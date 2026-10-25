package local.hyprwatch

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Scripted host stand-in for the Bluetooth connection, with injectable lost acknowledgements. */
class WatchConnection {
    var items = JSONArray()
    var capacity = 20
    var creates = 0
    var edits = 0
    var deletes = 0
    var loseCreate = false
    var loseEdit = false
    var loseDelete = false

    fun status(): JSONObject = JSONObject().put("state", "connected")

    fun reminders(): JSONObject = JSONObject().put("items", JSONArray(items.toString())).put("capacity", capacity)

    fun createSilent(t: String, s: Long): Long {
        creates++
        var id = 0L
        for (i in 0 until items.length()) {
            id = maxOf(id, items.getJSONObject(i).getLong("id") + 1)
        }
        items.put(item(id, t, s))
        if (loseCreate) {
            loseCreate = false
            throw IOException("Simulated lost create ACK")
        }
        return id
    }

    fun editSilent(id: Long, t: String, s: Long) {
        edits++
        for (i in 0 until items.length()) {
            if (items.getJSONObject(i).getLong("id") == id) items.put(i, item(id, t, s))
        }
        if (loseEdit) {
            loseEdit = false
            throw IOException("Simulated lost edit ACK")
        }
    }

    fun deleteReminder(id: Long) {
        deletes++
        for (i in items.length() - 1 downTo 0) {
            if (items.getJSONObject(i).getLong("id") == id) items.remove(i)
        }
        if (loseDelete) {
            loseDelete = false
            throw IOException("Simulated lost delete ACK")
        }
    }

    companion object {
        fun item(id: Long, title: String, second: Long): JSONObject =
            JSONObject()
                .put("id", id)
                .put("title", title)
                .put("repeat_mode", 15)
                .put("source", 1)
                .put("completion_status", 1)
                .put("modified_second", second)
    }
}
