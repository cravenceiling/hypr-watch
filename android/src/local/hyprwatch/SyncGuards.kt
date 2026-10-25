package local.hyprwatch

import org.json.JSONObject

/** Called while holding the note store lock, so a sync cannot bind an id between check and deletion. */
object SyncGuards {
    @JvmStatic
    fun requireDeletable(note: JSONObject, write: String?, probe: String?) {
        for (intent in arrayOf(write, probe)) {
            if (intent != null && note.getString("id") == JSONObject(intent).getString("note_id")) {
                throw IllegalArgumentException("This note has a pending watch operation. Reconnect and let it finish before deleting it.")
            }
        }
        if (!note.isNull("watch_id")) throw IllegalArgumentException("Remove this note from the watch before deleting it")
    }
}
