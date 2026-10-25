package local.hyprwatch

import org.json.JSONObject

/**
 * Not shipped: the docs/watch-features.md example. Type 99 is made up. The watch reports button
 * presses (99/1); on connect the phone greets it (99/0) and reads its counter (99/2), and the API
 * changes the greeting.
 */
class ExampleFeature : WatchFeature {
    override val name = "example"
    private lateinit var host: FeatureHost

    @Volatile private var presses = 0

    @Volatile private var counter: Long? = null

    override fun install(host: FeatureHost) {
        this.host = host
        host.on(99, 1) { presses++ }
        host.route("GET", "/api/example") { JSONObject().put("greeting", greeting()).put("presses", presses) }
        host.route("PUT", "/api/example/greeting/{text}") { r ->
            val text = r.params.getValue("text")
            require(text.length <= 20) { "Use a greeting of at most 20 characters" }
            host.settings.put("greeting", JSONObject().put("text", text))
            JSONObject().put("greeting", text).put("sent_to_watch", hello())
        }
    }

    override fun onConnected() {
        hello()
        // A request waits for the watch's reply, so it runs on the commands thread, never in a hook.
        host.submit { counter = Proto.number(host.request(Proto.command(99, 2)), 3, -1) }
    }

    override fun onDisconnected() {
        counter = null
    }

    override fun status(): JSONObject = JSONObject().put("presses", presses).put("counter", counter ?: JSONObject.NULL)

    private fun greeting(): String = host.settings.get("greeting")?.optString("text") ?: "hi"

    private fun hello(): Boolean = host.send(Proto.command(99, 0, Proto.text(3, greeting())))
}
