package local.hyprwatch

import org.json.JSONArray
import org.json.JSONObject

/** In-memory host stand-in for the SQLite store, compiled with the real NotesSync. */
class NotesStore {
    val records = LinkedHashMap<String, JSONObject>()
    val metadata = HashMap<String, String>()

    @Synchronized
    fun list(): JSONArray {
        val a = JSONArray()
        for (n in records.values) {
            a.put(JSONObject(n.toString()))
        }
        return a
    }

    @Synchronized
    fun get(id: String): JSONObject? = records[id]?.let { JSONObject(it.toString()) }

    @Synchronized
    fun save(id: String, input: JSONObject): JSONObject? {
        val n = records[id]!!
        for (k in input.keySet()) {
            n.put(k, input.get(k))
        }
        return get(id)
    }

    @Synchronized
    fun setWatch(id: String, wid: Long?, f: String?, s: String) {
        val n = records[id]!!
        n.put("watch_id", wid ?: JSONObject.NULL).put("watch_fingerprint", f ?: JSONObject.NULL).put("watch_state", s)
    }

    @Synchronized
    fun meta(k: String, v: String) {
        metadata[k] = v
    }

    @Synchronized
    fun meta(k: String): String? = metadata[k]

    @Synchronized
    fun removeMeta(k: String) {
        metadata.remove(k)
    }
}
