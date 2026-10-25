package local.hyprwatch

import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A failure on the phone's side (Bluetooth off, no paired watch, no key): retrying cannot fix it.
 * [needsKey]: the key is missing, invalid or rejected by the watch, so only a new key helps.
 */
class PhoneSetupException(message: String, val needsKey: Boolean = false) : IOException(message)

/**
 * When the app opens the watch link on its own. Only while it owns the watch: an explicit connect
 * claims it once it authenticates and an explicit disconnect releases it, so the link never comes
 * back after the watch was handed to Mi Fitness or after an explicit connect that failed. [store]
 * keeps ownership and the last drop across restarts.
 *
 * After a drop or a failed attempt the next one waits [BACKOFF_MS]; a phone-side problem (Bluetooth
 * off, watch not paired) waits for [phoneReady] or [watchInRange] instead, and a key problem waits
 * for an explicit connect. Android-free and driven by events, so host tests run it on a fake clock.
 * [attempt] connects as the numbered attempt and may block until that attempt ends; it is never
 * called under the lock, nor while an attempt is under way or the link is up.
 */
class WatchReconnect(
    private val store: Store,
    private val now: () -> Long,
    private val schedule: (wallMs: Long, task: () -> Unit) -> () -> Unit,
    private val attempt: (Int) -> Unit,
    private val log: (String) -> Unit,
) {
    /** [released]: the last change of hands was an explicit disconnect; a fresh install has neither flag. */
    data class Saved(val owned: Boolean, val lastDrop: String, val lastDropAt: Long, val released: Boolean = false)

    interface Store {
        fun load(): Saved

        fun save(saved: Saved)
    }

    enum class Phase { IDLE, CONNECTING, UP, WAITING, NEEDS_PHONE, NEEDS_KEY }

    /** For [explicitConnect]'s caller: run [attempt] itself, or wait for it when it [joins] one under way. */
    data class Turn(val attempt: Int, val joins: Boolean)

    /** What a service start does besides [start]; see [serviceStart]. */
    enum class StartAction { NONE, CONNECT, STOP }

    private val lock = Any()
    private var saved = store.load()
    private var phase = Phase.IDLE

    /** An explicit connect is under way: it claims the watch if it authenticates, and nothing if it fails. */
    private var explicit = false

    /** Attempts failed since the link was last up or the phone last changed; picks the next delay. */
    private var failures = 0

    /** The number of the latest attempt; while it is under way, only its own failure counts. */
    private var current = 0

    /** Attempts since the link was last up, dropped or released, for the log. */
    private var tries = 0
    private var nextAt = 0L

    /** When the last attempt or link ended, for [watchInRange]. */
    private var endedAt = Long.MIN_VALUE / 2

    /** Android's link to the watch is up, as its last broadcast said. */
    private var linked = false
    private var cancelTimer: (() -> Unit)? = null

    /** A look at the end of the quiet window, after [watchInRange] was ignored within it. */
    private var cancelCheck: (() -> Unit)? = null
    private var closed = false

    /** Bumped by every timer change, so a timer that fires after its cancel does nothing. */
    private var timerGen = 0

    val owned: Boolean get() = synchronized(lock) { saved.owned }

    /** Whether a connect may still go ahead: the app owns the watch, or an explicit connect is under way. */
    fun wanted(): Boolean = synchronized(lock) { !closed && (saved.owned || explicit) }

    /** Whether a restore's restart may connect (and so claim) the watch: the app owns it or never released it. */
    val restoreMayConnect: Boolean get() = synchronized(lock) { saved.owned || !saved.released }

    val phaseNow: Phase get() = synchronized(lock) { phase }

    /** On service start: connects when the app owned the watch before it stopped. */
    fun start() = trigger("service start", reset = true)

    /**
     * What a service start does besides [start]. The service is always sticky, since ownership can
     * change after its last start: a restart after Android killed it ([restart], no intent) stops
     * again unless the app owns the watch. A restore's start ([connect]) connects unless the watch
     * was released, as it may be with Mi Fitness and a connect would take it back for good.
     */
    fun serviceStart(connect: Boolean, restart: Boolean): StartAction =
        synchronized(lock) {
            when {
                restart && !saved.owned -> StartAction.STOP
                connect && (saved.owned || !saved.released) -> StartAction.CONNECT
                else -> StartAction.NONE
            }
        }

    /**
     * An explicit connect: cancels the timer. The caller runs the returned attempt, or waits for it
     * when it joins one under way. Claims the watch only through [connected].
     */
    fun explicitConnect(): Turn =
        synchronized(lock) {
            clearTimer()
            if (phase == Phase.UP) return Turn(current, joins = false)
            explicit = true
            failures = 0
            if (phase == Phase.CONNECTING) return Turn(current, joins = true)
            phase = Phase.CONNECTING
            Turn(begin(), joins = false)
        }

    /** An explicit disconnect: the watch may go to another companion, so nothing reconnects. */
    fun release() {
        synchronized(lock) {
            if (saved.owned || !saved.released) save(saved.copy(owned = false, released = true))
            explicit = false
            clearTimer()
            failures = 0
            tries = 0
            phase = Phase.IDLE
        }
    }

    /**
     * The link authenticated. Claims the watch after an explicit connect. False when the link is no
     * longer wanted (released while it authenticated): the caller closes it.
     */
    fun connected(): Boolean =
        synchronized(lock) {
            clearTimer()
            if (closed || !(saved.owned || explicit)) {
                phase = Phase.IDLE
                return false
            }
            if (!saved.owned || saved.released) save(saved.copy(owned = true, released = false))
            explicit = false
            failures = 0
            tries = 0
            phase = Phase.UP
            true
        }

    /**
     * The link of [attempt] went down: a drop when it [wasUp], else a failed attempt. [phoneSide]
     * means retrying cannot help until the phone changes, [needsKey] until the key does. A second
     * report of one failure changes nothing, and neither does the end of an older attempt while a
     * newer one is under way. A failed explicit connect leaves ownership as it was.
     */
    fun failed(reason: String, wasUp: Boolean, phoneSide: Boolean, needsKey: Boolean = false, attempt: Int? = null) {
        synchronized(lock) {
            if (phase == Phase.CONNECTING && attempt != null && attempt != current) return
            if (wasUp) {
                save(saved.copy(lastDrop = reason, lastDropAt = now()))
                log("Watch link dropped: $reason")
                tries = 0
            } else if (phase == Phase.UP || phase == Phase.WAITING || phase == Phase.NEEDS_PHONE || phase == Phase.NEEDS_KEY) {
                // UP: an attempt that timed out while its login still went through; the link is up.
                return
            }
            endedAt = now()
            explicit = false
            clearTimer()
            if (!saved.owned || closed) {
                phase = Phase.IDLE
                return
            }
            if (needsKey) {
                phase = Phase.NEEDS_KEY
                log("Watch reconnect stopped until the next connect: $reason")
                return
            }
            if (phoneSide) {
                phase = Phase.NEEDS_PHONE
                log("Watch reconnect waits for the phone: $reason")
                return
            }
            val delay = BACKOFF_MS[failures.coerceAtMost(BACKOFF_MS.size - 1)]
            failures++
            phase = Phase.WAITING
            nextAt = now() + delay
            val gen = timerGen
            cancelTimer = schedule(nextAt) { fire(gen) }
            if (!wasUp) log("Watch reconnect attempt failed: $reason; next in ${delay / 1000} s")
        }
    }

    /** Bluetooth turned on, or the watch was paired: try now, and start the backoff over. */
    fun phoneReady(why: String) = trigger(why, reset = true)

    /**
     * Android linked to the paired watch. An attempt brings that link up itself, so within [QUIET_MS]
     * of the last attempt's end this is likely its own echo: it waits for the window's end, and tries
     * then only if the link is still up (the watch came back) and the backoff timer is not due first.
     * Otherwise it tries now, keeping the backoff.
     */
    fun watchInRange() {
        val reset = synchronized(lock) {
            linked = true
            if (phase == Phase.WAITING && now() - endedAt < QUIET_MS) {
                val at = endedAt + QUIET_MS
                if (cancelCheck == null && at < nextAt) {
                    val gen = timerGen
                    cancelCheck = schedule(at) { look(gen) }
                }
                return
            }
            phase == Phase.NEEDS_PHONE
        }
        trigger("watch in range", reset)
    }

    /** Android's link to the paired watch went down. */
    fun watchOutOfRange() {
        synchronized(lock) { linked = false }
    }

    private fun look(gen: Int) {
        val due = synchronized(lock) {
            if (gen != timerGen) return
            cancelCheck = null
            phase == Phase.WAITING && linked
        }
        if (due) trigger("watch still in range", reset = false)
    }

    private fun fire(gen: Int) {
        val due = synchronized(lock) { gen == timerGen && phase == Phase.WAITING }
        if (due) trigger("timer", reset = false)
    }

    private fun trigger(why: String, reset: Boolean) {
        val (n, nth) = synchronized(lock) {
            if (!saved.owned || closed) return
            if (phase == Phase.CONNECTING || phase == Phase.UP || phase == Phase.NEEDS_KEY) return
            clearTimer()
            if (reset) failures = 0
            phase = Phase.CONNECTING
            begin() to tries
        }
        log("Watch reconnect attempt $nth ($why)")
        try {
            attempt(n)
        } catch (e: Exception) {
            failed(e.message ?: e.javaClass.simpleName, wasUp = false, phoneSide = false, attempt = n)
        }
    }

    /** Under [lock]: numbers a new attempt. */
    private fun begin(): Int {
        tries++
        return ++current
    }

    /** The service is stopping: nothing is scheduled or attempted from now on. Ownership stays. */
    fun shutdown() {
        synchronized(lock) {
            closed = true
            clearTimer()
        }
    }

    /** Under [lock]. */
    private fun clearTimer() {
        timerGen++
        nextAt = 0
        cancelTimer?.invoke()
        cancelTimer = null
        cancelCheck?.invoke()
        cancelCheck = null
    }

    /** Under [lock]. */
    private fun save(s: Saved) {
        saved = s
        store.save(s)
    }

    /** Adds the fields `/status` shows under `watch`. */
    fun status(into: JSONObject): JSONObject =
        synchronized(lock) {
            into.put("auto_reconnect", saved.owned)
                .put("next_attempt_at", nextAt)
                .put("reconnect_waiting_for_phone", phase == Phase.NEEDS_PHONE || phase == Phase.NEEDS_KEY)
                .put("reconnect_needs_key", phase == Phase.NEEDS_KEY)
                .put("last_drop", saved.lastDrop)
                .put("last_drop_at", saved.lastDropAt)
        }

    companion object {
        /**
         * 5 s rides out a blip (the watch restarting its session, a moment out of range), 15 s and 60 s a
         * short walk away. Then every 5 minutes: an attempt at an absent watch pages it for about 5 s
         * and wakes the phone, so 12 an hour stays cheap, and the watch coming back in range or
         * Bluetooth turning on tries at once anyway. Doze stretches the wait to 9 minutes or more.
         */
        val BACKOFF_MS = longArrayOf(5_000, 15_000, 60_000, 300_000)

        /** Covers the link-up broadcast an attempt causes, delivered once that attempt has failed. */
        const val QUIET_MS = 15_000L

        /**
         * Whether a boot or an app update ([bootOrUpdate]) starts the service: only while the app owns
         * the watch, and on Android 12+ ([sdk] 31) only with the Bluetooth permission, without which
         * the connected-device service is refused its foreground start.
         */
        fun startsAfter(bootOrUpdate: Boolean, owned: Boolean, sdk: Int, bluetoothGranted: Boolean): Boolean =
            bootOrUpdate && owned && (sdk < 31 || bluetoothGranted)

        /**
         * A schedule for [WatchReconnect]: an in-process timer that runs on time while the phone is
         * awake, and [Phone.at] for the same time, which wakes the phone (late, but never lost) when it
         * sleeps. Whichever comes first runs the task on [timers], once; the cancel stops both. A
         * wakeup hands the task over under [awake]'s hold (it returns the release), so the phone stays
         * up through the attempt without holding [Phone.at]'s thread.
         */
        fun wakingSchedule(
            phone: Phone,
            timers: ScheduledExecutorService,
            now: () -> Long,
            awake: () -> () -> Unit = { {} },
        ): (Long, () -> Unit) -> () -> Unit =
            { wallMs, task ->
                val once = AtomicBoolean()
                var cancelTimer: () -> Unit = {}
                val cancelAt = phone.at(wallMs) {
                    cancelTimer()
                    if (once.compareAndSet(false, true)) {
                        val release = awake()
                        try {
                            timers.execute {
                                try {
                                    task()
                                } finally {
                                    release()
                                }
                            }
                        } catch (e: RejectedExecutionException) {
                            release()
                        }
                    }
                }
                val timer = timers.schedule(
                    Runnable {
                        cancelAt()
                        if (once.compareAndSet(false, true)) task()
                    },
                    (wallMs - now()).coerceAtLeast(0),
                    TimeUnit.MILLISECONDS,
                )
                cancelTimer = { timer.cancel(false) }
                val cancel = {
                    once.set(true)
                    timer.cancel(false)
                    cancelAt()
                }
                cancel
            }
    }
}

/**
 * Starts connects for [policy]: [connect] queues one as the numbered attempt and returns its future.
 * An explicit connect joins the attempt under way rather than queue a second behind it.
 */
class Connects(private val policy: WatchReconnect, private val connect: (Int) -> Future<*>) {
    /** The latest connect. */
    @Volatile private var inFlight: Future<*>? = null

    fun explicit(): Future<*> {
        val running = inFlight
        val turn = policy.explicitConnect()
        if (turn.joins && running != null && !running.isDone) return running
        return connect(turn.attempt).also { inFlight = it }
    }

    /** A reconnect attempt, as [policy] asks for it. */
    fun attempt(n: Int): Future<*> = connect(n).also { inFlight = it }
}
