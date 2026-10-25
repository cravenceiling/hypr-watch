package local.hyprwatch

import org.json.JSONObject
import java.io.IOException

/**
 * Host harness for watch features: runs them on [Features] as the app does, over a [FakeWatch]
 * instead of Bluetooth. Every action ends by running the tasks features submitted, so a test sees
 * their effect at once. Starts disconnected.
 */
class FeatureRig(vararg installed: WatchFeature, val phone: FakePhone = FakePhone(), val settings: MemSettings = MemSettings()) {
    val logs = ArrayList<String>()
    val watch = FakeWatch { logs.add(it) }
    val features = Features(installed.toList(), settings, phone) { logs.add(it) }.also { it.install(watch) }

    /** Authentication finished: connect hooks run. */
    fun connect() {
        watch.epoch++
        watch.connected = true
        features.connected()
        drain()
    }

    /** The link dropped: disconnect hooks run, queued tasks are dropped. */
    fun disconnect() {
        watch.epoch++
        watch.connected = false
        watch.dataInFlight = 0
        features.disconnected()
        drain()
    }

    /** A watch-initiated command, as built by Proto.command; throws when no feature handles it. */
    fun receive(cmd: ByteArray) {
        val c = Proto.decode(cmd)
        val key = Proto.number(c, 1, -1).toInt() to Proto.number(c, 2, -1).toInt()
        val handler = watch.handlers[key] ?: throw IllegalStateException("No feature handles ${key.first}/${key.second}")
        handler(c)
        drain()
    }

    /** Channel-5 data. */
    fun chunk(data: ByteArray) {
        watch.chunks?.invoke(data)
        drain()
    }

    /** A link ack freed room in the send window; it acks every upload chunk in flight too. */
    fun ack() {
        watch.dataInFlight = 0
        watch.acked?.invoke()
        drain()
    }

    /** A link ack for the oldest [n] upload chunks in flight. */
    fun ackData(n: Int) {
        check(n in 1..watch.dataInFlight) { "Only ${watch.dataInFlight} upload chunks are in flight" }
        watch.dataInFlight -= n
        watch.acked?.invoke()
        drain()
    }

    /** Upload chunks sent on channel 2 so far, as hex; [takeData] also clears. */
    fun data(): List<String> = watch.data.map(HealthCapture::hex)

    fun takeData(): List<String> = data().also { watch.data.clear() }

    /** Answers request()s for [type]/[sub] with the command [reply] builds; a field 100 status other than 0 is a rejection. */
    fun answer(type: Int, sub: Int, reply: (Map<Int, List<Any>>) -> ByteArray) {
        watch.answers[type to sub] = reply
    }

    /** Commands sent and requested so far, as hex, oldest first. */
    fun sent(): List<String> = watch.sent.map(HealthCapture::hex)

    /** [sent], then forgets them. */
    fun take(): List<String> = sent().also { watch.sent.clear() }

    /**
     * Status and JSON body as NotesService answers an authorized request; [target] may carry a query.
     * A large-body route gets [raw], by default [body]'s bytes, as NotesService passes them.
     */
    fun call(method: String, target: String, body: JSONObject = JSONObject(), raw: ByteArray? = null): Pair<Int, JSONObject> {
        val large = Http.bodyLimit(method, target) > Http.MAX_BODY
        return Http.outcome {
            val routed = if (large) features.route(method, target, JSONObject(), raw ?: body.toString().toByteArray()) else features.route(method, target, body)
            routed ?: throw IllegalArgumentException("Unknown API operation")
        }.also { drain() }
    }

    /** What the features add to /api/status. */
    fun status(): JSONObject = JSONObject().also(features::status)

    /** Runs the timer tasks that are due by now, as if their delays passed. */
    fun fire() {
        watch.fire()
        drain()
    }

    /** Runs submitted tasks, including those they submit. */
    fun drain() {
        while (true) (watch.tasks.removeFirstOrNull() ?: return)()
    }
}

/** [WatchPort] in memory: records sends and requests, answers requests from [answers]. */
class FakeWatch(private val log: (String) -> Unit) : WatchPort {
    override var connected = false
        get() {
            onConnectedRead?.invoke()
            return field
        }

    /** Runs whenever a feature reads [connected]: a test's hook for interleavings. */
    @Volatile var onConnectedRead: (() -> Unit)? = null

    /** What DataUpload last told the link: true while an upload is under way. */
    var tracing = false

    /** Sends return false, as with a full send window. */
    var refuse = false

    /** Runs after each command is sent or requested, on the sending thread: a test's hook for interleavings. */
    @Volatile var onSend: (() -> Unit)? = null
    val sent = ArrayList<ByteArray>()

    /** Bumped by FeatureRig's connect and disconnect, as a link reset does. */
    var epoch = 0L

    /** Upload chunks sent, how many await a link ack, and how many may (the link's upload window). */
    val data = ArrayList<ByteArray>()
    var dataInFlight = 0
    var dataWindow = 3

    /** The link refuses upload data outright, as a closed writer or an oversize frame would. */
    var dataBroken: String? = null
    val answers = HashMap<Pair<Int, Int>, (Map<Int, List<Any>>) -> ByteArray>()
    val handlers = HashMap<Pair<Int, Int>, (Map<Int, List<Any>>) -> Unit>()
    var chunks: ((ByteArray) -> Unit)? = null
    var acked: (() -> Unit)? = null
    val tasks = ArrayDeque<() -> Unit>()
    private val timers = ArrayList<Pair<Long, () -> Unit>>()

    override fun on(type: Int, sub: Int, handler: (Map<Int, List<Any>>) -> Unit) {
        handlers[type to sub] = handler
    }

    override fun onChunk(handler: (ByteArray) -> Unit) {
        chunks = handler
    }

    override fun onAcked(handler: () -> Unit) {
        acked = handler
    }

    override fun send(what: String, cmd: ByteArray): Boolean {
        val refusal = if (!connected) "watch is not connected" else if (refuse) "Watch has not acknowledged ${SendWindow.MAX_TRACKED} frames" else null
        if (refusal != null) {
            log("Dropped a $what: $refusal")
            return false
        }
        sent.add(cmd)
        onSend?.invoke()
        return true
    }

    /** As [send]: tests of pacing drive HealthCapture or the real link instead. */
    override fun sendPaced(what: String, cmd: ByteArray): Boolean = send(what, cmd)

    override fun traceUpload(on: Boolean) {
        tracing = on
    }

    override fun linkEpoch(): Long = epoch

    override fun sendData(epoch: Long, chunk: ByteArray): Boolean {
        current(epoch)
        if (!connected) throw IOException("Watch is not connected")
        dataBroken?.let { throw IOException(it) }
        if (refuse || dataInFlight >= dataWindow) return false
        data.add(chunk)
        dataInFlight++
        return true
    }

    override fun dataInFlight(epoch: Long): Int = dataInFlight.also { current(epoch) }

    private fun current(epoch: Long) {
        if (epoch != this.epoch) throw IOException("The watch link restarted during the upload")
    }

    override fun request(cmd: ByteArray): Map<Int, List<Any>> {
        if (!connected) throw IOException("Connect the watch first")
        sent.add(cmd)
        onSend?.invoke()
        val c = Proto.decode(cmd)
        val type = Proto.number(c, 1, -1).toInt()
        val sub = Proto.number(c, 2, -1).toInt()
        val answer = answers[type to sub] ?: throw IOException("No reply to command $type/$sub")
        val r = Proto.decode(answer(c))
        val status = Proto.number(r, 100, 0)
        if (status != 0L) throw IOException("Watch rejected command $type/$sub with status $status")
        return r
    }

    override fun submit(task: () -> Unit) {
        tasks.addLast(task)
    }

    override fun after(ms: Long, task: () -> Unit): () -> Unit {
        val t = ms to task
        timers.add(t)
        return { timers.remove(t) }
    }

    /** Delays of the pending timer tasks. */
    fun delays(): List<Long> = timers.map { it.first }

    fun fire() {
        val due = ArrayList(timers)
        timers.clear()
        for (t in due) t.second()
    }
}

/** HealthStore's settings table in memory; [onPut] runs after each write. */
class MemSettings : SettingsStore {
    val values = LinkedHashMap<String, String>()
    var onPut: ((String) -> Unit)? = null

    override fun setting(key: String): String? = values[key]

    override fun setting(key: String, value: String) {
        values[key] = value
        onPut?.invoke(key)
    }
}

/**
 * [Phone] with answers a test sets; [tick] runs the [every] tasks, [wake] the due [at] tasks,
 * [mediaChanged] the media listeners, [timeChanged] the time listeners and [ringEnded] ends a ring
 * on its own.
 */
class FakePhone : Phone {
    var location: PhoneLocation? = null
    val labels = HashMap<String, String>()
    var access = true
    var playing: NowPlaying? = null
    var volume = 40
    val actions = ArrayList<MediaAction>()
    val periodic = ArrayList<Pair<Long, () -> Unit>>()
    private val mediaListeners = ArrayList<() -> Unit>()

    override fun coarseLocation(): PhoneLocation? = location

    var locationState = LocationAccess.ALLOWED

    override fun locationAccess(): LocationAccess = locationState

    /** Fix listeners; a test sets [location] and runs them. */
    val fixListeners = ArrayList<() -> Unit>()

    override fun onLocation(listener: () -> Unit): () -> Unit {
        fixListeners.add(listener)
        return { fixListeners.remove(listener) }
    }

    override fun appLabel(pkg: String): String = labels[pkg] ?: pkg

    /** Icons by package, as ARGB pixels; [appIcon] records each size asked for. */
    val icons = HashMap<String, IntArray>()
    val iconSizes = ArrayList<Pair<String, Int>>()

    override fun appIcon(pkg: String, size: Int): IntArray? {
        iconSizes.add(pkg to size)
        return icons[pkg]
    }

    override fun mediaAccess(): Boolean = access

    override fun nowPlaying(): NowPlaying? = playing

    override fun musicVolume(): Int = volume

    override fun media(action: MediaAction): Boolean {
        val volume = action == MediaAction.VOLUME_UP || action == MediaAction.VOLUME_DOWN
        if (!volume && playing == null) return false
        actions.add(action)
        return true
    }

    /** As on Android, a listener registered without access never fires. */
    override fun onMediaChanged(listener: () -> Unit): () -> Unit {
        if (!access) return {}
        mediaListeners.add(listener)
        return { mediaListeners.remove(listener) }
    }

    override fun every(ms: Long, task: () -> Unit): () -> Unit {
        val p = ms to task
        periodic.add(p)
        return { periodic.remove(p) }
    }

    /** [at] tasks as (wall-clock ms, task), oldest first. */
    val wakeups = ArrayList<Pair<Long, () -> Unit>>()

    override fun at(wallMs: Long, task: () -> Unit): () -> Unit {
        val w = wallMs to task
        wakeups.add(w)
        return { wakeups.remove(w) }
    }

    /** Runs the [at] tasks due by [nowMs] and forgets them. */
    fun wake(nowMs: Long) {
        val due = wakeups.filter { it.first <= nowMs }
        wakeups.removeAll(due)
        due.forEach { it.second() }
    }

    var uses24Hour = true

    /** [ringPhone] fails, as with no ringtone and no vibrator. */
    var cannotRing = false

    /** The ring in progress as (timeout ms, stopped callback); null when silent. */
    var ring: Pair<Long, () -> Unit>? = null
    private val timeListeners = ArrayList<() -> Unit>()

    override fun uses24HourTime(): Boolean = uses24Hour

    override fun onTimeChanged(listener: () -> Unit): () -> Unit {
        timeListeners.add(listener)
        return { timeListeners.remove(listener) }
    }

    override fun ringPhone(ms: Long, stopped: () -> Unit): Boolean {
        if (cannotRing) return false
        ring = ms to stopped
        return true
    }

    override fun stopRinging() {
        ring = null
    }

    /** The clock, time zone or 12/24-hour setting changed. */
    fun timeChanged() = ArrayList(timeListeners).forEach { it() }

    /** The ring stopped on its own: timeout or the user opened the app. */
    fun ringEnded() {
        val r = ring ?: return
        ring = null
        r.second()
    }

    fun tick() = ArrayList(periodic).forEach { it.second() }

    fun mediaChanged() = ArrayList(mediaListeners).forEach { it() }
}
