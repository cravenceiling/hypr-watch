package local.hyprwatch

import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/** The reconnect policy on a fake clock: ownership, backoff, phone-side waits and idempotent attempts. */
object ReconnectTest {
    private var assertions = 0

    private fun check(b: Boolean, label: String) {
        assertions++
        if (!b) throw AssertionError(label)
    }

    private class MemoryStore(var saved: WatchReconnect.Saved = WatchReconnect.Saved(false, "", 0)) : WatchReconnect.Store {
        var saves = 0

        override fun load() = saved

        override fun save(saved: WatchReconnect.Saved) {
            this.saved = saved
            saves++
        }
    }

    /**
     * A policy whose timers wait for [fire]. An attempt only counts, as one still under way, unless
     * [onAttempt] reports its outcome from inside it, as the real connect does before it returns.
     */
    private class Rig(val store: MemoryStore = MemoryStore()) {
        var now = 1_000_000L
        val timers = ArrayList<Pair<Long, () -> Unit>>()
        var attempts = 0
        var lastAttempt = 0
        var attemptThrows = false
        var onAttempt: (() -> Unit)? = null
        val logs = ArrayList<String>()
        val policy = WatchReconnect(
            store,
            { now },
            { at, task ->
                val t = at to task
                timers.add(t)
                ({ timers.remove(t) })
            },
            { n ->
                attempts++
                lastAttempt = n
                if (attemptThrows) throw IllegalStateException("shut down")
                onAttempt?.invoke()
            },
            { logs.add(it) },
        )

        /** Delay of the one pending timer from now; -1 when none. */
        fun pending(): Long = if (timers.isEmpty()) -1 else timers.single().first - now

        /** Moves the clock to the earliest pending timer and runs it. */
        fun fire() {
            val t = timers.minBy { it.first }
            now = t.first
            timers.remove(t)
            t.second()
        }

        /** An explicit connect that authenticates: the app owns the watch from here. */
        fun connect() {
            check(!policy.explicitConnect().joins, "Explicit connect runs its own attempt")
            check(policy.connected(), "The link is wanted")
        }

        fun status(): JSONObject = policy.status(JSONObject())
    }

    @JvmStatic
    fun main(args: Array<String>) {
        notOwned()
        backoff()
        attemptEcho()
        keyRejected()
        explicitConnect()
        restore()
        releasedWhileConnecting()
        idempotent()
        phoneSide()
        release()
        restart()
        failingAttempt()
        wakingSchedule()
        returnInQuietWindow()
        staleFailure()
        join()
        serviceStart()
        linkGuards()
        println("ReconnectTest: $assertions assertions passed")
    }

    /** Without an explicit connect the app never connects on its own, but a drop is still recorded. */
    private fun notOwned() {
        val r = Rig()
        r.policy.start()
        r.policy.phoneReady("bluetooth on")
        r.policy.watchInRange()
        check(r.attempts == 0 && r.timers.isEmpty(), "Not owned: no attempt")
        r.policy.failed("bt socket closed, read return: -1", wasUp = true, phoneSide = false)
        check(r.timers.isEmpty() && r.attempts == 0, "Not owned: a drop schedules nothing")
        val s = r.status()
        check(!s.getBoolean("auto_reconnect") && s.getLong("next_attempt_at") == 0L, "Not owned in status: $s")
        check(s.getString("last_drop") == "bt socket closed, read return: -1" && s.getLong("last_drop_at") == r.now, "Drop recorded: $s")
    }

    private fun backoff() {
        val r = Rig()
        r.connect()
        check(r.store.saved.owned && r.status().getBoolean("auto_reconnect"), "A connect that authenticates claims the watch and persists it")
        r.policy.failed("bt socket closed, read return: -1", wasUp = true, phoneSide = false)
        check(r.pending() == 5_000L && r.status().getLong("next_attempt_at") == r.now + 5_000, "First retry after 5 s: ${r.status()}")
        check(r.logs.count { it.startsWith("Watch link dropped") } == 1, "One log line per drop")
        val delays = ArrayList<Long>()
        repeat(5) {
            r.fire()
            check(r.timers.isEmpty() && r.status().getLong("next_attempt_at") == 0L, "No timer while an attempt runs")
            r.policy.failed("Timed out at opening socket", wasUp = false, phoneSide = false)
            delays.add(r.pending())
        }
        check(delays == listOf(15_000L, 60_000L, 300_000L, 300_000L, 300_000L), "Backoff 15 s, 60 s, then every 5 min: $delays")
        check(r.attempts == 5 && r.logs.count { it.startsWith("Watch reconnect attempt ") && it.contains("(timer)") } == 5, "One log line per attempt")
        val numbers = r.logs.filter { it.matches(Regex("Watch reconnect attempt \\d.*")) }.map { it.removePrefix("Watch reconnect attempt ") }
        check(numbers == listOf("1 (timer)", "2 (timer)", "3 (timer)", "4 (timer)", "5 (timer)"), "Attempts after a drop count from 1: $numbers")
        r.fire()
        r.policy.connected()
        r.policy.failed("Watch disconnected", wasUp = true, phoneSide = false)
        check(r.pending() == 5_000L, "A link that came up starts the backoff over")
        r.fire()
        r.policy.failed("Timed out at opening socket", wasUp = false, phoneSide = false)
        check(r.pending() == 15_000L, "Second failure: 15 s")

        // The watch in range, well after the last attempt: try at once, but keep the backoff.
        r.now += WatchReconnect.QUIET_MS
        r.policy.watchInRange()
        check(r.attempts == 8 && r.timers.isEmpty(), "In range: attempt now, timer cancelled")
        r.policy.failed("Timed out at waiting for session", wasUp = false, phoneSide = false)
        check(r.pending() == 60_000L, "The watch in range does not start the backoff over")
        // Bluetooth turning on does: the phone changed.
        r.policy.phoneReady("bluetooth on")
        check(r.attempts == 9, "Bluetooth on: attempt now")
        r.policy.failed("Timed out at opening socket", wasUp = false, phoneSide = false)
        check(r.pending() == 5_000L, "After Bluetooth on the backoff starts over")
    }

    /**
     * An attempt brings Android's link to the watch up itself; its broadcast is handled only once the
     * attempt has failed. That echo must neither retry at once nor start the backoff over, or a watch
     * in range that refuses the connect is tried every few seconds forever.
     */
    private fun attemptEcho() {
        val r = Rig()
        r.connect()
        r.policy.failed("Watch disconnected", wasUp = true, phoneSide = false)
        r.onAttempt = { r.policy.failed("Timed out at waiting for session", wasUp = false, phoneSide = false) }
        val delays = ArrayList<Long>()
        repeat(5) {
            r.fire()
            r.now += 3_000
            r.policy.watchInRange()
            delays.add(r.timers.maxOf { it.first } - r.now)
            // Android drops the link the failed attempt opened, so the look at the window's end does nothing.
            r.now += 2_000
            r.policy.watchOutOfRange()
            if (r.timers.size == 2) r.fire()
        }
        check(r.attempts == 5, "Only the timer attempts: ${r.attempts}")
        check(delays == listOf(12_000L, 57_000L, 297_000L, 297_000L, 297_000L), "The echo keeps the backoff: $delays")
    }

    /** A key the watch rejects: no timer and no trigger retries it, only an explicit connect. */
    private fun keyRejected() {
        val r = Rig()
        r.connect()
        r.policy.failed("Watch disconnected", wasUp = true, phoneSide = false)
        r.onAttempt = { r.policy.failed("Watch rejected authentication", wasUp = false, phoneSide = true, needsKey = true) }
        r.fire()
        check(r.attempts == 1 && r.timers.isEmpty() && r.status().getBoolean("reconnect_waiting_for_phone"), "Rejected key: nothing scheduled")
        check(r.status().getBoolean("reconnect_needs_key"), "Status names the key: ${r.status()}")
        // The connect waiting for the handshake reports it again; the cause keeps it a key problem.
        r.policy.failed(LinkGuards.errorText(LinkGuards.cause(ExecutionException(PhoneSetupException("Watch rejected authentication", needsKey = true))), "authenticating"), wasUp = false, phoneSide = true, needsKey = true)
        check(r.policy.phaseNow == WatchReconnect.Phase.NEEDS_KEY && r.timers.isEmpty(), "A second report changes nothing")
        r.now += 60_000
        r.policy.watchInRange()
        r.policy.phoneReady("bluetooth on")
        r.policy.phoneReady("watch paired")
        check(r.attempts == 1 && r.timers.isEmpty(), "Rejected key: triggers do nothing")
        check(r.store.saved.owned, "Ownership stays")
        r.onAttempt = null
        check(!r.policy.explicitConnect().joins && r.policy.connected(), "An explicit connect tries the new key")
        check(r.policy.phaseNow == WatchReconnect.Phase.UP, "Up with the new key")
        check(!r.status().getBoolean("reconnect_needs_key"), "Key accepted")
    }

    /** An explicit connect claims the watch only once it authenticates. */
    private fun explicitConnect() {
        val r = Rig()
        check(!r.policy.explicitConnect().joins && r.policy.wanted(), "Not owned yet, but the explicit connect is wanted")
        check(!r.store.saved.owned && r.store.saves == 0, "Nothing claimed before it authenticates")
        r.policy.failed("Timed out at opening socket", wasUp = false, phoneSide = false)
        check(!r.store.saved.owned && !r.policy.wanted() && r.timers.isEmpty(), "A failed connect claims nothing and schedules nothing")
        r.now += 60_000
        r.policy.start()
        r.policy.watchInRange()
        check(r.attempts == 0, "Nothing reconnects after it")

        // After a release, a failed connect leaves the watch released.
        r.connect()
        r.policy.release()
        r.policy.explicitConnect()
        r.policy.failed("Timed out at opening socket", wasUp = false, phoneSide = false)
        check(!r.store.saved.owned && r.store.saved.released && !r.policy.restoreMayConnect, "Still released")

        // Owned before: a failed connect keeps it, and the backoff runs.
        r.connect()
        check(!r.store.saved.released, "A connect clears the release")
        r.policy.failed("Watch disconnected", wasUp = true, phoneSide = false)
        r.policy.explicitConnect()
        check(r.timers.isEmpty(), "An explicit connect cancels the pending timer")
        r.policy.failed("Timed out at opening socket", wasUp = false, phoneSide = false)
        check(r.store.saved.owned && r.pending() == 5_000L, "Owned before: still owned, retry in 5 s")

        // An attempt under way: the explicit connect joins it rather than start a second.
        r.fire()
        check(r.policy.explicitConnect().joins, "Joins the attempt under way")
        r.policy.connected()
        check(r.policy.phaseNow == WatchReconnect.Phase.UP && r.attempts == 1, "One attempt")
    }

    /** A restore's restart connects a fresh install or an owned watch, never a released one. */
    private fun restore() {
        check(Rig().policy.restoreMayConnect, "Fresh install: a restore connects")
        val owned = Rig()
        owned.connect()
        check(owned.policy.restoreMayConnect, "Owned: a restore connects")
        owned.policy.release()
        check(!owned.policy.restoreMayConnect && owned.store.saved.released, "Released: a restore leaves it alone")
        check(!Rig(owned.store).policy.restoreMayConnect, "The release outlives a restart")
    }

    /** A release while an explicit connect authenticates: the link is not claimed and must close. */
    private fun releasedWhileConnecting() {
        val r = Rig()
        r.policy.explicitConnect()
        r.policy.release()
        check(!r.policy.wanted(), "Released: the connect is no longer wanted")
        check(!r.policy.connected() && !r.store.saved.owned, "Authenticated after a release: not claimed, the caller closes it")
        r.policy.watchInRange()
        check(r.attempts == 0, "Nothing reconnects")
    }

    /** A trigger while an attempt runs or the link is up does nothing; a failure reported twice schedules once. */
    private fun idempotent() {
        val r = Rig()
        r.connect()
        r.policy.failed("Timed out at opening socket", wasUp = false, phoneSide = false)
        check(r.policy.phaseNow == WatchReconnect.Phase.UP && r.timers.isEmpty(), "A timeout reported after the login went through leaves the link up")
        r.policy.failed("Watch disconnected", wasUp = true, phoneSide = false)
        r.fire()
        r.policy.phoneReady("bluetooth on")
        r.policy.watchInRange()
        r.policy.start()
        check(r.attempts == 1, "An attempt under way: triggers do nothing")
        r.policy.connected()
        r.policy.phoneReady("bluetooth on")
        r.policy.watchInRange()
        r.policy.start()
        check(r.attempts == 1, "Connected: triggers do nothing")
        r.policy.failed("Watch disconnected", wasUp = true, phoneSide = false)
        r.policy.failed("Watch operation failed", wasUp = false, phoneSide = false)
        check(r.timers.size == 1 && r.pending() == 5_000L && r.status().getString("last_drop") == "Watch disconnected", "Second report of one drop changes nothing")
        // An explicit connect while the link is up keeps it up, so triggers still do nothing.
        r.fire()
        r.policy.connected()
        check(!r.policy.explicitConnect().joins, "Up: the connect returns at once")
        r.policy.phoneReady("bluetooth on")
        check(r.attempts == 2 && r.policy.phaseNow == WatchReconnect.Phase.UP, "Connect on an up link: no extra attempt")
    }

    /** Bluetooth off or no paired watch: no retry loop, wait for the phone. */
    private fun phoneSide() {
        val r = Rig()
        r.connect()
        r.policy.failed("bt socket closed, read return: -1", wasUp = true, phoneSide = false)
        r.fire()
        r.policy.failed("Turn on phone Bluetooth", wasUp = false, phoneSide = true)
        check(r.timers.isEmpty() && r.status().getBoolean("reconnect_waiting_for_phone"), "Bluetooth off: no timer, waiting for the phone")
        r.policy.failed("Turn on phone Bluetooth", wasUp = false, phoneSide = true)
        check(r.timers.isEmpty() && r.attempts == 1, "Still no spin")
        r.policy.phoneReady("bluetooth on")
        check(r.attempts == 2 && !r.status().getBoolean("reconnect_waiting_for_phone"), "Bluetooth on: attempt")
        r.policy.failed("Pair the Redmi Watch 5 Active in Android Bluetooth settings first", wasUp = false, phoneSide = true)
        // That failure never reached Bluetooth, so a link right after it is the watch, not an echo.
        r.policy.watchInRange()
        check(r.attempts == 3, "Paired watch linked: attempt")
        r.policy.failed("Pair the Redmi Watch 5 Active in Android Bluetooth settings first", wasUp = false, phoneSide = true)
        r.policy.phoneReady("watch paired")
        check(r.attempts == 4, "Watch paired: attempt")
    }

    /** An explicit disconnect stops everything: Mi Fitness may hold the watch next. */
    private fun release() {
        val r = Rig()
        r.connect()
        r.policy.failed("Watch disconnected", wasUp = true, phoneSide = false)
        val stale = r.timers.single()
        r.policy.release()
        check(!r.store.saved.owned && r.timers.isEmpty() && r.status().getLong("next_attempt_at") == 0L, "Release clears ownership and the timer")
        stale.second()
        r.policy.failed("Watch disconnected", wasUp = false, phoneSide = false)
        r.now += 60_000
        r.policy.watchInRange()
        r.policy.phoneReady("bluetooth on")
        r.policy.start()
        check(r.attempts == 0 && r.timers.isEmpty(), "Released: a stale timer, a drop and triggers do nothing")
        // A timer cancelled by a newer one does nothing either.
        r.connect()
        r.policy.failed("Watch disconnected", wasUp = true, phoneSide = false)
        val old = r.timers.single()
        r.policy.phoneReady("bluetooth on")
        old.second()
        check(r.attempts == 1, "A cancelled timer that still fires does nothing")
    }

    /** Ownership and the last drop outlive the process; the next service start reconnects. */
    private fun restart() {
        val store = MemoryStore()
        val first = Rig(store)
        first.connect()
        first.policy.failed("bt socket closed, read return: -1", wasUp = true, phoneSide = false)
        val at = first.now
        val second = Rig(store)
        check(second.status().getString("last_drop") == "bt socket closed, read return: -1" && second.status().getLong("last_drop_at") == at, "Last drop survives a restart")
        second.policy.start()
        check(second.attempts == 1 && second.logs.any { it.contains("(service start)") }, "Owned: service start connects")
        val saves = store.saves
        second.policy.connected()
        check(store.saves == saves, "Connecting again writes nothing")
    }

    private fun failingAttempt() {
        val r = Rig()
        r.connect()
        r.policy.failed("Watch disconnected", wasUp = true, phoneSide = false)
        r.attemptThrows = true
        r.fire()
        check(r.pending() == 15_000L, "An attempt that throws counts as a failure")
        r.policy.shutdown()
        check(r.timers.isEmpty() && !r.policy.wanted(), "Shutdown cancels the timer")
        r.policy.failed("Watch disconnected", wasUp = false, phoneSide = false)
        r.policy.start()
        check(r.timers.isEmpty() && r.attempts == 1 && r.store.saved.owned, "After shutdown nothing is scheduled or tried; ownership stays")
    }

    /**
     * The in-process timer and the wakeup alarm run the task once between them, and cancel stops both.
     * A wakeup hands the task to the timer thread under its own hold, released when the task ends.
     */
    private fun wakingSchedule() {
        val phone = FakePhone()
        val timers = Executors.newSingleThreadScheduledExecutor()
        try {
            var now = 0L
            val held = AtomicInteger()
            val released = AtomicInteger()
            val schedule = WatchReconnect.wakingSchedule(phone, timers, { now }) {
                held.incrementAndGet()
                ({ released.incrementAndGet() })
            }
            val runs = AtomicInteger()
            // Asleep: the in-process timer is far off, the wakeup runs it, off the wakeup's thread.
            val gate = CountDownLatch(1)
            schedule(3_600_000L) {
                gate.await(5, TimeUnit.SECONDS)
                runs.incrementAndGet()
            }
            phone.wake(3_600_000L)
            check(phone.wakeups.isEmpty() && runs.get() == 0 && held.get() == 1 && released.get() == 0, "The wakeup returns at once, holding the phone awake")
            gate.countDown()
            timers.submit {}.get(5, TimeUnit.SECONDS)
            check(runs.get() == 1 && released.get() == 1, "The task ran once and let go of the hold")
            now = 3_600_000L
            // Awake: the in-process timer runs it on time and cancels the wakeup.
            val done = CountDownLatch(1)
            schedule(now) {
                runs.incrementAndGet()
                done.countDown()
            }
            check(done.await(5, TimeUnit.SECONDS), "In-process timer ran")
            timers.submit {}.get(5, TimeUnit.SECONDS)
            check(phone.wakeups.isEmpty() && runs.get() == 2 && held.get() == 1, "Its wakeup was cancelled")
            val cancel = schedule(now + 60_000) { runs.incrementAndGet() }
            cancel()
            phone.wake(now + 120_000)
            check(runs.get() == 2 && phone.wakeups.isEmpty(), "Cancel stops both")
        } finally {
            timers.shutdownNow()
        }
    }

    /**
     * The watch returning while the last attempt's echo would still be ignored: one look at the
     * quiet window's end tries then if Android's link to it is still up, keeping the backoff.
     */
    private fun returnInQuietWindow() {
        val r = Rig()
        r.connect()
        r.policy.failed("Watch disconnected", wasUp = true, phoneSide = false)
        r.onAttempt = { r.policy.failed("Timed out at opening socket", wasUp = false, phoneSide = false) }
        r.fire()
        r.fire()
        check(r.pending() == 60_000L, "At the 60 s step")
        val ended = r.now
        r.onAttempt = null
        r.now += 3_000
        r.policy.watchInRange()
        check(r.attempts == 2 && r.timers.size == 2, "In the quiet window: no attempt yet, a look at its end")
        r.policy.watchInRange()
        check(r.timers.size == 2, "One look per window")
        r.fire()
        check(r.now == ended + WatchReconnect.QUIET_MS && r.attempts == 3 && r.timers.isEmpty(), "Still linked at the window's end: attempt")
        check(r.logs.last() == "Watch reconnect attempt 3 (watch still in range)", "Logged: ${r.logs.last()}")
        r.policy.failed("Timed out at waiting for session", wasUp = false, phoneSide = false)
        check(r.pending() == 300_000L, "It keeps the backoff")

        // The backoff timer due before the window ends: no look.
        val early = Rig()
        early.connect()
        early.policy.failed("Watch disconnected", wasUp = true, phoneSide = false)
        early.fire()
        early.policy.failed("Timed out at opening socket", wasUp = false, phoneSide = false)
        early.now += 3_000
        early.policy.watchInRange()
        check(early.timers.size == 1 && early.attempts == 1, "15 s step: the timer is the look")
    }

    /** A release during attempt A, then an explicit connect B: A's failure must not cancel B. */
    private fun staleFailure() {
        val r = Rig()
        val a = r.policy.explicitConnect()
        r.policy.release()
        val b = r.policy.explicitConnect()
        check(!b.joins && b.attempt != a.attempt, "B is a new attempt: $a $b")
        r.policy.failed("Timed out at waiting for session", wasUp = false, phoneSide = false, attempt = a.attempt)
        check(r.policy.wanted() && r.policy.phaseNow == WatchReconnect.Phase.CONNECTING, "A's end leaves B wanted")
        r.policy.failed("Timed out at opening socket", wasUp = false, phoneSide = false, attempt = b.attempt)
        check(!r.policy.wanted() && r.policy.phaseNow == WatchReconnect.Phase.IDLE, "B's own failure counts")

        // A timer attempt that throws reports as its own attempt.
        val t = Rig()
        t.connect()
        t.policy.failed("Watch disconnected", wasUp = true, phoneSide = false)
        t.attemptThrows = true
        t.fire()
        check(t.pending() == 15_000L, "Counted as the attempt's failure")
    }

    /** An explicit connect joins the attempt under way instead of queueing a second connect behind it. */
    private fun join() {
        val r = Rig()
        val started = ArrayList<Int>()
        val futures = ArrayList<CompletableFuture<Void?>>()
        val connects = Connects(r.policy) { n ->
            started.add(n)
            CompletableFuture<Void?>().also { futures.add(it) }
        }
        r.onAttempt = { connects.attempt(r.lastAttempt) }
        connects.explicit()
        r.policy.connected()
        futures[0].complete(null)
        r.policy.failed("Watch disconnected", wasUp = true, phoneSide = false)
        r.fire()
        check(started.size == 2 && !futures[1].isDone, "A timer attempt is under way")
        check(connects.explicit() === futures[1] && started.size == 2, "Joined, no second connect: $started")
        futures[1].complete(null)
        val again = connects.explicit()
        check(again !== futures[1] && started == listOf(1, 2, 2), "Once it ended, the connect runs again as that attempt: $started")
    }

    /** The service is always sticky; a start decides: a restart after a kill stays only while owned, a restore connects unless released. */
    private fun serviceStart() {
        val r = Rig()
        val connect = WatchReconnect.StartAction.CONNECT
        val stop = WatchReconnect.StartAction.STOP
        val none = WatchReconnect.StartAction.NONE
        check(r.policy.serviceStart(connect = true, restart = false) == connect, "A restore connects a fresh install")
        check(r.policy.serviceStart(connect = false, restart = false) == none, "The Activity's start adds nothing")
        check(r.policy.serviceStart(connect = false, restart = true) == stop, "Restarted after a kill, not owned: stop")
        r.connect()
        check(r.policy.serviceStart(connect = false, restart = true) == none, "Owned: the restart stays, and start() reconnects")
        check(r.policy.serviceStart(connect = true, restart = false) == connect, "Owned: a restore connects")
        r.policy.release()
        check(r.policy.serviceStart(connect = true, restart = false) == none, "Released: a restore leaves the watch alone")
        check(r.policy.serviceStart(connect = false, restart = true) == stop, "Released: a restart stops")

        check(WatchReconnect.startsAfter(bootOrUpdate = true, owned = true, sdk = 34, bluetoothGranted = true), "Boot while owned: start")
        check(!WatchReconnect.startsAfter(bootOrUpdate = true, owned = true, sdk = 34, bluetoothGranted = false), "Android 12+ without the Bluetooth permission: no start")
        check(WatchReconnect.startsAfter(bootOrUpdate = true, owned = true, sdk = 30, bluetoothGranted = false), "Before Android 12 there is no such permission")
        check(!WatchReconnect.startsAfter(bootOrUpdate = true, owned = false, sdk = 34, bluetoothGranted = true), "Not owned: no start")
        check(!WatchReconnect.startsAfter(bootOrUpdate = false, owned = true, sdk = 34, bluetoothGranted = true), "Another broadcast: no start")
    }

    /** WatchConnection's socket and error decisions. */
    private fun linkGuards() {
        val rejected = PhoneSetupException("Watch rejected authentication", needsKey = true)
        check(LinkGuards.cause(ExecutionException(rejected)) === rejected, "The handshake's failure is unwrapped")
        check(LinkGuards.errorText(LinkGuards.cause(ExecutionException(rejected)), "authenticating") == "Watch rejected authentication", "The second report keeps the reason")
        check(LinkGuards.errorText(TimeoutException(), "waiting for session") == "Timed out at waiting for session", "Timeout names the stage")
        check(LinkGuards.errorText(LinkGuards.cause(ExecutionException(null)), "x") == "Watch operation failed", "No cause: generic")

        // The socket is stored before the wanted check, so a disconnect on either side of it closes the socket.
        val order = ArrayList<String>()
        check(!LinkGuards.adopt("socket", { order.add("store $it") }, { order.add("wanted"); false }), "Not wanted: the connect stops")
        check(order == listOf("store socket", "wanted"), "Stored, then checked: $order")
        check(LinkGuards.adopt("socket", {}, { true }), "Wanted: it goes on")

        val seen = Any()
        check(LinkGuards.backstopCloses(seen, seen, wanted = false), "Same socket, still unwanted: close")
        check(!LinkGuards.backstopCloses(seen, Any(), wanted = false), "A newer socket stays open")
        check(!LinkGuards.backstopCloses(seen, null, wanted = false), "Already closed: nothing")
        check(!LinkGuards.backstopCloses(seen, seen, wanted = true), "Wanted again: stays open")
    }
}
