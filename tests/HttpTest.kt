package local.hyprwatch

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Requests refused before routing get a status and a JSON error instead of a closed socket. */
object HttpTest {
    private var assertions = 0

    private fun check(b: Boolean, label: String) {
        assertions++
        if (!b) throw AssertionError(label)
    }

    /**
     * The response to an authorized [request] as NotesService answers it (Http.answer), and the body
     * its route got: the JSON, or a large-body route's bytes. [route] answers, `{}` by default.
     */
    private fun exchange(request: String, route: ((JSONObject, ByteArray) -> JSONObject)? = null): Pair<String, String?> {
        val input = ByteArrayInputStream(request.toByteArray(Charsets.ISO_8859_1))
        val out = ByteArrayOutputStream()
        var read: String? = null
        Http.serve(input, out) { head ->
            Http.answer(head, input, out) { body, raw ->
                read = if (raw.isEmpty()) body.toString() else String(raw, Charsets.UTF_8)
                route?.invoke(body, raw) ?: JSONObject()
            }
        }
        return out.toString(Charsets.UTF_8.name()) to read
    }

    private fun statusAndBody(response: String): String = response.substringBefore("\r\n") + " " + response.substringAfter("\r\n\r\n")

    @JvmStatic
    fun main(args: Array<String>) {
        val ok = exchange("POST /api/notes HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 2\r\n\r\n{}")
        check(statusAndBody(ok.first) == "HTTP/1.1 200 OK {}" && ok.second == "{}", "A well-formed request reaches the handler with its body")
        check(ok.first.contains("\r\nContent-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'\r\n"), "Self-only CSP")

        val cases = listOf(
            "POST /api/notes HTTP/1.1\r\nContent-Length: 100001\r\n\r\n" to "HTTP/1.1 413 Error {\"error\":\"Request body is larger than 100000 bytes\"}",
            "POST /api/notes HTTP/1.1\r\nContent-Length: 99999999999999999999999\r\n\r\n" to "HTTP/1.1 413 Error {\"error\":\"Request body is larger than 100000 bytes\"}",
            "POST /api/notes HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n2\r\n{}\r\n0\r\n\r\n" to
                "HTTP/1.1 411 Error {\"error\":\"Send a Content-Length; chunked requests are not supported\"}",
            "POST /api/notes HTTP/1.1\r\nContent-Length: 2x\r\n\r\n{}" to "HTTP/1.1 400 Error {\"error\":\"Invalid Content-Length\"}",
            "POST /api/notes HTTP/1.1\r\nContent-Length: -1\r\n\r\n" to "HTTP/1.1 400 Error {\"error\":\"Invalid Content-Length\"}",
            "POST /api/notes HTTP/1.1\r\nContent-Length: 1\r\n\r\n{" to "HTTP/1.1 400 Error {\"error\":\"Invalid JSON\"}",
            "GET /api/status HTTP/1.1\r\nHost 127.0.0.1\r\n\r\n" to "HTTP/1.1 400 Error {\"error\":\"Invalid header\"}",
            "GET /api/status\r\n\r\n" to "HTTP/1.1 400 Error {\"error\":\"Invalid HTTP request\"}",
            "GET /api/status HTTP/1.1\r\n" + "X: ${"a".repeat(8000)}\r\n".repeat(3) + "\r\n" to "HTTP/1.1 400 Error {\"error\":\"Headers too large\"}",
            "GET /${"a".repeat(9000)} HTTP/1.1\r\n\r\n" to "HTTP/1.1 400 Error {\"error\":\"HTTP line too long\"}",
        )
        for ((request, expected) in cases) {
            val (response, read) = exchange(request)
            check(statusAndBody(response) == expected && read == null, "${request.substringBefore("\r\n").take(40)}: got ${statusAndBody(response)}")
        }

        // One route may send a larger body; the cap stays bounded and ignores the query.
        check(Http.bodyLimit("POST", "/api/watch-faces/upload") == 8_388_608 && Http.bodyLimit("POST", "/api/watch-faces/upload?x=1") == 8_388_608, "Upload route cap")
        check(Http.bodyLimit("GET", "/api/watch-faces/upload") == 100_000 && Http.bodyLimit("POST", "/api/watch-faces") == 100_000 && Http.bodyLimit("POST", "/api/notes") == 100_000, "Every other route keeps 100000")
        val large = "{\"data\":\"" + "A".repeat(200_000) + "\"}"
        val upload = exchange("POST /api/watch-faces/upload HTTP/1.1\r\nContent-Length: ${large.length}\r\n\r\n$large")
        check(statusAndBody(upload.first) == "HTTP/1.1 200 OK {}" && upload.second == large, "A 200 kB face upload is read")
        val other = exchange("POST /api/notes HTTP/1.1\r\nContent-Length: ${large.length}\r\n\r\n$large")
        check(statusAndBody(other.first) == "HTTP/1.1 413 Error {\"error\":\"Request body is larger than 100000 bytes\"}" && other.second == null, "Not on another route")
        val huge = exchange("POST /api/watch-faces/upload HTTP/1.1\r\nContent-Length: 8388609\r\n\r\n")
        check(statusAndBody(huge.first) == "HTTP/1.1 413 Error {\"error\":\"Request body is larger than 8388608 bytes\"}" && huge.second == null, "8 MiB + 1 is refused before reading")

        // One large body at a time, from before it is read until its route returns: a second upload meanwhile is
        // refused before its body is read; small bodies are unaffected. A large body reaches its route as bytes.
        var meanwhile: Pair<Pair<String, String?>, Pair<String, String?>>? = null
        val first = exchange("POST /api/watch-faces/upload?x=1 HTTP/1.1\r\nContent-Length: ${large.length}\r\n\r\n$large") { body, raw ->
            meanwhile = exchange("POST /api/watch-faces/upload HTTP/1.1\r\nContent-Length: ${large.length}\r\n\r\n$large") to
                exchange("POST /api/notes HTTP/1.1\r\nContent-Length: 9\r\n\r\n{\"a\":\"b\"}")
            JSONObject().put("got", "${body.length()} keys, ${raw.size} bytes")
        }
        check(statusAndBody(first.first) == "HTTP/1.1 200 OK {\"got\":\"0 keys, 200011 bytes\"}", "The upload route gets {} and the bytes: ${first.first}")
        val (second, small) = meanwhile!!
        check(statusAndBody(second.first) == "HTTP/1.1 409 Error {\"error\":\"Another large upload is in progress\"}" && second.second == null, "Second large body: 409, unread")
        check(statusAndBody(small.first) == "HTTP/1.1 200 OK {}" && small.second == "{\"a\":\"b\"}", "A small body meanwhile is read as JSON")
        val failed = exchange("POST /api/watch-faces/upload HTTP/1.1\r\nContent-Length: 2\r\n\r\n{}") { _, _ -> throw IllegalStateException("route failed") }
        check(statusAndBody(failed.first) == "HTTP/1.1 503 Error {\"error\":\"route failed\"}", "A failing route answers 503")
        check(statusAndBody(exchange("POST /api/watch-faces/upload HTTP/1.1\r\nContent-Length: 2\r\n\r\n{}").first) == "HTTP/1.1 200 OK {}", "The permit is released, even after a failure")

        var closed = false
        try {
            exchange("POST /api/notes HTTP/1.1\r\nContent-Length: 5\r\n\r\n{}")
        } catch (e: java.io.IOException) {
            closed = e.message == "Truncated request"
        }
        check(closed, "A client that sends less than it announced is not answered")
        println("HTTP framing: $assertions assertions passed")
    }
}
