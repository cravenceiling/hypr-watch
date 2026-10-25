package local.hyprwatch

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.lang.reflect.InvocationTargetException

object SyncTest {
    private var assertions = 0

    private fun check(b: Boolean, label: String) {
        assertions++
        if (!b) throw AssertionError(label)
    }

    private val recover = NotesSync::class.java.getDeclaredMethod("recoverProbe").apply { isAccessible = true }

    private class Setup {
        val n = NotesStore()
        val w = WatchConnection()
        val sync = NotesSync(n, w)

        init {
            sync.shutdown()
            n.meta("silent_notes_supported", "true")
            n.records["note"] = JSONObject()
                .put("id", "note")
                .put("title", "Example")
                .put("body", "Keep this full text")
                .put("watch_title", "Example")
                .put("watch_enabled", true)
                .put("done", false)
                .put("watch_id", JSONObject.NULL)
                .put("watch_fingerprint", JSONObject.NULL)
                .put("watch_state", "not_sent")
        }

        fun cycle() {
            val m = NotesSync::class.java.getDeclaredMethod("cycle")
            m.isAccessible = true
            try {
                m.invoke(sync)
            } catch (e: InvocationTargetException) {
                throw e.cause!!
            }
        }

        fun recoverProbe() {
            recover.invoke(sync)
        }

        fun note(): JSONObject = n.get("note")!!
    }

    @JvmStatic
    fun main(args: Array<String>) {
        var s = Setup()
        s.w.items.put(WatchConnection.item(7, "Other reminder", 12).put("source", 0).put("repeat_mode", 0))
        s.cycle()
        check(s.w.creates == 1 && s.w.items.length() == 2, "Create preserves unrelated reminder")
        check(s.note().getLong("watch_id") == 8L, "Binds returned id")
        s.cycle()
        check(s.w.creates == 1, "Repeat sync does not duplicate")

        s.n.save("note", JSONObject().put("watch_title", "Updated"))
        s.cycle()
        check(s.w.edits == 1 && s.w.items.length() == 2, "Title edit keeps id and unrelated item")
        check(s.note().getString("body") == "Keep this full text", "Body retained")

        s.n.save("note", JSONObject().put("done", true))
        s.cycle()
        check(
            s.w.deletes == 1 && s.w.items.length() == 1 && s.w.items.getJSONObject(0).getString("title") == "Other reminder",
            "Completion deletes only owned item",
        )

        s = Setup()
        s.w.loseCreate = true
        try {
            s.cycle()
            throw AssertionError("Expected lost ACK")
        } catch (expected: IOException) {
        }
        check(s.n.meta("pending_write") != null, "Intent persists before create")
        s.cycle()
        s.cycle()
        check(s.w.creates == 1 && s.w.items.length() == 1 && !s.note().isNull("watch_id"), "Lost create ACK reconciles without duplicate")

        s.n.save("note", JSONObject().put("watch_title", "Edit with lost ACK"))
        s.w.loseEdit = true
        try {
            s.cycle()
        } catch (expected: IOException) {
        }
        s.cycle()
        s.cycle()
        check(s.w.edits == 1 && s.note().getString("watch_state") == "synced", "Lost edit ACK reconciles")

        s.n.save("note", JSONObject().put("watch_enabled", false))
        s.w.loseDelete = true
        try {
            s.cycle()
        } catch (expected: IOException) {
        }
        s.cycle()
        check(s.w.deletes == 1 && s.note().isNull("watch_id"), "Lost delete ACK reconciles")

        s = Setup()
        s.cycle()
        var r = s.w.items.getJSONObject(0)
        r.put("completion_status", 2).put("modified_second", r.getLong("modified_second") + 100)
        s.cycle()
        check(s.note().getBoolean("done") && s.w.items.length() == 0, "Watch completion with new timestamp returns to phone")

        s = Setup()
        s.cycle()
        s.w.items = JSONArray()
        s.cycle()
        check(s.note().getBoolean("watch_enabled"), "First empty read does not unlink")
        s.cycle()
        check(!s.note().getBoolean("watch_enabled") && s.note().isNull("watch_id"), "Last item removal confirmed by two reads")
        check(s.note().getString("body") == "Keep this full text", "Watch deletion never deletes full note")

        s = Setup()
        s.cycle()
        r = s.w.items.getJSONObject(0)
        r.put("modified_second", r.getLong("modified_second") + 5).put("title", "Foreign note")
        s.n.save("note", JSONObject().put("done", true))
        s.cycle()
        check(
            s.w.deletes == 0 && s.w.items.length() == 1 && s.note().getString("watch_state") == "watch_changed",
            "Reused or changed id is not overwritten/deleted",
        )

        s = Setup()
        s.w.capacity = 1
        s.w.items.put(WatchConnection.item(1, "Existing", 12))
        s.cycle()
        check(s.w.creates == 0 && s.note().getString("watch_state") == "watch_full", "Capacity does not overwrite existing reminder")

        s = Setup()
        val probe = JSONObject().put("note_id", "note").put("title", "Example").put("modified_second", 123).put("before", JSONArray())
        s.n.meta("pending_probe", probe.toString())
        s.n.meta("silent_notes_supported", "false")
        s.w.items.put(WatchConnection.item(0, "Example", 123))
        s.recoverProbe()
        check(
            s.n.meta("pending_probe") == null && "true" == s.n.meta("silent_notes_supported") && !s.note().isNull("watch_id"),
            "Lost probe ACK reconciles and enables sync",
        )

        s = Setup()
        s.n.meta("pending_probe", probe.toString())
        s.recoverProbe()
        check(s.n.meta("pending_probe") != null, "First missing probe read stays pending")
        s.recoverProbe()
        check(s.n.meta("pending_probe") == null, "Second missing probe read permits retry")

        s = Setup()
        s.n.meta("pending_probe", probe.toString())
        s.w.items.put(WatchConnection.item(0, "Example", 124).put("completion_status", 2))
        s.recoverProbe()
        check(s.n.meta("pending_probe") == null && !s.note().isNull("watch_id"), "Probe completion with changed timestamp reconciles")
        s.cycle()
        check(s.note().getBoolean("done"), "Recovered completed probe completes full note")

        val unlinked = Setup().note()
        var blocked = false
        try {
            SyncGuards.requireDeletable(unlinked, JSONObject().put("note_id", "note").put("kind", "create").toString(), null)
        } catch (e: IllegalArgumentException) {
            blocked = true
        }
        check(blocked, "Pending create blocks note deletion before id binding")

        blocked = false
        try {
            SyncGuards.requireDeletable(unlinked, null, probe.toString())
        } catch (e: IllegalArgumentException) {
            blocked = true
        }
        check(blocked, "Pending probe blocks note deletion")
        SyncGuards.requireDeletable(unlinked, JSONObject().put("note_id", "another").toString(), null)
        check(true, "Other note intent does not block unlinked deletion")

        s = Setup()
        s.n.meta("pending_probe", probe.toString())
        s.w.items.put(WatchConnection.item(0, "Example", 123).put("completion_status", 2).put("repeat_mode", 0))
        s.recoverProbe()
        check(
            s.w.items.length() == 0 && "false" == s.n.meta("silent_notes_supported") && s.n.meta("pending_probe") == null,
            "Unsupported completed probe is cleaned up and capability disabled",
        )

        println("Sync recovery, completion, removal, ownership and capacity: $assertions assertions passed")
    }
}
