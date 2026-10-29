package local.hyprwatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class NotesService : Service() {
    private var server: ServerSocket? = null
    private var clients: ExecutorService? = null
    private lateinit var notes: NotesStore
    private lateinit var health: HealthStore
    private lateinit var ingest: HealthIngest
    private lateinit var export: HealthConnectExport
    private lateinit var phone: AndroidPhone
    private lateinit var healthFeature: HealthFeature
    private lateinit var features: Features
    private lateinit var watch: WatchConnection
    private lateinit var reconnect: WatchReconnect
    private lateinit var sync: NotesSync
    private lateinit var token: String
    private var unlockRegistered = false
    private var bluetoothRegistered = false
    private val background = Executors.newSingleThreadExecutor()

    /** Reconnect triggers and their in-process timers; an attempt blocks here, never on the main thread. */
    private val reconnects = Executors.newSingleThreadScheduledExecutor()

    private lateinit var connects: Connects

    /** Wakeups' holds still open, released at shutdown rather than left to their timeout. */
    private val holds = HashSet<PowerManager.WakeLock>()
    private val destroyed = CountDownLatch(1)

    /** Refused its foreground start or failed to start: stopping, and not to be restarted. */
    @Volatile private var stopping = false

    /** Created while a restore swapped files: started once the restore sends its start command. */
    private var deferred = false

    // Receivers run on the main thread; the settings read and the capture lock stay off it.
    private val unlock = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_USER_PRESENT && ::watch.isInitialized) {
                background.execute {
                    try {
                        healthFeature.syncIfDue(health.unlockSyncMinutes() * 60_000L)
                    } catch (ignored: Exception) {
                    }
                }
            }
        }
    }

    // The adapter turning on, the watch being paired, or Android linking to it (it came back in range); and
    // that link going down, so a link seen in the quiet window counts only while it lasts.
    private val bluetooth = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (!::reconnect.isInitialized) return
            when (i.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_ON -> onReconnectThread { reconnect.phoneReady("bluetooth on") }
                    BluetoothAdapter.STATE_OFF -> onReconnectThread { reconnect.watchOutOfRange() }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> if (isWatch(i, bonded = true)) onReconnectThread { reconnect.phoneReady("watch paired") }
                BluetoothDevice.ACTION_ACL_CONNECTED -> if (isWatch(i, bonded = true)) onReconnectThread { reconnect.watchInRange() }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> if (isWatch(i, bonded = false)) onReconnectThread { reconnect.watchOutOfRange() }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("bridge", "Watch connection", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, NotesActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "bridge")
            .setContentTitle("Hypr Watch")
            .setContentText("Notes API available on this phone")
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        try {
            startForeground(1, notification)
        } catch (e: Exception) {
            // Refused (Bluetooth permission revoked, a start from the background): stop rather than crash on every start.
            Log.e("HyprWatch", "Service could not start in the foreground: ${e.javaClass.simpleName}")
            stopping = true
            stopSelf()
            return
        }
        // The Activity starts the service whenever it is recreated, also in the middle of a restore.
        if (PhoneBackup.restoring) deferred = true else startUp()
    }

    private fun startUp() {
        instance = this
        try {
            PhoneBackup.recover(this)
            token = readOrCreateToken(this)
            notes = NotesStore(this)
            health = HealthStore(this)
            ingest = HealthIngest(health)
            health.onNewVersion = ingest::request
            val healthApi = HealthApi(health)
            export = HealthConnectExport(health, HealthConnectWriter(this))
            ingest.onIngested = { export.request() }
            ingest.request()
            // Resumes days an interrupted export left; does nothing while the export is off.
            export.request()
            phone = AndroidPhone(this)
            healthFeature = HealthFeature(health, { path, query -> healthApi.route("GET", path, query) }, ingest::status, export)
            val list = appFeatures(healthFeature, WristNotificationListener.RouteSettings(this)) { NotesActivity.requestLocation(this) }
            features = Features(list, health, phone) { Log.i("HyprWatch", it) }
            val now = { System.currentTimeMillis() }
            reconnect = WatchReconnect(LinkStore(this), now, WatchReconnect.wakingSchedule(phone, reconnects, now, ::holdAwake), ::reconnectAttempt) { Log.i("HyprWatch", it) }
            watch = WatchConnection(this, features, reconnect)
            connects = Connects(reconnect) { n -> watch.connect(n, reconnect::wanted) }
            sync = NotesSync(notes, watch)
            val pool = Executors.newFixedThreadPool(4)
            clients = pool
            val listener = ServerSocket()
            server = listener
            listener.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 8766))
            serving = true
            val accept = Thread(
                {
                    while (!listener.isClosed) {
                        try {
                            val s = listener.accept()
                            pool.submit(Runnable { serve(s) })
                        } catch (e: IOException) {
                            break
                        }
                    }
                },
                "notes-api",
            )
            accept.isDaemon = true
            accept.start()
            val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(unlock, filter, RECEIVER_NOT_EXPORTED) else registerReceiver(unlock, filter)
            unlockRegistered = true
            val bt = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(bluetooth, bt, RECEIVER_NOT_EXPORTED) else registerReceiver(bluetooth, bt)
            bluetoothRegistered = true
            onReconnectThread { reconnect.start() }
        } catch (e: Exception) {
            Log.e("HyprWatch", "Service failed to start", e)
            stopping = true
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (deferred && !PhoneBackup.restoring) {
            deferred = false
            startUp()
        }
        // Refused its foreground start (a restart after a kill can be, on Android 12+) or failed to start:
        // it is stopping, and a restart would only fail again.
        if (stopping) return START_NOT_STICKY
        if (::watch.isInitialized) {
            val connect = intent?.getBooleanExtra(EXTRA_CONNECT, false) == true
            when (reconnect.serviceStart(connect, restart = intent == null)) {
                WatchReconnect.StartAction.CONNECT -> explicitConnect()
                WatchReconnect.StartAction.STOP -> {
                    Log.i("HyprWatch", "Restarted after a kill without the watch to keep; stopping")
                    stopSelf(startId)
                }
                WatchReconnect.StartAction.NONE -> if (connect) Log.i("HyprWatch", "Restore leaves the released watch alone")
            }
        }
        // Always sticky: Android keeps only the last start's answer, and the first connect usually claims the
        // watch after the Activity's start. A restart after a kill comes with no intent and decides above.
        return START_STICKY
    }

    /** An explicit connect (API, restore): claims the watch once it authenticates; joins an attempt already under way. */
    private fun explicitConnect(): Future<*> = connects.explicit()

    /** Waits for the attempt, so the wakeup's hold ([holdAwake]) keeps the phone awake through it. */
    private fun reconnectAttempt(n: Int) {
        val attempt = connects.attempt(n)
        try {
            attempt.get(ATTEMPT_S, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            throw IOException("Connect attempt took over $ATTEMPT_S s")
        }
    }

    /** A wakeup's hold on the CPU for one attempt; released by the returned function, at shutdown, or by its timeout. */
    private fun holdAwake(): () -> Unit {
        val wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HyprWatch:reconnect")
        wake.setReferenceCounted(false)
        wake.acquire((ATTEMPT_S + 10) * 1000)
        synchronized(holds) { holds.add(wake) }
        return { release(wake) }
    }

    private fun release(wake: PowerManager.WakeLock) {
        synchronized(holds) { holds.remove(wake) }
        try {
            wake.release()
        } catch (ignored: RuntimeException) {
        }
    }

    private fun onReconnectThread(task: () -> Unit) {
        try {
            reconnects.execute { guard("watch reconnect") { task() } }
        } catch (ignored: RejectedExecutionException) {
        }
    }

    /** Whether a broadcast's device is the watch; [bonded]: and still paired. */
    private fun isWatch(i: Intent, bonded: Boolean): Boolean =
        try {
            @Suppress("DEPRECATION")
            val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            d != null && (!bonded || d.bondState == BluetoothDevice.BOND_BONDED) && WatchConnection.isWatch(d.name)
        } catch (e: SecurityException) {
            false
        }

    private inline fun guard(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.i("HyprWatch", "$what failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (instance === this) {
            instance = null
            serving = false
        }
        if (unlockRegistered) unregisterReceiver(unlock)
        if (bluetoothRegistered) unregisterReceiver(bluetooth)
        // Before the link closes, so its drop schedules nothing on a stopped service.
        if (::reconnect.isInitialized) reconnect.shutdown()
        background.shutdownNow()
        reconnects.shutdownNow()
        for (wake in synchronized(holds) { holds.toList() }) release(wake)
        if (::sync.isInitialized) sync.shutdown()
        if (::watch.isInitialized) watch.shutdown()
        if (::features.isInitialized) features.close()
        if (::phone.isInitialized) phone.close()
        try {
            server?.close()
        } catch (ignored: IOException) {
        }
        clients?.shutdownNow()
        if (::notes.isInitialized) notes.close()
        if (::ingest.isInitialized) ingest.shutdown()
        if (::export.isInitialized) export.shutdown()
        if (::health.isInitialized) health.close()
        super.onDestroy()
        destroyed.countDown()
    }

    /** For a restore: true once [onDestroy] has closed both databases. */
    fun awaitDestroyed(ms: Long): Boolean = destroyed.await(ms, TimeUnit.MILLISECONDS)

    fun watchConnected(): Boolean = ::watch.isInitialized && watch.status().getString("state") == "connected"

    /** For a backup: consistent copies of both databases in [dir]. */
    fun snapshot(dir: File) {
        notes.snapshot(File(dir, "notes.db"))
        health.snapshot(File(dir, "health.db"))
    }

    private fun serve(socket: Socket) {
        try {
            socket.use { s ->
                s.soTimeout = 40000
                val input = s.getInputStream()
                val out = s.getOutputStream()
                Http.serve(input, out) { head -> handle(head, input, out) }
            }
        } catch (ignored: Exception) {
        }
    }

    private fun handle(head: Http.Head, input: InputStream, out: OutputStream) {
        val method = head.method
        val path = head.path
        val host = head.headers["host"] ?: ""
        if (!host.matches(Regex("(127\\.0\\.0\\.1|localhost)(:[0-9]+)?"))) {
            Http.json(out, 403, "{\"error\":\"Invalid host\"}")
            return
        }
        val origin = head.headers["origin"]
        if (origin != null && origin != "http://$host") {
            Http.json(out, 403, "{\"error\":\"Cross-origin access denied\"}")
            return
        }
        if ("GET" == method && "/favicon.ico" == path) {
            Http.respond(out, 204, null, ByteArray(0))
            return
        }
        if ("GET" == method && ("/" == path || "/app.js" == path || "/style.css" == path || "/theme.js" == path || "/geist.woff2" == path)) {
            val asset = if (path == "/") "index.html" else path.substring(1)
            val type = when {
                path.endsWith(".js") -> "text/javascript"
                path.endsWith(".css") -> "text/css"
                path.endsWith(".woff2") -> "font/woff2"
                else -> "text/html"
            }
            assets.open(asset).use { a -> Http.respond(out, 200, type, a.readBytes()) }
            return
        }
        val auth = head.headers["authorization"] ?: ""
        if (!MessageDigest.isEqual(auth.toByteArray(Charsets.UTF_8), "Bearer $token".toByteArray(Charsets.UTF_8))) {
            Http.json(out, 401, "{\"error\":\"Connect with your local access token\"}")
            return
        }
        Http.answer(head, input, out) { request, raw -> route(method, path, request, raw) }
    }

    /** Feature routes come last, so they cannot shadow these. [raw]: see [ApiRequest]. */
    private fun route(method: String, path: String, input: JSONObject, raw: ByteArray): JSONObject {
        if (method == "GET" && path == "/api/status") {
            val silent = "true" == notes.meta("silent_notes_supported")
            val status = JSONObject().put("watch", reconnect.status(watch.status())).put("sync", sync.status())
            features.status(status)
            return status
                .put("notes_count", notes.list().length())
                .put("watch_title_limit", 20)
                .put("silent_notes_supported", silent)
                .put("silent_notes_status", if ("true" == notes.meta("silent_notes_supported")) "Verified on Redmi Watch 5 Active 2.2.105" else "Not verified on this watch")
                .put("connection", "USB forwarding; Android holds the notes database")
        }
        if (method == "GET" && path == "/api/notes") return JSONObject().put("notes", notes.list())
        if (method == "POST" && path == "/api/notes") {
            val n = notes.save(null, input)
            sync.request()
            return JSONObject().put("note", n)
        }
        if (path.startsWith("/api/notes/")) {
            val id = path.substring(11)
            if (!id.matches(Regex("[0-9a-f-]{36}"))) throw IllegalArgumentException("Invalid note id")
            if (method == "GET") {
                val n = notes.get(id) ?: throw IllegalArgumentException("Note not found")
                return JSONObject().put("note", n)
            }
            if (method == "PATCH") {
                val n = notes.save(id, input)
                sync.request()
                return JSONObject().put("note", n)
            }
            if (method == "DELETE") {
                synchronized(notes) {
                    val n = notes.get(id) ?: throw IllegalArgumentException("Note not found")
                    SyncGuards.requireDeletable(n, notes.meta("pending_write"), notes.meta("pending_probe"))
                    notes.delete(id)
                    return JSONObject().put("deleted", true)
                }
            }
        }
        if (method == "POST" && path == "/api/watch/connect") {
            explicitConnect().get(30, TimeUnit.SECONDS)
            return JSONObject().put("watch", reconnect.status(watch.status()))
        }
        if (method == "POST" && path == "/api/watch/disconnect") {
            // Released first, so the drop this causes schedules nothing.
            reconnect.release()
            watch.disconnect()
            return JSONObject().put("watch", reconnect.status(watch.status()))
        }
        if (method == "GET" && path == "/api/watch/reminders") {
            val r = watch.reminders()
            notes.meta("last_watch_snapshot", r.toString())
            return r
        }
        if (method == "POST" && path == "/api/watch/probe-silent") {
            synchronized(notes) {
                return probeSilent(input.getString("note_id"))
            }
        }
        if (method == "POST" && path == "/api/watch/sync") {
            if ("true" != notes.meta("silent_notes_supported")) throw IllegalArgumentException("Silent reminder mode is not verified on this watch")
            if ("connected" != watch.status().getString("state")) throw IllegalArgumentException("Connect the watch first")
            sync.request()
            return JSONObject().put("queued", true).put("message", "Sync queued. Watch updates are checked automatically.")
        }
        if (method == "GET" && path == "/api/export") return JSONObject().put("version", 1).put("notes", notes.list())
        return features.route(method, path, input, raw) ?: throw IllegalArgumentException("Unknown API operation")
    }

    @Synchronized
    private fun probeSilent(noteId: String): JSONObject {
        if (notes.meta("pending_probe") != null) throw IllegalArgumentException("A previous probe needs reconciliation before another write")
        if (notes.meta("pending_write") != null) throw IllegalArgumentException("Reconnect and finish the pending watch sync before another probe")
        val note = notes.get(noteId)
        if (note == null || !note.isNull("watch_id")) throw IllegalArgumentException("Use an existing note that is not on the watch")
        val title = note.getString("watch_title")
        if (title.isEmpty()) throw IllegalArgumentException("Set a short watch title first")
        val before = watch.reminders()
        val old = before.getJSONArray("items")
        if (old.length() >= before.getInt("capacity")) throw IllegalArgumentException("Watch reminder slots are full")
        for (i in 0 until old.length()) {
            if (title == old.getJSONObject(i).getString("title")) throw IllegalArgumentException("Use a distinct test title")
        }
        val modified = System.currentTimeMillis() / 1000
        notes.meta("pending_probe", JSONObject().put("note_id", noteId).put("title", title).put("modified_second", modified).put("before", old).toString())
        val id = watch.createSilent(title, modified)
        val pending = JSONObject(notes.meta("pending_probe")!!).put("ack_id", id)
        notes.meta("pending_probe", pending.toString())
        val after = watch.reminders()
        val items = after.getJSONArray("items")
        var created: JSONObject? = null
        for (i in 0 until items.length()) {
            val r = items.getJSONObject(i)
            var existed = false
            for (j in 0 until old.length()) {
                if (old.getJSONObject(j).getLong("id") == r.getLong("id")) existed = true
            }
            if (!existed && title == r.getString("title") && (id < 0 || r.getLong("id") == id)) {
                if (created != null) throw IOException("Ambiguous probe result; no further writes")
                created = r
            }
        }
        if (created == null) throw IOException("Created reminder was not found; probe is paused for reconciliation")
        val silent = created.getInt("repeat_mode") == 15
        notes.meta("probe_result", JSONObject().put("supported", silent).put("reminder", created).toString())
        if (silent) {
            notes.save(noteId, JSONObject().put("watch_enabled", true))
            notes.setWatch(noteId, created.getLong("id"), NotesSync.identity(created).toString(), "awaiting_watch_check")
            notes.meta("silent_notes_supported", "true")
        } else {
            notes.meta("silent_notes_supported", "false")
            watch.deleteReminder(created.getLong("id"))
            val remaining = watch.reminders().getJSONArray("items")
            for (i in 0 until remaining.length()) {
                if (remaining.getJSONObject(i).getLong("id") == created.getLong("id")) throw IOException("Probe cleanup was not confirmed")
            }
        }
        notes.removeMeta("pending_probe")
        return JSONObject().put("supported", silent).put("created", created).put("after", after).put("cleanup", !silent)
    }

    companion object {
        /** Start extra: connect the watch after a restore, unless it was released. Claims it once connected, as an API connect does. */
        const val EXTRA_CONNECT = "local.hyprwatch.CONNECT"

        /** Longer than a connect takes (25 s socket deadline, 20 s handshake), within a wakeup's minute. */
        private const val ATTEMPT_S = 50L

        @Volatile
        @JvmField
        var instance: NotesService? = null

        /** True while this process's own server holds 127.0.0.1:8766; until then the Activity loads nothing from it. */
        @Volatile
        @JvmField
        var serving = false

        @JvmStatic
        @Throws(IOException::class)
        fun readOrCreateToken(c: Context): String {
            val f = File(c.filesDir, "api-token")
            if (f.exists()) return NotesStore.javaTrim(String(Files.readAllBytes(f.toPath()), Charsets.US_ASCII))
            val b = ByteArray(32)
            SecureRandom().nextBytes(b)
            val t = Base64.encodeToString(b, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
            FileOutputStream(f).use { out -> out.write(t.toByteArray(Charsets.US_ASCII)) }
            return t
        }
    }
}

/** Watch ownership and the last drop, in private prefs outside the databases, so a restore keeps them. */
class LinkStore(context: Context) : WatchReconnect.Store {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun load() =
        WatchReconnect.Saved(prefs.getBoolean("owned", false), prefs.getString("last_drop", "") ?: "", prefs.getLong("last_drop_at", 0), prefs.getBoolean("released", false))

    // Written through, so a release is on disk before the API answers and a force-stop cannot lose it.
    override fun save(saved: WatchReconnect.Saved) {
        prefs.edit()
            .putBoolean("owned", saved.owned)
            .putBoolean("released", saved.released)
            .putString("last_drop", saved.lastDrop)
            .putLong("last_drop_at", saved.lastDropAt)
            .commit()
    }

    companion object {
        private const val FILE = "watch-link"

        fun owned(context: Context): Boolean = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("owned", false)
    }
}

/**
 * After a reboot or an app update, starts the service when the app owns the watch, which then reconnects it.
 * Not without the Bluetooth permission: the connected-device service would be refused its foreground start.
 */
class LinkStartReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val start = i.action == Intent.ACTION_BOOT_COMPLETED || i.action == Intent.ACTION_MY_PACKAGE_REPLACED
        val owned = start && LinkStore.owned(c)
        val granted = c.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (!WatchReconnect.startsAfter(start, owned, Build.VERSION.SDK_INT, granted)) {
            if (owned) Log.i("HyprWatch", "Not starting after ${i.action}: Bluetooth permission is off")
            return
        }
        try {
            c.startForegroundService(Intent(c, NotesService::class.java))
        } catch (e: Exception) {
            Log.i("HyprWatch", "Service start after ${i.action} failed: ${e.javaClass.simpleName}")
        }
    }
}
