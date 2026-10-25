package local.hyprwatch

import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Semaphore

/** HTTP/1.1 framing for the loopback API, apart from Android so host tests drive it. */
object Http {
    /** A request refused before routing; answered with [status] and a JSON error. */
    class Failure(val status: Int, message: String) : Exception(message)

    /** A route refused because a competing operation holds what it needs; answered 409. */
    class Conflict(message: String) : Exception(message)

    class Head(val method: String, val path: String, val headers: Map<String, String>)

    const val MAX_BODY = 100_000

    /** The watch face upload's cap: a 6 MB face file in base64 inside JSON. */
    const val MAX_UPLOAD_BODY = 8 * 1024 * 1024

    /** Routes whose body may exceed [MAX_BODY]: `METHOD path`, without the query. */
    private val LARGE_BODIES = mapOf("POST /api/watch-faces/upload" to MAX_UPLOAD_BODY)

    /** The largest body [method] [target] may send. */
    fun bodyLimit(method: String, target: String): Int = LARGE_BODIES["$method ${target.substringBefore('?')}"] ?: MAX_BODY

    /** One large body in memory at a time, from before its bytes are read until its route returns. */
    private val largeBody = Semaphore(1)

    /** Runs [block], holding the large-body permit when [head]'s route takes a large body; 409 [Failure] while another request holds it. */
    fun <T> bodyPermit(head: Head, block: (large: Boolean) -> T): T {
        if (bodyLimit(head.method, head.path) <= MAX_BODY) return block(false)
        if (!largeBody.tryAcquire()) throw Failure(409, "Another large upload is in progress")
        try {
            return block(true)
        } finally {
            largeBody.release()
        }
    }

    private val QUERY_NAME = Regex("[a-z_]{1,32}")

    /** `a=1&b=2` without percent-decoding: every accepted value is plain ASCII. */
    fun parseQuery(raw: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (part in raw.split('&')) {
            if (part.isEmpty()) continue
            val eq = part.indexOf('=')
            val key = if (eq < 0) part else part.substring(0, eq)
            if (!key.matches(QUERY_NAME)) throw IllegalArgumentException("Invalid query parameter")
            if (out.put(key, if (eq < 0) "" else part.substring(eq + 1)) != null) throw IllegalArgumentException("Repeated query parameter '$key'")
        }
        return out
    }

    /** Runs a route: 200 with its result, 400 for IllegalArgumentException, 409 for [Conflict], 503 with the root cause for any other failure. */
    fun outcome(route: () -> JSONObject): Pair<Int, JSONObject> =
        try {
            200 to route()
        } catch (e: IllegalArgumentException) {
            400 to JSONObject().put("error", e.message)
        } catch (e: Conflict) {
            409 to JSONObject().put("error", e.message)
        } catch (e: Exception) {
            var cause: Throwable = e
            while (cause.cause != null) cause = cause.cause!!
            503 to JSONObject().put("error", cause.message ?: "Operation failed")
        }

    /**
     * An authorized request: reads its body under [bodyPermit], runs [route] with the body as JSON,
     * or, for a large-body route, `{}` and the bytes (never one more String copy), and answers.
     */
    fun answer(head: Head, input: InputStream, out: OutputStream, route: (body: JSONObject, raw: ByteArray) -> JSONObject) {
        bodyPermit(head) { large ->
            val body = body(head, input)
            val request = try {
                if (large || body.isEmpty()) JSONObject() else JSONObject(String(body, Charsets.UTF_8))
            } catch (e: JSONException) {
                return@bodyPermit json(out, 400, "{\"error\":\"Invalid JSON\"}")
            }
            val (status, result) = outcome { route(request, if (large) body else ByteArray(0)) }
            json(out, status, result.toString())
        }
    }

    /** Reads the request head and runs [handle]; a [Failure] from either is answered, other errors close the socket. */
    fun serve(input: InputStream, out: OutputStream, handle: (Head) -> Unit) {
        try {
            handle(head(input))
        } catch (e: Failure) {
            json(out, e.status, JSONObject().put("error", e.message).toString())
        }
    }

    private fun head(input: InputStream): Head {
        // Java's split(" ") drops trailing empty strings; keep that so "GET / HTTP/1.1 " still parses.
        val req = line(input).split(" ").dropLastWhile { it.isEmpty() }
        if (req.size != 3) throw Failure(400, "Invalid HTTP request")
        val headers = HashMap<String, String>()
        var total = 0
        while (true) {
            val l = line(input)
            if (l.isEmpty()) break
            total += l.length
            if (total > 16384) throw Failure(400, "Headers too large")
            val colon = l.indexOf(':')
            if (colon < 0) throw Failure(400, "Invalid header")
            headers[l.substring(0, colon).lowercase()] = l.substring(colon + 1).trim { it <= ' ' }
        }
        return Head(req[0], req[1], headers)
    }

    /** The body announced by Content-Length; call only after the request is authorized. */
    fun body(head: Head, input: InputStream): ByteArray {
        if (head.headers.containsKey("transfer-encoding")) throw Failure(411, "Send a Content-Length; chunked requests are not supported")
        val declared = head.headers["content-length"] ?: "0"
        if (!declared.matches(Regex("[0-9]+"))) throw Failure(400, "Invalid Content-Length")
        val length = declared.toLongOrNull()
        val limit = bodyLimit(head.method, head.path)
        if (length == null || length > limit) throw Failure(413, "Request body is larger than $limit bytes")
        val body = ByteArray(length.toInt())
        var pos = 0
        while (pos < body.size) {
            val n = input.read(body, pos, body.size - pos)
            if (n < 0) throw IOException("Truncated request")
            pos += n
        }
        return body
    }

    fun json(out: OutputStream, status: Int, body: String) = respond(out, status, "application/json", body.toByteArray(Charsets.UTF_8))

    /** A null type means no content: 204 without Content-Type or Content-Length. */
    fun respond(out: OutputStream, status: Int, type: String?, body: ByteArray) {
        val reason = when (status) {
            200 -> "OK"
            204 -> "No Content"
            else -> "Error"
        }
        val content = if (type == null) "" else "Content-Type: " + type + "; charset=utf-8\r\nContent-Length: " + body.size + "\r\n"
        val headers = "HTTP/1.1 " + status + " " + reason + "\r\n" +
            content +
            "Cache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'\r\n" +
            "Connection: close\r\n\r\n"
        out.write(headers.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    private fun line(input: InputStream): String {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c == -1) break
            if (c == 10) return b.toString(Charsets.US_ASCII.name()).replace("\r", "")
            b.write(c)
            if (b.size() > 8192) throw Failure(400, "HTTP line too long")
        }
        throw EOFException()
    }
}
