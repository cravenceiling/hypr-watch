package local.hyprwatch

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Reconciles one owned watch mutation per cycle; intent survives a lost acknowledgement. */
class NotesSync(private val notes: NotesStore, private val watch: WatchConnection) {
    private val worker = Executors.newSingleThreadScheduledExecutor()

    @Volatile
    private var requested = false

    init {
        worker.scheduleWithFixedDelay({ run() }, 3, 15, TimeUnit.SECONDS)
    }

    fun request() {
        if (!requested) {
            requested = true
            worker.submit(Runnable { run() })
        }
    }

    fun shutdown() {
        worker.shutdownNow()
    }

    fun status(): JSONObject {
        val value = notes.meta("sync_report")
        return if (value == null) JSONObject().put("state", "idle") else JSONObject(value)
    }

    private fun run() {
        requested = false
        try {
            if ("connected" != watch.status().getString("state")) return
            synchronized(notes) {
                if (notes.meta("pending_probe") != null) {
                    recoverProbe()
                    return
                }
                if ("true" == notes.meta("silent_notes_supported")) cycle()
            }
        } catch (e: Exception) {
            try {
                val message = rootCause(e).message ?: "Sync interrupted"
                notes.meta("sync_report", JSONObject().put("state", "error").put("message", message).put("at", System.currentTimeMillis()).toString())
            } catch (ignored: Exception) {
            }
        }
    }

    private fun cycle() {
        val snapshot = watch.reminders()
        val items = snapshot.getJSONArray("items")
        notes.meta("last_watch_snapshot", snapshot.toString())
        val pending = notes.meta("pending_write")
        if (pending != null) {
            recover(JSONObject(pending), items)
            report("reconciled", "Previous write checked against the watch")
            return
        }
        val all = notes.list()
        var active = 0
        for (i in 0 until all.length()) {
            val n = all.getJSONObject(i)
            val nid = n.getString("id")
            var wanted = n.getBoolean("watch_enabled") && !n.getBoolean("done")
            if (wanted) active++
            var present = if (n.isNull("watch_id")) null else byId(items, n.getLong("watch_id"))
            if (!n.isNull("watch_id")) {
                var fingerprint = JSONObject(n.getString("watch_fingerprint"))
                if (present == null) {
                    val missing = "missing:$nid"
                    val count = (notes.meta(missing) ?: "0").toInt()
                    if (count < 1) {
                        notes.meta(missing, "1")
                        notes.setWatch(nid, n.getLong("watch_id"), fingerprint.toString(), "checking_watch_removal")
                        continue
                    }
                    notes.save(nid, JSONObject().put("watch_enabled", false))
                    notes.setWatch(nid, null, null, "removed_on_watch")
                    notes.removeMeta(missing)
                    continue
                }
                notes.removeMeta("missing:$nid")
                if (!matches(present, fingerprint)) {
                    // Completing a task on this firmware changes its timestamp as well as status.
                    // Accept that one documented transition after a second read; never adopt edits to title/source/mode.
                    if (completedFrom(present, fingerprint)) {
                        val confirmation = byId(watch.reminders().getJSONArray("items"), n.getLong("watch_id"))
                        if (confirmation != null && matches(confirmation, identity(present)) && confirmation.getInt("completion_status") == 2) {
                            fingerprint = identity(confirmation)
                            present = confirmation
                            notes.setWatch(nid, present.getLong("id"), fingerprint.toString(), "completed_on_watch")
                        } else {
                            notes.setWatch(nid, n.getLong("watch_id"), fingerprint.toString(), "watch_changed")
                            continue
                        }
                    } else {
                        notes.setWatch(nid, n.getLong("watch_id"), fingerprint.toString(), "watch_changed")
                        continue
                    }
                }
                if (present.getInt("completion_status") == 2) {
                    notes.save(nid, JSONObject().put("done", true))
                    wanted = false
                }
                if (!wanted) {
                    mutate(JSONObject().put("kind", "delete").put("note_id", nid).put("id", present.getLong("id")).put("old", fingerprint), items)
                    return
                }
                val title = n.getString("watch_title")
                if (title.isEmpty()) {
                    notes.setWatch(nid, present.getLong("id"), fingerprint.toString(), "needs_watch_title")
                    continue
                }
                if (title != present.getString("title")) {
                    mutate(
                        JSONObject().put("kind", "edit").put("note_id", nid).put("id", present.getLong("id")).put("old", fingerprint).put("desired", desired(title)),
                        items,
                    )
                    return
                }
                notes.setWatch(nid, present.getLong("id"), fingerprint.toString(), "synced")
            } else if (wanted) {
                if ("watch_changed" == n.getString("watch_state")) continue
                if (items.length() >= snapshot.getInt("capacity")) {
                    notes.setWatch(nid, null, null, "watch_full")
                    continue
                }
                val title = n.getString("watch_title")
                if (title.isEmpty()) {
                    notes.setWatch(nid, null, null, "needs_watch_title")
                    continue
                }
                mutate(JSONObject().put("kind", "create").put("note_id", nid).put("desired", desired(title)), items)
                return
            }
        }
        report("up_to_date", "$active active note${if (active == 1) "" else "s"} checked")
    }

    private fun desired(title: String): JSONObject {
        val previous = (notes.meta("last_write_second") ?: "0").toLong()
        val time = maxOf(System.currentTimeMillis() / 1000, previous + 1)
        notes.meta("last_write_second", time.toString())
        return JSONObject().put("title", title).put("repeat_mode", 15).put("source", 1).put("modified_second", time)
    }

    private fun recoverProbe() {
        val p = JSONObject(notes.meta("pending_probe")!!)
        val snapshot = watch.reminders()
        val items = snapshot.getJSONArray("items")
        notes.meta("last_watch_snapshot", snapshot.toString())
        val nid = p.getString("note_id")
        if (notes.get(nid) == null) throw IOException("Interrupted probe needs inspection: its phone note is missing")
        val expected = JSONObject().put("title", p.getString("title")).put("repeat_mode", 15).put("source", 1).put("modified_second", p.getLong("modified_second"))
        var found: JSONObject? = null
        var uncertain = false
        for (i in 0 until items.length()) {
            val r = items.getJSONObject(i)
            if (r.getString("title") != p.getString("title")) continue
            if (r.getInt("source") == 1 && r.getLong("modified_second") == p.getLong("modified_second") || completedFrom(r, expected)) {
                if (found != null) throw IOException("Ambiguous probe result; no further writes")
                found = r
            } else {
                uncertain = true
            }
        }
        if (found != null && !uncertain) {
            if (found.getInt("completion_status") == 2) {
                val confirmed = byId(watch.reminders().getJSONArray("items"), found.getLong("id"))
                if (!sameIdentity(confirmed, identity(found)) || confirmed!!.getInt("completion_status") != 2) {
                    throw IOException("Probe completion changed while checking; no further writes")
                }
            }
            if (found.getInt("repeat_mode") == 15) {
                notes.save(nid, JSONObject().put("watch_enabled", true))
                notes.setWatch(nid, found.getLong("id"), identity(found).toString(), "synced")
                notes.meta("silent_notes_supported", "true")
            } else {
                notes.meta("silent_notes_supported", "false")
                watch.deleteReminder(found.getLong("id"))
                if (byId(watch.reminders().getJSONArray("items"), found.getLong("id")) != null) throw IOException("Probe cleanup not yet confirmed")
            }
            notes.removeMeta("pending_probe")
            report("probe_recovered", "Interrupted capability test reconciled")
            return
        }
        if (uncertain) throw IOException("Interrupted probe changed on the watch. Inspect or remove that test item before continuing.")
        val missing = p.optInt("missing_reads", 0) + 1
        if (missing < 2) {
            p.put("missing_reads", missing)
            notes.meta("pending_probe", p.toString())
            report("checking_probe", "Confirming the interrupted test did not persist")
            return
        }
        notes.removeMeta("pending_probe")
        report("probe_recovered", "Interrupted test left no watch item; it can be retried")
    }

    private fun mutate(intent: JSONObject, before: JSONArray) {
        intent.put("before", before)
        notes.meta("pending_write", intent.toString())
        report("syncing", intent.getString("kind") + " watch note")
        when (intent.getString("kind")) {
            "create" -> {
                val d = intent.getJSONObject("desired")
                val id = watch.createSilent(d.getString("title"), d.getLong("modified_second"))
                intent.put("ack_id", id)
                notes.meta("pending_write", intent.toString())
            }
            "edit" -> {
                val d = intent.getJSONObject("desired")
                watch.editSilent(intent.getLong("id"), d.getString("title"), d.getLong("modified_second"))
            }
            else -> watch.deleteReminder(intent.getLong("id"))
        }
        val after = watch.reminders()
        notes.meta("last_watch_snapshot", after.toString())
        recover(intent, after.getJSONArray("items"))
        report("synced", "Watch write verified")
    }

    private fun recover(p: JSONObject, items: JSONArray) {
        val kind = p.getString("kind")
        val nid = p.getString("note_id")
        val note = notes.get(nid) ?: throw IOException("Pending watch write refers to a missing phone note")
        if (kind == "create") {
            var match: JSONObject? = null
            for (i in 0 until items.length()) {
                val r = items.getJSONObject(i)
                if (matches(r, p.getJSONObject("desired"))) {
                    if (match != null) throw IOException("Ambiguous watch note; sync paused")
                    match = r
                }
            }
            if (match != null) {
                notes.setWatch(nid, match.getLong("id"), identity(match).toString(), "synced")
                notes.removeMeta("pending_write")
                return
            }
            val before = p.getJSONArray("before")
            var unexpected: JSONObject? = null
            for (i in 0 until items.length()) {
                val r = items.getJSONObject(i)
                if (byId(before, r.getLong("id")) == null && r.getString("title") == p.getJSONObject("desired").getString("title")) {
                    if (unexpected != null) throw IOException("Ambiguous unverified write; sync paused")
                    unexpected = r
                }
            }
            if (unexpected != null) throw IOException("Watch changed the no-alert write; sync paused for inspection")
            val count = p.optInt("missing_reads", 0) + 1
            if (count < 2) {
                p.put("missing_reads", count)
                notes.meta("pending_write", p.toString())
                return
            }
            notes.setWatch(nid, null, null, "not_sent")
            notes.removeMeta("pending_write")
            return
        }
        val r = byId(items, p.getLong("id"))
        if (kind == "delete" && r == null) {
            notes.setWatch(nid, null, null, if (note.getBoolean("done")) "completed" else "not_sent")
            notes.removeMeta("pending_write")
            return
        }
        if (kind == "edit" && matches(r, p.getJSONObject("desired"))) {
            notes.setWatch(nid, r!!.getLong("id"), identity(r).toString(), "synced")
            notes.removeMeta("pending_write")
            return
        }
        if (matches(r, p.getJSONObject("old"))) {
            notes.removeMeta("pending_write")
            return
        }
        val watchId = if (note.isNull("watch_id")) null else note.getLong("watch_id")
        // Same call as before: a null fallback, which Android's org.json accepts despite its annotation.
        @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
        val fingerprint: String? = note.optString("watch_fingerprint", null)
        notes.setWatch(nid, watchId, fingerprint, "watch_changed")
        notes.removeMeta("pending_write")
    }

    private fun report(state: String, message: String) {
        notes.meta("sync_report", JSONObject().put("state", state).put("message", message).put("at", System.currentTimeMillis()).toString())
    }

    companion object {
        @JvmStatic
        fun identity(r: JSONObject): JSONObject =
            JSONObject()
                .put("title", r.getString("title"))
                .put("repeat_mode", r.getInt("repeat_mode"))
                .put("source", r.getInt("source"))
                .put("completion_status", r.getInt("completion_status"))
                .put("modified_second", r.getLong("modified_second"))

        /** Innermost cause, whose message is what callers report. */
        fun rootCause(e: Throwable): Throwable {
            var cause = e
            while (cause.cause != null) {
                cause = cause.cause!!
            }
            return cause
        }

        private fun sameIdentity(r: JSONObject?, f: JSONObject): Boolean =
            r != null &&
                r.getInt("repeat_mode") == f.getInt("repeat_mode") &&
                r.getString("title") == f.getString("title") &&
                r.getLong("modified_second") == f.getLong("modified_second") &&
                r.getInt("source") == f.optInt("source", 1)

        internal fun matches(r: JSONObject?, f: JSONObject): Boolean =
            r != null && r.getInt("repeat_mode") == 15 && f.getInt("repeat_mode") == 15 && sameIdentity(r, f)

        internal fun completedFrom(r: JSONObject?, f: JSONObject): Boolean =
            r != null &&
                r.getInt("completion_status") == 2 &&
                f.optInt("completion_status", 1) == 1 &&
                r.getInt("repeat_mode") == 15 &&
                f.getInt("repeat_mode") == 15 &&
                r.getString("title") == f.getString("title") &&
                r.getInt("source") == f.optInt("source", 1) &&
                r.getLong("modified_second") >= f.getLong("modified_second")

        private fun byId(items: JSONArray, id: Long): JSONObject? {
            for (i in 0 until items.length()) {
                val r = items.getJSONObject(i)
                if (r.getLong("id") == id) return r
            }
            return null
        }
    }
}
