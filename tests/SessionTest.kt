package local.hyprwatch

import org.json.JSONObject
import java.io.IOException
import java.security.GeneralSecurityException
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/** The notification listener's settings, unused by these tests. */
private object NoListener : NotificationsFeature.ListenerSettings {
    override fun get(): JSONObject = JSONObject()

    override fun put(input: JSONObject): JSONObject = JSONObject()

    override fun openAccess(): Boolean = false
}

/** Drives the production WatchSession wiring with a real SppLink and commands executor. */
object SessionTest {
    private var assertions = 0

    private fun check(b: Boolean, label: String) {
        assertions++
        if (!b) throw AssertionError(label)
    }

    private fun bytes(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private fun hex(b: ByteArray): String = HealthCapture.hex(b)

    private class Rig(vararg extra: WatchFeature) {
        val written: MutableList<String> = Collections.synchronizedList(ArrayList())
        val logs: MutableList<String> = Collections.synchronizedList(ArrayList())
        val timer = ManualTimer()
        val store = MemHealthStore()
        val files = store.files
        @Volatile var connected = true
        @Volatile var refuse = false

        /** Runs after each frame is written, under the link lock: a test's hook for interleavings. */
        @Volatile var onWrite: ((String) -> Unit)? = null
        val link = SppLink(
            { if (refuse) throw IOException("Watch is not accepting data") else hex(it).let { f -> written.add(f); onWrite?.invoke(f) } },
            timer, { logs.add(it) }, {},
        ).apply { authenticated() }
        val commands = daemonExecutor()
        val health = HealthFeature(store, { _, _ -> null }, { JSONObject() }) { HealthCaptureTest.DAY }
        val notifications = NotificationsFeature(NoListener, WatchNotifications(NotificationIds()) { ZoneOffset.UTC })
        val session = WatchSession(
            link, timer, { logs.add(it) }, { connected }, commands,
            Features(listOf(health, notifications) + extra, MemSettings(), FakePhone()) { logs.add(it) },
        ).apply { install() }
        private val reader = Frames.Reader()
        private var seq = 0

        /** Commands written on channel 1, without framing; acks left out. */
        fun sent(): List<String> = synchronized(written) { written.filter { it.startsWith("a5a503") }.map { it.substring(20) } }

        fun receive(channel: Int, payload: ByteArray) {
            val f = Frames.encode(3, seq++, byteArrayOf(channel.toByte(), 1) + payload)
            reader.add(f, f.size)
            while (true) {
                link.receive(reader.next(true) ?: break)
            }
        }
    }

    /** Daemon threads, so a failed assertion ends the test instead of leaving it waiting. */
    private fun daemonExecutor(): ExecutorService = Executors.newSingleThreadExecutor { Thread(it).apply { isDaemon = true } }

    private fun profile(height: Int): JSONObject = HealthCapture.profile(HealthCaptureTest.profileInput().put("height_cm", height), HealthCaptureTest.DAY)

    private fun info(p: JSONObject): String = hex(HealthCapture.userInfo(p, HealthCaptureTest.DAY))

    private fun thread(f: () -> Unit): Thread = Thread(f).also {
        it.isDaemon = true
        it.start()
    }

    /** Until [t] blocks on a monitor or ends. */
    private fun settle(t: Thread) {
        val until = System.currentTimeMillis() + 2000
        while (t.state != Thread.State.BLOCKED && t.isAlive && System.currentTimeMillis() < until) {
            Thread.sleep(5)
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        featureRequests()
        reminderDuringFile()
        profiles()
        notifications()
        sessionRestart()
        rejectedKey()
        staleConfirmation()
        sendsDuringRestart()
        staleDeadline()
        uploadsOverLink()
        resetAfterLastPart()
        callbackDuringInstall()
        println("Watch session threads, profile ordering, notification state across reconnects: $assertions assertions passed")
    }

    /**
     * Wired as WatchConnection is: the session's connected check reads auth, built after the session.
     * A media listener that fires as it is registered (AndroidPhone fires once on registering) must
     * find auth there, so features install only after both exist.
     */
    private fun callbackDuringInstall() {
        val logs: MutableList<String> = Collections.synchronizedList(ArrayList())
        val timer = ManualTimer()
        val link = SppLink({}, timer, { logs.add(it) }, {})
        val phone = object : Phone by FakePhone() {
            override fun onMediaChanged(listener: () -> Unit): () -> Unit {
                listener()
                return {}
            }
        }
        lateinit var auth: WatchAuth
        val session = WatchSession(
            link, timer, { logs.add(it) }, { auth.isAuthenticated }, daemonExecutor(),
            Features(listOf(MusicFeature()), MemSettings(), phone) { logs.add(it) },
        )
        auth = WatchAuth(link, session, timer, {}, {}) {}
        session.install()
        check(!session.connected && logs.isEmpty(), "a feature callback during install sees a disconnected session: $logs")
    }

    /** WatchSession as a WatchPort: a submitted task requests on the commands thread; one queued before a disconnect never runs. */
    private fun featureRequests() {
        val replies: MutableList<Long> = Collections.synchronizedList(ArrayList())
        val probe = object : WatchFeature {
            override val name = "probe"
            lateinit var host: FeatureHost

            override fun install(host: FeatureHost) {
                this.host = host
                host.on(99, 1) { host.request(Proto.command(99, 2)) }
            }

            override fun onConnected() = host.submit { replies.add(Proto.number(host.request(Proto.command(99, 2)), 3, -1)) }
        }
        val r = Rig(probe)
        r.session.onConnected()
        val ask = hex(Proto.command(99, 2))
        val until = System.currentTimeMillis() + 5000
        while (!r.sent().contains(ask) && System.currentTimeMillis() < until) {
            Thread.sleep(5)
        }
        check(r.sent() == listOf(HealthCaptureTest.TODAY, ask), "Connect hooks run in order; the probe's request goes out from the commands thread")
        r.receive(1, Proto.command(99, 2, Proto.num(3, 7)))
        r.session.command { }
        check(replies == listOf(7L), "The reply reaches the submitted task")

        r.receive(1, Proto.command(99, 1))
        check(r.logs.last() == "probe command 99/1 failed: request() would block the watch link; use submit()", "The reader thread never waits for a reply")

        val busy = CountDownLatch(1)
        val release = CountDownLatch(1)
        val caller = daemonExecutor()
        val held = caller.submit(Callable { r.session.command { busy.countDown(); release.await() } })
        busy.await(2, TimeUnit.SECONDS)
        probe.host.submit { replies.add(0) }
        r.session.onDisconnected()
        release.countDown()
        held.get(2, TimeUnit.SECONDS)
        r.session.command { }
        caller.shutdown()
        check(replies == listOf(7L) && r.logs.contains("Dropped a probe task: the watch session ended"), "A task queued before a disconnect is dropped, though the link still reads connected")
        r.commands.shutdown()
    }

    /** DataUpload on the real WatchSession and SppLink: chunks leave on channel 2 in the clear, numbered after the request and paced by link acks. */
    private fun uploadsOverLink() {
        val up = DataUpload()
        val r = Rig(up)
        r.link.configure(bytes(LinkTest.WATCH_CONFIG))
        r.link.encrypt = { it.reversedArray() }
        val results: MutableList<String?> = Collections.synchronizedList(ArrayList())
        val ten = ByteArray(10) { (it + 1).toByte() }
        check(up.start(DataUpload.TYPE_NOTIFICATION_ICON, ten) { results.add(it) }, "Upload started")
        val request = r.written.toList()
        check(request.size == 1 && request[0].substring(16, 20) == "0102" && request[0].substring(20) == hex(bytes("08161000c201180a160832121070903e79b7575e3f4e7ffa15c2608ac7180a").reversedArray()), "22/0 at seq 0, encrypted on channel 1")
        r.written.clear()
        // The watch's ack: chunk size 18, so 12-byte parts. Frame 0 (the request) is unacked, so the window of 3 takes two chunks.
        r.receive(1, Proto.command(22, 0, Proto.bytes(24, Proto.bytes(2, Proto.join(Proto.num(2, 0), Proto.num(4, 0), Proto.num(5, 18))))))
        val chunks = { synchronized(r.written) { r.written.filter { it.startsWith("a5a503") } } }
        check(chunks() == listOf("a5a503011200b93d020103000100003270903e79b7575e3f4e7f", "a5a5030212001fd2020103000200fa15c2608ac70a0000000102"), "Two plaintext chunks at seq 1 and 2: ${chunks()}")
        check(up.status().getInt("parts_sent") == 2 && results.isEmpty(), "Third part waits for room")
        r.written.clear()
        val ack = { seq: Int ->
            val f = Frames.encode(1, seq, ByteArray(0))
            val reader = Frames.Reader()
            reader.add(f, f.size)
            r.link.receive(reader.next(true)!!)
        }
        ack(1)
        check(chunks() == listOf("a5a503031200c768020103000300030405060708090a48c1e370") && results.isEmpty(), "The ack frees room: the last part goes out at seq 3")
        ack(3)
        check(results == listOf<String?>(null) && up.status().getString("state") == "done" && r.link.uploadsInFlight(r.link.epoch) == 0, "Done when the watch acked it")
        r.commands.shutdown()
    }

    /**
     * The link resets (the writer saw the socket close) right after the last part is written and
     * before the uploader reads what is in flight: the reset emptied the count without an ack, so
     * the upload fails rather than reading that as done.
     */
    private fun resetAfterLastPart() {
        val up = DataUpload()
        val r = Rig(up)
        r.link.configure(bytes(LinkTest.WATCH_CONFIG))
        val results: MutableList<String?> = Collections.synchronizedList(ArrayList())
        check(up.start(DataUpload.TYPE_NOTIFICATION_ICON, ByteArray(10) { (it + 1).toByte() }) { results.add(it) }, "Upload started")
        val ack = Frames.encode(1, 0, ByteArray(0))
        val reader = Frames.Reader()
        reader.add(ack, ack.size)
        r.link.receive(reader.next(true)!!)
        // Part 3 of 3: [2][1] then [3 u16][3 u16].
        r.onWrite = { f -> if (f.startsWith("a5a503") && f.substring(16, 28) == "020103000300") r.link.reset(IOException("Watch disconnected")) }
        r.receive(1, Proto.command(22, 0, Proto.bytes(24, Proto.bytes(2, Proto.join(Proto.num(2, 0), Proto.num(4, 0), Proto.num(5, 18))))))
        check(results == listOf<String?>("The watch link restarted during the upload") && up.status().getString("state") == "failed", "Not done: ${up.status()}")
        check(
            r.logs.contains("Link reset during an upload (Watch disconnected): 3 of 3 upload frames unacked, 2 frames received meanwhile, last: data 0 channel 1 opcode 1 command 22/0"),
            "The reset logs where the upload stood, counting the handshake's frames: ${r.logs}",
        )
        r.commands.shutdown()
    }

    /** A reminder request on the commands thread is pending while a file streams in on the reader thread. */
    private fun reminderDuringFile() {
        val a = "01020304000301"
        val r = Rig()
        // The watch's tx window of 3: the paced 8/3 fits beside the unacked 8/1 and 8/2 requests.
        r.link.configure(bytes(LinkTest.WATCH_CONFIG))
        r.health.capture.start()
        r.receive(1, Proto.command(8, 1, Proto.bytes(10, Proto.bytes(2, bytes(a)))))
        check(r.sent().contains("080810035209120701020304000301"), "8/3 sent from the reader thread's list handler")
        val fa = HealthCaptureTest.file(a)
        r.receive(5, HealthCaptureTest.chunk(2, 1, fa.copyOfRange(0, 10)))
        val caller = daemonExecutor()
        val reply = caller.submit(Callable { r.session.command { r.link.request(Proto.command(17, 14), 17, 14, 5000) } })
        val until = System.currentTimeMillis() + 5000
        while (!r.sent().contains("0811100e") && System.currentTimeMillis() < until) {
            Thread.sleep(5)
        }
        r.receive(5, HealthCaptureTest.chunk(2, 2, fa.copyOfRange(10, fa.size)))
        check(r.files.latest(a) == hex(fa) && !reply.isDone, "File completes while the reminder request holds the commands thread")
        r.receive(1, Proto.join(Proto.num(1, 17), Proto.num(2, 14), Proto.bytes(19, Proto.bytes(10, Proto.num(2, 50)))))
        val list = reply.get(5, TimeUnit.SECONDS)
        caller.shutdown()
        check(Proto.number(Proto.message(Proto.message(list, 19), 10), 2, 0) == 50L, "Reminder reply completes the request")

        r.connected = false
        var refused: String? = null
        try {
            r.session.command { "sent" }
        } catch (e: Exception) {
            refused = e.cause?.message
        }
        r.commands.shutdown()
        check(refused == "Connect the watch first", "Commands need a connected watch")
    }

    private fun profiles() {
        val r = Rig()
        r.session.onConnected()
        check(r.sent() == listOf(HealthCaptureTest.TODAY), "No profile entered: connect sends no 8/0, only the listing")
        val r2 = Rig()
        r2.store.saved = HealthCapture.profile(HealthCaptureTest.profileInput(), HealthCaptureTest.DAY)
        r2.session.onConnected()
        check(r2.sent() == listOf(HealthCaptureTest.USER_INFO, HealthCaptureTest.TODAY), "Entered profile is sent before the listing")

        // Two saves: the second waits for the first, so the watch gets the profile stored last.
        val s = Rig()
        val db = AtomicReference<JSONObject?>()
        val inStore = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pa = profile(170)
        val pb = profile(180)
        s.store.write = {
            db.set(it)
            if (it === pa) {
                inStore.countDown()
                release.await()
            }
        }
        val ta = thread { s.health.saveProfile(pa) }
        inStore.await(2, TimeUnit.SECONDS)
        val tb = thread { s.health.saveProfile(pb) }
        settle(tb)
        check(db.get() === pa, "Second save waits while the first is storing")
        release.countDown()
        ta.join(2000)
        tb.join(2000)
        check(db.get() === pb && s.sent() == listOf(info(pa), info(pb)), "Profiles reach the watch in the order they were stored")

        // A save during the connect-time send waits, so the connect cannot send an older profile last.
        val c = Rig()
        db.set(pa)
        val read = CountDownLatch(1)
        val go = CountDownLatch(1)
        c.store.read = {
            db.get().also {
                read.countDown()
                go.await()
            }
        }
        c.store.write = { db.set(it) }
        val tc = thread { c.session.onConnected() }
        read.await(2, TimeUnit.SECONDS)
        val ts = thread { c.health.saveProfile(pb) }
        settle(ts)
        go.countDown()
        tc.join(2000)
        ts.join(2000)
        check(c.sent().filter { it.startsWith("08081000") } == listOf(info(pa), info(pb)), "Connect sends what it read; the newer save follows it")

        // A send that fails after storing reports false instead of throwing.
        val f = Rig()
        f.refuse = true
        f.store.write = { db.set(it) }
        check(!f.health.saveProfile(pb) && db.get() === pb, "Stored, not sent")
        check(f.logs.last() == "Dropped a profile: Watch is not accepting data", "Failed send is logged")
        f.connected = false
        check(!f.health.saveProfile(pa) && db.get() === pa, "Disconnected: stored for the next connect")
    }

    private fun notifications() {
        val utc = ZoneOffset.UTC
        val call = PhoneNotification("call", "com.dialer", "Phone", "Bob", "123", 0, isCall = true, postTime = 100)
        val callHex = hex(WatchNotifications.incomingCall("Bob", "123", 0, utc))
        val note = PhoneNotification("k", "com.a", "A", "Hi", "Yo", 0)
        val r = Rig()
        r.connected = false
        r.notifications.forward(NotificationEvent.Posted(call))
        r.notifications.forward(NotificationEvent.Posted(note.copy(key = "j")))
        check(r.sent().isEmpty() && r.logs.last() == "Dropped a notification: watch is not connected", "Nothing is sent while disconnected")
        r.connected = true
        r.notifications.forward(NotificationEvent.Posted(call.copy(postTime = 200)))
        check(r.sent() == listOf(callHex), "A call that rang while disconnected is sent once connected")
        r.notifications.forward(NotificationEvent.Posted(note))
        check(r.sent().last() == hex(WatchNotifications.send(note, 1, utc)!!), "Disconnected posts allocate no ids")

        // Android reports a removal once; one made while disconnected is sent after the reconnect.
        r.connected = false
        r.session.onDisconnected()
        r.notifications.forward(NotificationEvent.Removed("k"))
        r.connected = true
        var reposts = 0
        NotificationBridge.repost = { reposts++ }
        r.session.onConnected()
        NotificationBridge.repost = null
        val delete = hex(WatchNotifications.delete(1, "com.a", "k")!!)
        check(r.sent().takeLast(2) == listOf(HealthCaptureTest.TODAY, delete) && reposts == 1, "Removal while disconnected deletes id 1 after reconnect")
        // The link drop ended the watch's call screen; the listener's repost shows it again.
        r.notifications.forward(NotificationEvent.Posted(call.copy(postTime = 300)))
        check(r.sent().last() == callHex, "Ringing call sent again after a reconnect")

        // A full send window refuses the call; nothing is remembered as sent, so its next post gets through.
        val w = Rig()
        repeat(SendWindow.MAX_TRACKED) { w.link.send(byteArrayOf(0)) }
        w.written.clear()
        w.notifications.forward(NotificationEvent.Posted(call))
        check(w.sent().isEmpty() && w.logs.last() == "Dropped a notification: Watch has not acknowledged 128 frames", "Call refused by the full window")
        w.link.receive(Frames.Packet().apply { type = 1; seq = SendWindow.MAX_TRACKED - 1 })
        w.notifications.forward(NotificationEvent.Posted(call.copy(postTime = 200)))
        check(w.sent() == listOf(callHex), "Call sent once the window has room")
        repeat(SendWindow.MAX_TRACKED - 1) { w.link.send(byteArrayOf(0)) }
        w.written.clear()
        w.notifications.forward(NotificationEvent.Removed("call"))
        check(w.sent().isEmpty(), "End-call refused by the full window")
        w.link.receive(Frames.Packet().apply { type = 1; seq = 255 })
        check(w.sent() == listOf(hex(WatchNotifications.endCall())), "Owed end-call goes out as soon as an ack makes room")
        w.notifications.forward(NotificationEvent.Posted(note))
        check(w.sent().last() == hex(WatchNotifications.send(note, 1, utc)!!) && w.sent().size == 2, "Next notification follows once")

        // Listener attach and watch connect in either order: the ringing call reaches the watch once.
        for (listenerFirst in listOf(true, false)) {
            val o = Rig()
            val scan = { o.notifications.forward(NotificationEvent.Posted(call)) }
            o.connected = false
            if (listenerFirst) {
                NotificationBridge.repost = scan
                scan()
            }
            o.connected = true
            o.session.onConnected()
            if (!listenerFirst) {
                NotificationBridge.repost = scan
                scan()
            }
            NotificationBridge.repost = null
            check(o.sent() == listOf(HealthCaptureTest.TODAY, callHex), "Ringing call sent once, listener first: $listenerFirst")
        }

        // Exceptions other than IOException are refusals too: nothing escapes into the capture or the API.
        val x = Rig()
        x.link.encrypt = { throw GeneralSecurityException("Bad key") }
        val stored = AtomicReference<JSONObject?>()
        x.store.write = { stored.set(it) }
        check(!x.health.saveProfile(profile(170)) && stored.get() != null, "Stored, send failure reported as false")
        check(x.logs.last() == "Dropped a profile: Bad key", "Crypto failure is logged")
        x.health.capture.start()
        check(x.logs.last() == "Dropped a health command: Bad key", "Capture start survives a crypto failure")
    }

    /** WatchConnection's wiring with a real WatchAuth: the watch answers authentication with [key]. */
    private class AuthRig {
        val written: MutableList<String> = Collections.synchronizedList(ArrayList())
        val logs: MutableList<String> = Collections.synchronizedList(ArrayList())
        val drops: MutableList<String> = Collections.synchronizedList(ArrayList())
        val timer = ManualTimer()
        val link = SppLink({ written.add(hex(it)) }, timer, { logs.add(it) }, { drops.add(it) })
        val commands = daemonExecutor()
        val store = MemHealthStore()
        val health = HealthFeature(store, { _, _ -> null }, { JSONObject() })
        val notifications = NotificationsFeature(NoListener, WatchNotifications(NotificationIds()) { ZoneOffset.UTC })
        @Volatile var connects = 0

        /** Runs once, right after the next connected check, as a send would. */
        val afterCheck = AtomicReference<(() -> Unit)?>()
        lateinit var auth: WatchAuth
        val session = WatchSession(
            link, timer, { logs.add(it) }, { auth.isAuthenticated.also { afterCheck.getAndSet(null)?.invoke() } }, commands,
            Features(listOf(health, notifications), MemSettings(), FakePhone()) { logs.add(it) },
        )
        val key = ByteArray(16) { it.toByte() }
        val crypto = WatchCrypto(key)
        private val reader = Frames.Reader()
        private var seq = 0

        init {
            auth = WatchAuth(link, session, timer, { drops.add(it) }, {}) { connects++ }
            session.install()
        }

        fun sent(): List<String> = synchronized(written) { written.filter { it.startsWith("a5a503") }.map { it.substring(20) } }

        fun receive(channel: Int, payload: ByteArray) {
            val f = Frames.encode(3, seq++, byteArrayOf(channel.toByte(), 1) + payload)
            reader.add(f, f.size)
            while (true) {
                link.receive(reader.next(true) ?: break)
            }
        }

        /** The watch's side of 1/26 after [WatchAuth.begin]. */
        fun challenge() {
            val phone = Proto.blob(Proto.message(Proto.message(Proto.decode(bytes(sent().last())), 3), 30), 1)
            val watch = ByteArray(16) { (100 + it).toByte() }
            val salt = WatchCrypto.hmac(phone + watch, key)
            val dec = WatchCrypto.hmac(salt, "miwear-auth".toByteArray(Charsets.US_ASCII) + byteArrayOf(1)).copyOf(16)
            val proof = Proto.join(Proto.bytes(1, watch), Proto.bytes(2, WatchCrypto.hmac(dec, watch + phone)))
            receive(1, Proto.command(1, 26, Proto.bytes(3, Proto.bytes(31, proof))))
        }

        /** 1/26 and 1/27. */
        fun authenticate() {
            challenge()
            receive(1, Proto.command(1, 27))
        }
    }

    /** A watch that rejects the key fails as a key problem, which reconnecting never retries on its own. */
    private fun rejectedKey() {
        val r = AuthRig()
        r.auth.begin(r.crypto)
        val e = try {
            r.receive(1, Proto.command(1, 26, Proto.num(100, 1)))
            null
        } catch (e: PhoneSetupException) {
            e
        }
        check(e != null && e.needsKey && e.message == "Watch rejected authentication", "Rejected key: $e")
        check(!r.auth.isAuthenticated && r.connects == 0, "Not connected")
    }

    /** A session config repeated mid-connection is a disconnect, and its new 1/27 a connect. */
    private fun sessionRestart() {
        val r = AuthRig()
        val p = HealthCapture.profile(HealthCaptureTest.profileInput(), LocalDate.now())
        r.store.saved = p
        val info = hex(HealthCapture.userInfo(p, LocalDate.now()))
        var reposts = 0
        NotificationBridge.repost = { reposts++ }
        r.auth.begin(r.crypto)
        r.authenticate()
        check(r.auth.isAuthenticated && r.connects == 1 && reposts == 1, "First 1/27 connects the session")
        check(r.sent().takeLast(2) == listOf(info, HealthCaptureTest.TODAY), "Connect sends the profile, then lists today's health files")

        val note = PhoneNotification("k", "com.a", "A", "Hi", "Yo", 0)
        r.notifications.forward(NotificationEvent.Posted(note))
        val caller = daemonExecutor()
        val reply = caller.submit(Callable { r.session.command { r.link.request(Proto.command(17, 14), 17, 14, 5000) } })
        val until = System.currentTimeMillis() + 5000
        while (!r.sent().contains("0811100e") && System.currentTimeMillis() < until) {
            Thread.sleep(5)
        }
        r.written.clear()

        r.auth.begin(r.crypto)
        var cause: Throwable? = try {
            reply.get(2, TimeUnit.SECONDS)
            null
        } catch (e: ExecutionException) {
            e
        } catch (e: TimeoutException) {
            e
        }
        while (cause?.cause != null) cause = cause.cause
        caller.shutdown()
        check(!r.auth.isAuthenticated && cause?.message == "Watch restarted the session", "Restart: unauthenticated, the pending reminder read fails at once")
        check(r.health.capture.status().getString("last_error") == "Watch disconnected during health sync", "Restart stops the health sync")
        check(r.written.size == 1 && r.written[0].substring(6, 8) == "00" && WatchAuth.TIMEOUT_MS in r.timer.delays(), "Restart: link numbering from 0, new auth deadline")
        r.notifications.forward(NotificationEvent.Removed("k"))
        r.notifications.forward(NotificationEvent.Posted(note.copy(key = "j")))
        check(r.sent().size == 1 && r.logs.last() == "Dropped a notification: watch is not connected", "Nothing but authentication goes out meanwhile")

        r.authenticate()
        NotificationBridge.repost = null
        val delete = hex(WatchNotifications.delete(1, "com.a", "k")!!)
        check(r.auth.isAuthenticated && r.connects == 2 && reposts == 2, "The new 1/27 connects the session again and reposts the ringing call")
        check(r.sent().drop(2) == listOf(info, HealthCaptureTest.TODAY, delete), "Reconnect: profile, health sync, then the owed removal")
        check(WatchAuth.TIMEOUT_MS !in r.timer.delays(), "Deadline cancelled by the 1/27")

        val d = AuthRig()
        d.auth.begin(d.crypto)
        d.authenticate()
        d.auth.begin(d.crypto)
        d.timer.fire()
        check(d.drops == listOf("Watch did not finish authentication"), "No 1/27 after a restart drops the link")
        r.commands.shutdown()
        d.commands.shutdown()
    }

    /** A disconnect that lands after the reader took 1/27 but before it ran the connect hooks wins. */
    private fun staleConfirmation() {
        val r = AuthRig()
        r.auth.begin(r.crypto)
        r.challenge()
        val confirm: Thread
        synchronized(r.auth) {
            confirm = thread { r.receive(1, Proto.command(1, 27)) }
            settle(confirm)
            r.auth.disconnected(IOException("Watch disconnected"))
        }
        confirm.join(2000)
        var refused: String? = null
        try {
            r.link.send(HealthCapture.fetchPast())
        } catch (e: IOException) {
            refused = e.message
        }
        check(!r.auth.isAuthenticated && r.connects == 0 && r.sent().none { it.startsWith("0808") }, "The late 1/27 connects nothing")
        check(refused == "Watch is not connected", "Encrypted sends stay refused")
        r.auth.begin(r.crypto)
        r.authenticate()
        check(r.auth.isAuthenticated && r.connects == 1, "The next session authenticates")
        r.commands.shutdown()
    }

    /** A reminder command and a notification that passed their connected check before a restart send nothing. */
    private fun sendsDuringRestart() {
        val r = AuthRig()
        r.auth.begin(r.crypto)
        r.authenticate()
        val inTask = CountDownLatch(1)
        val go = CountDownLatch(1)
        val caller = daemonExecutor()
        val reply = caller.submit(Callable { r.session.command { inTask.countDown(); go.await(); r.link.request(Proto.command(17, 14), 17, 14, 5000) } })
        inTask.await(2, TimeUnit.SECONDS)
        r.written.clear()
        r.auth.begin(r.crypto)
        go.countDown()
        var cause: Throwable? = try {
            reply.get(2, TimeUnit.SECONDS)
            null
        } catch (e: ExecutionException) {
            e
        } catch (e: TimeoutException) {
            e
        }
        while (cause?.cause != null) cause = cause.cause
        caller.shutdown()
        check(cause?.message == "Watch is not connected" && r.sent().size == 1 && r.drops.isEmpty(), "Command refused at once, nothing sent with the old keys")

        r.authenticate()
        r.written.clear()
        val checked = CountDownLatch(1)
        val resume = CountDownLatch(1)
        r.afterCheck.set {
            checked.countDown()
            resume.await()
        }
        val post = thread { r.notifications.forward(NotificationEvent.Posted(PhoneNotification("k", "com.a", "A", "Hi", "Yo", 0))) }
        checked.await(2, TimeUnit.SECONDS)
        val restart = thread { r.auth.begin(r.crypto) }
        // The restart has closed the link and now waits for the notification to finish.
        settle(restart)
        resume.countDown()
        post.join(2000)
        restart.join(2000)
        check(r.sent().size == 1 && r.logs.contains("Dropped a notification: Watch is not connected"), "Notification refused, only authentication sent")
        r.commands.shutdown()
    }

    /** A deadline cancelled while it was already running must not drop the session that replaced it. */
    private fun staleDeadline() {
        val d = AuthRig()
        d.auth.begin(d.crypto)
        val expiry: Thread
        synchronized(d.auth) {
            expiry = thread { d.timer.fire() }
            settle(expiry)
            d.auth.begin(d.crypto)
        }
        expiry.join(2000)
        check(d.drops.isEmpty() && d.timer.delays() == listOf(WatchAuth.TIMEOUT_MS, SppLink.RESEND_TIMEOUT_MS), "Old deadline ignored, new one armed")
        d.timer.fire()
        check(d.drops == listOf("Watch did not finish authentication"), "The new deadline still drops a silent watch")
        d.commands.shutdown()
    }
}
