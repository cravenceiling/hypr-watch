package local.hyprwatch

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * One SPP connection: socket, handshake and reminder decoding. [session] decides which thread runs what.
 * [reconnect] hears when the link comes up and goes down.
 */
class WatchConnection(private val context: Context, features: Features, private val reconnect: WatchReconnect) {
    private val commands = Executors.newSingleThreadExecutor()
    private val timers = Executors.newSingleThreadScheduledExecutor()
    private val timer = WatchTimer(::after)
    private val link = SppLink(::write, timer, ::log, { reason -> fail(IOException(reason)) }, { Log.d(TAG, it) })
    private val session: WatchSession = WatchSession(link, timer, ::log, { auth.isAuthenticated }, commands, features)
    // Runs under the auth lock with a disconnect before or after it, so a dropped session never reads as connected.
    // A link released while it authenticated is closed off this lock instead, on the thread that opens
    // sockets, and only while the socket that authenticated is still the current one.
    private val auth: WatchAuth = WatchAuth(link, session, timer, { reason -> fail(IOException(reason)) }, { stage = it }) {
        if (reconnect.connected()) {
            state = "connected"
            handshake?.complete(null)
        } else {
            handshake?.complete(null)
            val seen = socket
            try {
                commands.execute { if (LinkGuards.backstopCloses(seen, socket, reconnect.wanted())) disconnect() }
            } catch (ignored: RejectedExecutionException) {
            }
        }
    }

    @Volatile private var socket: BluetoothSocket? = null

    /** The attempt the last connect ran as; the link's failures are reported as that attempt's. */
    @Volatile private var attempt = 0
    @Volatile private var writer: FrameWriter? = null
    @Volatile private var v2 = false
    @Volatile private var state = "disconnected"
    @Volatile private var error = ""
    @Volatile private var handshake: CompletableFuture<Void?>? = null
    @Volatile private var stage = "idle"
    @Volatile private var framesReceived = 0
    @Volatile private var sessionConfig = ""
    private lateinit var crypto: WatchCrypto

    // Last, once every field a feature callback can reach is set: [session] reads [auth].
    init {
        session.install()
    }

    fun status(): JSONObject =
        JSONObject()
            // While the watch restarts its session the link carries nothing until the new 1/27.
            .put("state", if (state == "connected" && !auth.isAuthenticated) "connecting" else state)
            .put("error", error)
            .put("stage", stage)
            .put("frames_received", framesReceived)
            .put("link_acks", link.acks)
            .put("session_config", sessionConfig)
            .put("last_response_ms", link.lastCommandAt)
            .put("transport", "Bluetooth Classic SPP")

    /** Connects as [attempt] unless the link is up; skipped or closed when [wanted] no longer holds once its turn comes. */
    fun connect(attempt: Int, wanted: () -> Boolean): Future<*> =
        commands.submit(
            Runnable {
                this.attempt = attempt
                try {
                    if (wanted()) open(wanted)
                } catch (e: Exception) {
                    fail(e)
                }
            },
        )

    private fun open(wanted: () -> Boolean) {
        if (auth.isAuthenticated && socket != null) return
        close(IOException("Watch reconnecting"))
        state = "connecting"
        error = ""
        stage = "opening socket"
        framesReceived = 0
        sessionConfig = ""
        v2 = false
        val hex = authKey()
        val key = ByteArray(16) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        val cipher = WatchCrypto(key)
        key.fill(0)
        crypto = cipher
        link.encrypt = { cipher.encrypt(it) }
        link.decrypt = { cipher.decrypt(it) }
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
        if (adapter == null || !adapter.isEnabled) throw PhoneSetupException("Turn on phone Bluetooth")
        var target: BluetoothDevice? = null
        for (d in adapter.bondedDevices) {
            if (isWatch(d.name)) {
                if (target != null) throw PhoneSetupException("More than one matching watch is paired")
                target = d
            }
        }
        if (target == null) throw PhoneSetupException("Pair the Redmi Watch 5 Active in Android Bluetooth settings first")
        val active = target.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805f9b34fb"))
        if (!LinkGuards.adopt(active, { socket = it }, wanted)) return disconnect()
        val timeout = Executors.newSingleThreadScheduledExecutor()
        val deadline = timeout.schedule(
            Runnable {
                if (!auth.isAuthenticated) {
                    try {
                        active.close()
                    } catch (ignored: IOException) {
                    }
                }
            },
            25,
            TimeUnit.SECONDS,
        )
        try {
            active.connect()
            writer = FrameWriter(active.outputStream) { e -> if (socket === active) fail(e) }
            val pendingHandshake = CompletableFuture<Void?>()
            handshake = pendingHandshake
            val reader = Thread({ readLoop(active) }, "watch-reader")
            reader.isDaemon = true
            reader.start()
            stage = "waiting for transport version"
            link.raw(Frames.VERSION)
            pendingHandshake.get(20, TimeUnit.SECONDS)
        } finally {
            deadline.cancel(false)
            timeout.shutdown()
        }
    }

    // The file's content never reaches an error message: a JSON error would quote it, key included.
    private fun authKey(): String {
        val file = File(context.filesDir, "watch.json")
        if (!file.exists()) throw PhoneSetupException("Watch key not configured: add files/watch.json with auth_key set to the watch's 32-digit hex key", needsKey = true)
        val hex = try {
            JSONObject(String(Files.readAllBytes(file.toPath()), Charsets.UTF_8)).getString("auth_key").replaceFirst(Regex("^0[xX]"), "")
        } catch (e: Exception) {
            ""
        }
        if (!hex.matches(Regex("[0-9a-fA-F]{32}"))) throw PhoneSetupException("Watch key in files/watch.json is invalid: auth_key must be 32 hex digits", needsKey = true)
        return hex
    }

    private fun readLoop(active: BluetoothSocket) {
        val frames = Frames.Reader(::log)
        val b = ByteArray(4096)
        try {
            val input = active.inputStream
            while (socket === active) {
                val n = input.read(b)
                if (n == -1) break
                frames.add(b, n)
                while (socket === active) {
                    val p = frames.next(v2) ?: break
                    framesReceived++
                    if (!v2) {
                        if (p.channel != 0 || p.payload.isEmpty() || (p.payload[0].toInt() and 255) < 2) {
                            throw IOException("Unsupported watch transport version")
                        }
                        v2 = true
                        stage = "waiting for session"
                        link.raw(Frames.encode(2, 0, Frames.SESSION))
                    } else if (p.type == 2) {
                        if (p.opcode == 2) {
                            if (sessionConfig.isEmpty()) {
                                sessionConfig = Frames.sessionConfig(p.payload)
                                log("Watch session config: $sessionConfig")
                            } else {
                                log("Watch sent its session config again; restarting the link and authentication")
                            }
                            link.configure(p.payload)
                            auth.begin(crypto)
                        }
                    } else {
                        link.receive(p)
                    }
                }
            }
            if (socket === active) throw IOException("Watch disconnected")
        } catch (e: Exception) {
            if (socket === active) fail(e)
        }
    }

    private fun write(b: ByteArray) {
        (writer ?: throw IOException("Watch is disconnected")).write(b)
    }

    private fun request(type: Int, sub: Int, vararg fields: ByteArray): Map<Int, List<Any>> =
        link.request(Proto.command(type, sub, *fields), type, sub)

    fun reminders(): JSONObject =
        session.command {
            val cmd = request(17, 14)
            val schedule = Proto.message(cmd, 19)
            if (!schedule.containsKey(10)) throw IOException("Watch did not return a reminder list")
            val list = Proto.message(schedule, 10)
            val items = JSONArray()
            for (raw in list[1] ?: emptyList()) {
                val item = Proto.decode(raw as ByteArray)
                val details = Proto.message(item, 2)
                val j = JSONObject().put("id", Proto.number(item, 1, -1)).put("title", Proto.string(details, 5))
                j.put("raw_details", Base64.encodeToString(Proto.blob(item, 2), Base64.NO_WRAP))
                j.put("repeat_mode", Proto.number(details, 3, 0))
                    .put("source", Proto.number(details, 7, 0))
                    .put("completion_status", Proto.number(details, 8, 0))
                    .put("modified_second", Proto.number(details, 9, 0))
                val fields = JSONArray()
                for (f in details.keys) {
                    fields.put(f)
                }
                j.put("detail_fields", fields)
                if (details.containsKey(1)) {
                    val date = Proto.message(details, 1)
                    j.put("year", Proto.number(date, 1, 0)).put("month", Proto.number(date, 2, 0)).put("day", Proto.number(date, 3, 0))
                }
                if (details.containsKey(2)) {
                    val time = Proto.message(details, 2)
                    j.put("hour", Proto.number(time, 1, 0)).put("minute", Proto.number(time, 2, 0))
                }
                items.put(j)
            }
            JSONObject().put("items", items).put("capacity", Proto.number(list, 2, 0)).put("received_at", System.currentTimeMillis())
        }

    fun createSilent(title: String, modified: Long): Long =
        session.command {
            val details = silentDetails(title, modified)
            val r = request(17, 15, Proto.bytes(19, Proto.bytes(14, details)))
            Proto.number(Proto.message(r, 19), 4, -1)
        }

    fun editSilent(id: Long, title: String, modified: Long) {
        session.command { request(17, 17, Proto.bytes(19, Proto.bytes(15, Proto.join(Proto.num(1, id), Proto.bytes(2, silentDetails(title, modified)))))) }
    }

    fun deleteReminder(id: Long) {
        session.command { request(17, 18, Proto.bytes(19, Proto.bytes(17, Proto.num(1, id)))) }
    }

    fun disconnect() {
        close(IOException("Watch disconnected"))
        state = "disconnected"
        error = ""
    }

    /** A failure the reader reported reaches here again from the connect waiting for the handshake; [LinkGuards.cause] keeps its reason. */
    private fun fail(failure: Exception) {
        val e = LinkGuards.cause(failure)
        val wasUp = state == "connected"
        error = LinkGuards.errorText(e, stage)
        close(e)
        state = "error"
        reconnect.failed(error, wasUp, e is PhoneSetupException, (e as? PhoneSetupException)?.needsKey == true, attempt)
        handshake?.completeExceptionally(e)
    }

    private fun close(cause: Exception) {
        auth.disconnected(cause)
        val s = socket
        socket = null
        writer?.close()
        writer = null
        if (s != null) {
            try {
                s.close()
            } catch (ignored: IOException) {
            }
        }
    }

    fun shutdown() {
        disconnect()
        commands.shutdownNow()
        timers.shutdownNow()
    }

    private fun after(ms: Long, task: () -> Unit): () -> Unit {
        val f = timers.schedule(Runnable { guard("timer") { task() } }, ms, TimeUnit.MILLISECONDS)
        return { f.cancel(false) }
    }

    private fun guard(what: String, f: () -> Unit) {
        try {
            f()
        } catch (e: Exception) {
            log("$what failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun log(message: String) {
        Log.i(TAG, message)
    }

    companion object {
        private const val TAG = "HyprWatch"

        /** Whether a bonded device's [name] is the watch, as [open] picks it. */
        fun isWatch(name: String?): Boolean = name != null && name.replace(" ", "").lowercase().startsWith("redmiwatch5active")

        internal fun silentDetails(title: String, modified: Long): ByteArray {
            if (title.isEmpty() || title.codePointCount(0, title.length) > 20) {
                throw IllegalArgumentException("Use a watch title between 1 and 20 characters")
            }
            return Proto.join(
                Proto.bytes(1, Proto.join(Proto.num(1, 0), Proto.num(2, 0), Proto.num(3, 0))),
                Proto.bytes(2, Proto.join(Proto.num(1, 0), Proto.num(2, 0))),
                Proto.num(3, 15),
                Proto.num(4, 0),
                Proto.text(5, title),
                Proto.num(7, 1),
                Proto.num(8, 1),
                Proto.num(9, modified),
            )
        }
    }
}
