package local.hyprwatch

import org.json.JSONObject
import java.util.Base64

/** WatchFacesFeature over FeatureRig. Routes block on watch replies, so they run on a second thread here. */
object WatchFacesFeatureTest {
    private var assertions = 0

    private fun check(b: Boolean, label: String) {
        assertions++
        if (!b) throw AssertionError(label)
    }

    private const val LIST = "08041000"
    private const val SET_USER = "08041001320b1209363534333635343633" // 4/1 {6: {2: "654365463"}}
    private const val DELETE_USER = "08041002320b1209363534333635343633" // 4/2 {6: {2: "654365463"}}
    private const val SET_200 = "0804100132051203323030" // 4/1 {6: {2: "200"}}

    private fun info(id: String, name: String, active: Boolean, canDelete: Boolean): ByteArray =
        Proto.bytes(1, Proto.join(Proto.text(1, id), Proto.text(2, name), Proto.num(3, if (active) 1 else 0), Proto.num(4, if (canDelete) 1 else 0), Proto.num(5, 0)))

    private fun list(vararg faces: ByteArray): ByteArray = Proto.command(4, 0, Proto.bytes(6, Proto.bytes(1, Proto.join(*faces))))

    private fun three(active: String): ByteArray =
        list(info("100", "Classic", active == "100", false), info("654365463", "redmiwatch5active", active == "654365463", true), info("200", "Sport", active == "200", false))

    private fun ack(sub: Int, status: Long = 0): ByteArray =
        if (status == 0L) Proto.command(4, sub, Proto.bytes(6, Proto.num(4, 1))) else Proto.command(4, sub, Proto.num(100, status))

    private class Call(rig: FeatureRig, method: String, target: String) {
        @Volatile var result: Pair<Int, JSONObject>? = null
        private val t = Thread { result = rig.call(method, target) }.also { it.start() }

        fun join(): Pair<Int, JSONObject> {
            t.join(5_000)
            return result ?: throw AssertionError("The route did not return")
        }
    }

    private fun waitSent(rig: FeatureRig, n: Int) {
        val deadline = System.currentTimeMillis() + 3_000
        while (synchronized(rig.watch.sent) { rig.watch.sent.size } < n) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("Expected $n sends, saw ${rig.sent()}")
            Thread.sleep(2)
        }
    }

    private fun faces(rig: FeatureRig) = rig.status().getJSONObject("watch_faces")

    @JvmStatic
    fun main(args: Array<String>) {
        happyPath()
        writeFailures()
        connectReplies()
        lateReplies()
        disconnects()
        faceFiles()
        installRoute()
        installFailures()
        installResults()
        reservations()
        bodyBytes()
        println("Watch faces feature: $assertions assertions passed")
    }

    private fun happyPath() {
        var now = 1_700_000_000_000L
        val rig = FeatureRig(WatchFacesFeature(replyTimeoutMs = 2_000, clock = { now }))

        val offline = rig.call("POST", "/api/watch-faces/100/activate")
        check(offline.first == 503 && offline.second.getString("error") == "Connect the watch first", "Offline write is a 503")
        val offlineGet = rig.call("GET", "/api/watch-faces")
        check(offlineGet.first == 200 && offlineGet.second.isNull("faces") && !offlineGet.second.getBoolean("connected") && rig.sent().isEmpty(), "Offline GET answers without sending")

        rig.connect()
        check(rig.take() == listOf(LIST), "Connect reads the list")
        val waiting = faces(rig)
        check(waiting.isNull("supported") && waiting.isNull("count") && waiting.getString("list_state") == "waiting", "Unknown until the watch answers")

        rig.receive(three("100"))
        val s = faces(rig)
        check(s.getInt("count") == 3 && s.getString("active_id") == "100" && s.getLong("read_at") == now && s.getBoolean("supported") && s.getString("list_state") == "ok", "List reply lands in status")

        val (code, body) = rig.call("GET", "/api/watch-faces")
        val arr = body.getJSONArray("faces")
        check(code == 200 && arr.length() == 3 && rig.sent().isEmpty(), "GET serves the cache")
        val user = arr.getJSONObject(1)
        check(user.getString("id") == "654365463" && user.getString("name") == "redmiwatch5active" && !user.getBoolean("active") && user.getBoolean("can_delete"), "Face fields")
        check(arr.getJSONObject(0).getBoolean("active") && !arr.getJSONObject(0).getBoolean("can_delete"), "Built-in active face")

        check(rig.call("GET", "/api/watch-faces?refresh=maybe").let { it.first == 400 && it.second.getString("error") == "Use refresh=true or refresh=false" }, "Bad refresh is a 400")
        check(rig.call("POST", "/api/watch-faces/abc/activate").let { it.first == 400 && it.second.getString("error") == "Watch face ids are 1 to 20 decimal digits" }, "Non-numeric id is a 400")
        check(rig.call("POST", "/api/watch-faces/999/activate").let { it.first == 400 && it.second.getString("error") == "Watch face 999 is not installed on the watch" }, "Unknown id is a 400")
        val same = rig.call("POST", "/api/watch-faces/100/activate")
        check(same.first == 200 && same.second.getBoolean("verified") && !same.second.getBoolean("sent") && rig.sent().isEmpty(), "Activating the active face sends nothing")
        check(rig.call("DELETE", "/api/watch-faces/200").let { it.first == 400 && it.second.getString("error") == "Watch face 200 is built in and cannot be deleted" }, "Built-in delete is a 400")
        check(rig.call("DELETE", "/api/watch-faces/999").let { it.first == 400 && it.second.getString("error") == "Watch face 999 is not installed on the watch" }, "Unknown delete is a 400")
        check(rig.call("DELETE", "/api/watch-faces/100").let { it.first == 400 && it.second.getString("error") == "Watch face 100 is built in and cannot be deleted" }, "Built-in check comes before the active check")
        check(rig.sent().isEmpty(), "Refusals send nothing")

        // Activate: 4/1, its ack, then a re-read that shows the new face active.
        now += 1_000
        val activate = Call(rig, "POST", "/api/watch-faces/654365463/activate")
        waitSent(rig, 1)
        check(rig.sent() == listOf(SET_USER), "Activate sends 4/1 with the id")
        rig.receive(ack(1))
        waitSent(rig, 2)
        check(rig.sent()[1] == LIST, "Then re-reads the list")
        rig.receive(three("654365463"))
        val a = activate.join()
        check(a.first == 200 && a.second.getBoolean("verified") && a.second.getBoolean("acknowledged") && a.second.getLong("ack") == 1L, "Activate verified and acknowledged")
        check(a.second.getString("active_id") == "654365463" && a.second.getString("action") == "activate" && faces(rig).getLong("read_at") == now, "Activate reports the re-read state")
        rig.take()

        check(rig.call("DELETE", "/api/watch-faces/654365463").let { it.first == 400 && it.second.getString("error") == "Watch face 654365463 is the active face; activate another face before deleting it" }, "Active delete is a 400")
        check(rig.sent().isEmpty(), "Active refusal sends nothing")

        // The user switches back on the watch, which pushes a list.
        rig.receive(three("100"))
        check(faces(rig).getString("active_id") == "100", "Unsolicited list updates the cache")

        val delete = Call(rig, "DELETE", "/api/watch-faces/654365463")
        waitSent(rig, 1)
        check(rig.sent() == listOf(DELETE_USER), "Delete sends 4/2 with the id")
        rig.receive(ack(2))
        waitSent(rig, 2)
        rig.receive(list(info("100", "Classic", true, false), info("200", "Sport", false, false)))
        val d = delete.join()
        check(d.first == 200 && d.second.getBoolean("verified") && d.second.getJSONArray("faces").length() == 2 && faces(rig).getInt("count") == 2, "Delete verified by the re-read")
        rig.take()

        rig.receive(Proto.command(4, 0, Proto.bytes(6, ByteArray(0))))
        check(faces(rig).getInt("count") == 0 && faces(rig).getBoolean("supported"), "An empty list is a list")
    }

    private fun writeFailures() {
        val rig = FeatureRig(WatchFacesFeature(replyTimeoutMs = 150))
        rig.connect()
        rig.receive(three("100"))
        rig.take()

        // The watch refuses the switch: the list is still re-read, then the route reports the refusal.
        val refused = Call(rig, "POST", "/api/watch-faces/200/activate")
        waitSent(rig, 1)
        check(rig.sent() == listOf(SET_200), "Switch to a built-in face")
        rig.receive(ack(1, status = 3))
        waitSent(rig, 2)
        rig.receive(three("100"))
        val r = refused.join()
        check(r.first == 503 && r.second.getString("error") == "The watch refused to switch to watch face 200 (status 3)", "Non-zero status is reported")
        rig.take()

        // No ack, but the re-read shows the switch happened.
        val silent = Call(rig, "POST", "/api/watch-faces/200/activate")
        waitSent(rig, 2)
        check(rig.sent() == listOf(SET_200, LIST), "A missing ack still re-reads")
        rig.receive(three("200"))
        val s = silent.join()
        check(s.first == 200 && s.second.getBoolean("verified") && !s.second.getBoolean("acknowledged") && s.second.isNull("ack"), "Verified without an ack")
        rig.take()

        // Nothing answers at all.
        val lost = Call(rig, "POST", "/api/watch-faces/100/activate")
        val l = lost.join()
        check(l.first == 503 && l.second.getString("error") == "The watch did not answer the watch face list read", "Missing list reply is a 503")
        check(rig.take() == listOf("0804100132051203313030", LIST), "4/1 for 100, then the read")
        check(rig.status().getJSONObject("watch_faces").getString("active_id") == "200", "Cache keeps the last real list")

        // The late reply to the lost read does not stand in for the next read's answer... it is just a list.
        rig.receive(three("200"))
        check(faces(rig).getString("active_id") == "200", "Late list still updates the cache")

        rig.watch.refuse = true
        val full = rig.call("POST", "/api/watch-faces/100/activate")
        check(full.first == 503 && full.second.getString("error") == "Could not send the watch face switch to the watch", "Full window is a 503")
        rig.watch.refuse = false
        check(rig.watch.connected, "Still connected after every failure")
    }

    private fun connectReplies() {
        val rig = FeatureRig(WatchFacesFeature(replyTimeoutMs = 150))
        rig.connect()
        rig.take()
        rig.fire()
        check(faces(rig).getString("list_state") == "no_reply" && faces(rig).isNull("supported"), "No reply leaves support unknown")

        rig.receive(Proto.command(4, 0, Proto.num(100, 1)))
        check(!faces(rig).getBoolean("supported") && faces(rig).getString("list_state") == "rejected" && faces(rig).isNull("count"), "Non-zero status: unsupported")

        val get = Call(rig, "GET", "/api/watch-faces?refresh=true")
        waitSent(rig, 1)
        rig.receive(Proto.command(4, 0, Proto.num(100, 1)))
        val g = get.join()
        check(g.first == 503 && g.second.getString("error") == "The watch refused the watch face list read", "Rejected refresh is a 503")
        rig.take()

        // Only refresh=true reads; a plain GET answers from the cache at once.
        val cached = rig.call("GET", "/api/watch-faces")
        check(cached.first == 200 && cached.second.isNull("faces") && cached.second.getString("list_state") == "rejected" && rig.sent().isEmpty(), "GET without a cache does not read")
        val auto = Call(rig, "GET", "/api/watch-faces?refresh=true")
        waitSent(rig, 1)
        check(rig.sent() == listOf(LIST), "refresh=true reads")
        rig.receive(three("100"))
        val a = auto.join()
        check(a.first == 200 && a.second.getJSONArray("faces").length() == 3 && a.second.getBoolean("supported") && a.second.getString("list_state") == "ok", "Refresh returns the read list")
        rig.take()

        // A refused connect read goes out on the next ack.
        rig.disconnect()
        rig.watch.refuse = true
        rig.connect()
        check(rig.sent().isEmpty() && rig.logs.last() == "Dropped a watch face list read: Watch has not acknowledged ${SendWindow.MAX_TRACKED} frames", "Refused connect read")
        rig.watch.refuse = false
        rig.ack()
        rig.ack()
        check(rig.take() == listOf(LIST), "Retried once on ack")
    }

    /** Replies carry no request id: a late one answers its own read, never the next route's. */
    private fun lateReplies() {
        val rig = FeatureRig(WatchFacesFeature(replyTimeoutMs = 300))
        rig.connect()
        rig.receive(three("100"))
        rig.take()

        val a = rig.call("GET", "/api/watch-faces?refresh=true")
        check(a.first == 503 && a.second.getString("error") == "The watch did not answer the watch face list read", "Read A times out: $a")
        rig.take()
        val b = Call(rig, "POST", "/api/watch-faces/200/activate")
        waitSent(rig, 1)
        val busy = rig.call("DELETE", "/api/watch-faces/654365463")
        check(busy.first == 409 && busy.second.getString("error") == "Another watch operation is in progress", "A second operation is a 409: $busy")
        val cachedGet = rig.call("GET", "/api/watch-faces")
        check(cachedGet.first == 200 && cachedGet.second.getString("active_id") == "100" && rig.sent() == listOf(SET_200), "A cached GET answers during it")
        rig.receive(ack(1))
        waitSent(rig, 2)
        rig.receive(three("100")) // A's late reply, from before the switch
        Thread.sleep(50)
        rig.receive(three("200")) // B's own
        val r = b.join()
        check(r.first == 200 && r.second.getBoolean("verified") && r.second.getString("active_id") == "200", "B is verified by its own reply, not A's: $r")

        // The connect read was never answered, so the next reply is its: the refresh cannot claim it.
        val owed = FeatureRig(WatchFacesFeature(replyTimeoutMs = 150))
        owed.connect()
        owed.take()
        val g = Call(owed, "GET", "/api/watch-faces?refresh=true")
        waitSent(owed, 1)
        owed.receive(three("100"))
        val gr = g.join()
        check(gr.first == 503 && gr.second.getString("error") == "The watch has not answered an earlier watch face list read; reconnect the watch", "Owed reply: $gr")
        check(faces(owed).getInt("count") == 3, "It still updates the cache")
    }

    private fun disconnects() {
        val rig = FeatureRig(WatchFacesFeature(replyTimeoutMs = 3_000))
        rig.connect()
        rig.receive(three("100"))
        rig.take()

        val call = Call(rig, "POST", "/api/watch-faces/654365463/activate")
        waitSent(rig, 1)
        rig.disconnect()
        val r = call.join()
        check(r.first == 503 && r.second.getString("error") == "The watch disconnected", "Disconnect wakes a waiting route")
        val s = faces(rig)
        check(s.isNull("count") && s.isNull("active_id") && s.isNull("read_at") && s.getString("list_state") == "idle", "Disconnect clears the cache")

        // A reply arriving after reconnect for the old session's write does not confuse the next read.
        rig.connect()
        check(rig.take() == listOf(SET_USER, LIST), "Reconnect reads again")
        rig.receive(ack(1))
        rig.receive(three("654365463"))
        check(faces(rig).getString("active_id") == "654365463" && faces(rig).getBoolean("supported"), "Fresh list after reconnect")
    }

    /** Gadgetbridge's XiaomiFWHelperTest.testRedmiWatch5Active header (168 bytes of a real RW5A face). */
    private const val GB_HEADER =
        "5aa534120000010000000000000000000008000000000100000000000100000000010000000000003635343336353436" +
            "330000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "00000000000000007265646d697761746368356163746976650000000000000000000000000000000000000000000000" +
            "000000000000000000000000000000000000000000000000"

    private fun hexBytes(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    /** A synthetic v2 face: magic, id at 0x28, name at 0x68, preview header at 0x100 via the offset at 0x20. */
    private fun face(id: String = "777", name: ByteArray = "Night".toByteArray(), preview: Pair<Int, Int>? = 180 to 216, size: Int = 600): ByteArray {
        val b = ByteArray(size) { (it * 31 + 7).toByte() }
        for (i in 0 until 0x100) b[i] = 0
        b[0] = 0x5a
        b[1] = 0xa5.toByte()
        b[2] = 0x34
        b[3] = 0x12
        b[6] = 1
        id.toByteArray().copyInto(b, 0x28)
        name.copyInto(b, 0x68)
        if (preview != null) {
            b[0x21] = 1 // offset 0x100
            byteArrayOf(0x28, 0x0c, 0, 0, preview.first.toByte(), (preview.first ushr 8).toByte(), preview.second.toByte(), (preview.second ushr 8).toByte(), 0x10, 0, 0, 0).copyInto(b, 0x100)
        }
        return b
    }

    private fun body(file: ByteArray): JSONObject = JSONObject().put("data", Base64.getEncoder().encodeToString(file))

    private fun parseError(b: ByteArray): String? =
        try {
            WatchFacesFeature.parseFace(b)
            null
        } catch (e: IllegalArgumentException) {
            e.message
        }

    private fun faceFiles() {
        val gb = WatchFacesFeature.parseFace(hexBytes(GB_HEADER))
        check(gb == WatchFacesFeature.FaceFile("654365463", "redmiwatch5active", null, "unknown"), "Gadgetbridge's RW5A header: $gb")
        check(WatchFacesFeature.parseFace(face()) == WatchFacesFeature.FaceFile("777", "Night", 180 to 216, "matches"), "Synthetic face with a 180x216 preview")
        check(WatchFacesFeature.parseFace(face(preview = 320 to 385)).compatibility == "matches", "Screen-sized preview")
        check(WatchFacesFeature.parseFace(face(preview = 160 to 193)).compatibility == "aspect_ratio", "Same shape, smaller: allowed")
        check(parseError(face(preview = 454 to 454)) == "The watch face preview is 454x454: it was made for another watch (this one takes 180x216 or 320x385)", "Round watch face refused")
        check(WatchFacesFeature.parseFace(face(name = byteArrayOf(-1, -1, -1, -1))).name == null, "Localised name: none")
        check(WatchFacesFeature.parseFace(face(name = "Caf\u00e9 \u2603".toByteArray())).name == "Caf\u00e9 \u2603", "UTF-8 name")
        check(parseError(hexBytes("0000060700020001" + "00".repeat(120))) == "This is a v3 watch face, which cannot be installed; only v2 faces (starting 5A A5) can", "v3 refused")
        check(parseError(ByteArray(600)) == "Not a watch face file: it does not start with 5A A5", "No magic")
        check(parseError(face().copyOf(0x6b)) == "Not a watch face file: it does not start with 5A A5", "Too short for a header")
        check(parseError(face(id = "12ab")) == "The watch face file has no numeric id", "Non-numeric id")
        check(parseError(face(id = "")) == "The watch face file has no numeric id", "Empty id")
        check(parseError(face().also { it.fill(0x31, 0x28, 0x68) }) == "The watch face file has no numeric id", "Id without a terminator")
        val noPreview = face(preview = null).also { it[0x21] = 0x10 } // offset 0x1000, past the end
        check(WatchFacesFeature.parseFace(noPreview).preview == null, "A preview offset past the end means no preview")
    }

    /** 4/4 {watchface(6) {watchfaceInstallStart(6) {id "777", size 600, version code 65536}}}. */
    private const val INSTALL_777 = "08041004320e320c0a0337373710d80418808004"

    private fun installReply(status: Long) = Proto.command(4, 4, Proto.bytes(6, Proto.num(5, status)))

    /** 4/4 {watchface(6) {prepareReply(9) {id, prepareStatus [status], expected slice length 4096}}}. */
    private fun prepareReply(status: Long) = Proto.command(4, 4, Proto.bytes(6, Proto.bytes(9, Proto.join(Proto.text(1, "777"), Proto.num(2, status), Proto.num(4, 4096)))))

    /** 4/5 {watchface(6) {installResult(7) {id, code}}}. */
    private fun result(code: Long, id: String = "777") = Proto.command(4, 5, Proto.bytes(6, Proto.bytes(7, Proto.join(Proto.text(1, id), Proto.num(2, code)))))

    private fun uploadAck(chunk: Long = 256) = Proto.command(22, 0, Proto.bytes(24, Proto.bytes(2, Proto.join(Proto.num(2, 0), Proto.num(4, 0), Proto.num(5, chunk)))))

    private fun install(rig: FeatureRig) = rig.status().getJSONObject("watch_faces").get("install")

    private class Post(rig: FeatureRig, body: JSONObject) {
        @Volatile var result: Pair<Int, JSONObject>? = null
        private val t = Thread { result = rig.call("POST", "/api/watch-faces/upload", body) }.also { it.start() }

        fun join(): Pair<Int, JSONObject> {
            t.join(5_000)
            return result ?: throw AssertionError("The route did not return")
        }
    }

    private fun installRig(): FeatureRig {
        val uploads = DataUpload()
        return FeatureRig(uploads, WatchFacesFeature(replyTimeoutMs = 300, uploads = uploads))
    }

    private fun installRoute() {
        val rig = installRig()
        fun post(b: JSONObject) = rig.call("POST", "/api/watch-faces/upload", b)
        check(post(body(face())).let { it.first == 503 && it.second.getString("error") == "Connect the watch first" }, "Offline install is a 503")
        rig.connect()
        rig.receive(three("100"))
        rig.take()

        check(post(JSONObject()).let { it.first == 400 && it.second.getString("error") == "Send the watch face file as base64 in data" }, "Missing data")
        check(post(JSONObject().put("data", "!!!")).let { it.first == 400 && it.second.getString("error") == "data is not valid base64" }, "Bad base64")
        check(post(JSONObject().put("data", "")).let { it.first == 400 && it.second.getString("error") == "The watch face file is empty" }, "Empty file")
        check(post(JSONObject().put("data", "A".repeat(8_000_004))).let { it.first == 400 && it.second.getString("error") == "Watch face files are limited to 6000000 bytes" }, "Too large")
        check(post(body(hexBytes("0000060700020001" + "00".repeat(120)))).let { it.first == 400 && it.second.getString("error").startsWith("This is a v3 watch face") }, "v3 is a 400")
        check(post(body(face(preview = 454 to 454))).first == 400, "Wrong preview is a 400")
        check(post(body(face(id = "654365463"))).let { it.first == 409 && it.second.getString("error") == "Watch face 654365463 is already installed; delete it first" }, "Installed id is a 409")
        check(rig.sent().isEmpty() && rig.data().isEmpty(), "Refusals send nothing")

        // 4/4, its go-ahead, the 22/0 upload request; the route returns while the upload runs.
        val file = face()
        val dataUrl = JSONObject().put("data", "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(file))
        val call = Post(rig, dataUrl)
        waitSent(rig, 1)
        check(rig.sent() == listOf(INSTALL_777), "Install start with id and size: ${rig.sent()}")
        rig.receive(installReply(0))
        val (code, out) = call.join()
        check(code == 200 && out.getJSONObject("install").getString("state") == "uploading" && out.getJSONObject("install").getString("id") == "777", "Uploading: $out")
        val sent = rig.take()
        check(sent.size == 2 && sent[1].startsWith("08161000c201") && sent[1].contains("0810"), "Then a type-16 upload request: $sent")
        check(post(body(face(id = "778"))).let { it.first == 409 && it.second.getString("error") == "A watch face is already being installed" }, "One install at a time")

        rig.receive(uploadAck())
        val parts = rig.takeData()
        check(parts.map { it.length / 2 } == listOf(254, 254, 130) && parts.map { it.substring(0, 8) } == listOf("03000100", "03000200", "03000300"), "626 bytes in parts of 250")
        check(parts.joinToString("") { it.substring(8) }.substring(44, 44 + 1200) == HealthCapture.hex(file), "The whole file follows the 22-byte header")
        rig.ackData(1)
        val progress = install(rig) as JSONObject
        check(progress.getString("state") == "uploading" && progress.getInt("percent") == 33 && progress.getInt("parts_acked") == 1 && progress.getInt("parts_total") == 3, "Progress: $progress")
        check(rig.sent().isEmpty(), "Nothing on channel 1 before the last ack")
        rig.ackData(2)
        check(rig.sent().isEmpty() && (install(rig) as JSONObject).getString("state") == "installing" && rig.watch.delays().contains(120_000L), "Uploaded: waits up to 120 s for the install result")
        rig.receive(result(2))
        check(rig.take() == listOf("08041001320512033737" + "37", LIST), "Installed: switch to the new face, then re-read the list")
        check((install(rig) as JSONObject).getString("state") == "activating", "Activating")
        rig.receive(ack(1))
        rig.receive(list(info("100", "Classic", false, false), info("777", "Night", true, true)))
        val done = install(rig) as JSONObject
        check(done.getString("state") == "done" && done.getBoolean("verified") && done.getBoolean("active") && done.getInt("percent") == 100 && done.isNull("error"), "Verified by the list: $done")
        check(faces(rig).getString("active_id") == "777" && done.getString("compatibility") == "matches" && done.getString("preview") == "180x216" && done.getString("name") == "Night", "Status shows the face")

        // Installed (code 3, in use) but no list after it: done, unverified.
        val again = Post(rig, body(face(id = "779")))
        waitSent(rig, 1)
        rig.receive(installReply(0))
        again.join()
        rig.take()
        rig.receive(uploadAck(4096))
        rig.ack()
        rig.receive(result(3, "779"))
        check(rig.take() == listOf("08041001320512033737" + "39", LIST), "Switch and re-read for 779")
        rig.fire()
        val unverified = install(rig) as JSONObject
        check(unverified.getString("state") == "done" && !unverified.getBoolean("verified"), "Reported installed, no list in time: unverified")
    }

    /** Starts installing face 777 and acks its upload: the install then waits for its result. */
    private fun uploaded(rig: FeatureRig, go: ByteArray = installReply(0)) {
        val c = Post(rig, body(face()))
        waitSent(rig, 1)
        rig.receive(go)
        check(c.join().let { it.first == 200 && it.second.getJSONObject("install").getString("state") == "uploading" }, "Install started")
        rig.take()
        rig.receive(uploadAck(4096))
        rig.ack()
        check((install(rig) as JSONObject).getString("state") == "installing", "Installing")
    }

    /** The watch's install result (4/5) and the list read after it decide how an install ends; it is never done without either. */
    private fun installResults() {
        val rig = installRig()
        rig.connect()
        rig.receive(three("100"))
        rig.take()
        fun ended() = install(rig) as JSONObject

        uploaded(rig, prepareReply(0))
        rig.receive(result(0))
        check(ended().getString("state") == "failed" && ended().getString("error") == "Watch face 777 was not installed: it failed the watch's verification (install result 0)" && rig.sent().isEmpty(), "Verify failed: ${ended()}")
        uploaded(rig)
        rig.receive(result(1))
        check(ended().getString("state") == "failed" && ended().getString("error") == "Watch face 777 was not installed: the watch could not install it (install result 1)" && rig.sent().isEmpty(), "Install failed: ${ended()}")

        // Another face's result answers nothing; without one in 120 s the list decides.
        uploaded(rig)
        rig.receive(result(2, "778"))
        check(rig.sent().isEmpty() && ended().getString("state") == "installing", "Another face's result is ignored")
        rig.fire()
        check(rig.take() == listOf("08041001320512033737" + "37", LIST) && ended().getString("state") == "activating", "No result in time: switch and re-read anyway")
        rig.receive(three("100"))
        check(ended().getString("state") == "failed" && ended().getString("error") == "Watch face 777 is not on the watch after the install" && !ended().getBoolean("verified"), "Not in the list: failed, not done: ${ended()}")

        uploaded(rig)
        rig.fire()
        rig.take()
        rig.receive(list(info("100", "Classic", false, false), info("777", "Night", true, true)))
        check(ended().getString("state") == "done" && ended().getBoolean("verified") && ended().getBoolean("active"), "No result but in the list: done, verified")
        rig.receive(three("100"))

        // Neither a result nor a list.
        uploaded(rig)
        rig.fire()
        rig.take()
        rig.fire()
        check(ended().getString("state") == "failed" && ended().getString("error") == "The watch did not confirm that watch face 777 is installed", "No result, no list: failed: ${ended()}")

        // Only the reply to the install's own read can fail it.
        uploaded(rig)
        rig.watch.refuse = true
        rig.fire()
        rig.watch.refuse = false
        check(rig.take().isEmpty() && ended().getString("state") == "activating", "Switch and read refused by the window")
        rig.receive(three("100"))
        check(ended().getString("state") == "activating", "A list while the install's read could not be sent leaves it running")
        rig.fire()
        check(ended().getString("state") == "failed", "then the wait ends it")

        // A result that comes before the last part's link ack is kept for it.
        val c = Post(rig, body(face()))
        waitSent(rig, 1)
        rig.receive(installReply(0))
        c.join()
        rig.take()
        rig.receive(uploadAck(4096))
        rig.receive(result(2))
        check(rig.sent().isEmpty() && ended().getString("state") == "uploading", "Result before the ack: still uploading")
        rig.ack()
        check(rig.take() == listOf("08041001320512033737" + "37", LIST) && ended().getString("state") == "activating", "The ack then activates at once")

        // A disconnect while activating or installing never ends as done.
        rig.disconnect()
        val unconfirmed = "The watch disconnected before the install was confirmed; check the face list"
        check(ended().getString("state") == "failed" && ended().getString("error") == unconfirmed, "Disconnect while activating: ${ended()}")
        rig.fire()
        check(ended().getString("state") == "failed", "The old wait changes nothing")
        rig.connect()
        rig.take()
        uploaded(rig)
        rig.disconnect()
        check(ended().getString("state") == "failed" && ended().getString("error") == unconfirmed, "Disconnect while installing: ${ended()}")
        rig.connect()
        rig.receive(result(2))
        check(rig.take() == listOf(LIST) && ended().getString("state") == "failed", "A result in the next session changes nothing")
    }

    private fun installFailures() {
        val rig = installRig()
        rig.connect()
        rig.take()

        fun refused(status: Long, code: Int, error: String) {
            val c = Post(rig, body(face()))
            waitSent(rig, 1)
            rig.receive(installReply(status))
            val r = c.join()
            check(r.first == code && r.second.getString("error") == error && rig.take() == listOf(INSTALL_777), "Install status $status: $r")
        }
        refused(2, 409, "Watch face 777 is already installed; delete it first")
        refused(1, 503, "The watch refused to install watch face 777: the watch is busy (prepare status 1)")
        refused(3, 503, "The watch refused to install watch face 777: the watch is low on storage; delete a face first (prepare status 3)")

        // The status is required, in prepareStatus (5) or prepareReply (9); a missing one is no go-ahead.
        fun answered(reply: ByteArray, code: Int, error: String) {
            val c = Post(rig, body(face()))
            waitSent(rig, 1)
            rig.receive(reply)
            val r = c.join()
            check(r.first == code && r.second.getString("error") == error && rig.take() == listOf(INSTALL_777), "4/4 reply ${HealthCapture.hex(reply)}: $r")
        }
        answered(Proto.command(4, 4, Proto.bytes(6, Proto.num(4, 1))), 503, "The watch answered the watch face install start without a status")
        answered(Proto.command(4, 4), 503, "The watch answered the watch face install start without a status")
        answered(Proto.command(4, 4, Proto.bytes(6, Proto.bytes(9, Proto.text(1, "777")))), 503, "The watch answered the watch face install start without a status")
        answered(prepareReply(7), 503, "The watch refused to install watch face 777: the watch holds as many faces as it can; delete one first (prepare status 7)")
        answered(prepareReply(2), 409, "Watch face 777 is already installed; delete it first")
        check(rig.logs.contains("Watch face install start reply: status 0, ack none, prepare status 2 in field 9") && rig.logs.contains("Watch face install start reply: status 0, ack 1, no prepare status"), "The log names the field: ${rig.logs}")
        val silent = rig.call("POST", "/api/watch-faces/upload", body(face()))
        check(silent.first == 503 && silent.second.getString("error") == "The watch did not answer the watch face install start" && rig.take() == listOf(INSTALL_777), "No answer: $silent")
        check(install(rig) == JSONObject.NULL, "No install began")

        // The late answer to that start is owed and answers nothing; the next start waits for its own.
        val next = Post(rig, body(face()))
        waitSent(rig, 1)
        rig.receive(installReply(0))
        Thread.sleep(50)
        rig.receive(installReply(1))
        check(next.join().let { it.first == 503 && it.second.getString("error") == "The watch refused to install watch face 777: the watch is busy (prepare status 1)" }, "Late reply answers the earlier start")
        rig.take()

        // The watch refuses the upload itself.
        var c = Post(rig, body(face()))
        waitSent(rig, 1)
        rig.receive(installReply(0))
        c.join()
        rig.take()
        rig.receive(Proto.command(22, 0, Proto.bytes(24, Proto.bytes(2, Proto.num(2, 7)))))
        val failed = install(rig) as JSONObject
        check(failed.getString("state") == "failed" && failed.getString("error") == "The watch refused the upload (error 7)" && failed.isNull("percent") && rig.sent().isEmpty(), "Refused upload: $failed")

        // A disconnect mid-upload fails the install.
        c = Post(rig, body(face()))
        waitSent(rig, 1)
        rig.receive(installReply(0))
        c.join()
        rig.take()
        rig.receive(uploadAck())
        rig.ackData(1)
        rig.disconnect()
        val dropped = install(rig) as JSONObject
        check(dropped.getString("state") == "failed" && dropped.getString("error") == "The watch disconnected", "Disconnect: $dropped")
        rig.connect()
        rig.ack()
        check(rig.take() == listOf(LIST) && (install(rig) as JSONObject).getString("state") == "failed", "Nothing resumes; only the connect read goes out")

        // The uploader is busy with something else (an icon): 409 after the watch's go-ahead.
        val uploads = DataUpload()
        val busy = FeatureRig(uploads, WatchFacesFeature(replyTimeoutMs = 300, uploads = uploads))
        busy.connect()
        busy.take()
        check(uploads.start(DataUpload.TYPE_NOTIFICATION_ICON, ByteArray(4)) {}, "Icon upload running")
        busy.take()
        check(busy.call("POST", "/api/watch-faces/upload", body(face())).let { it.first == 409 && it.second.getString("error") == "Another upload to the watch is in progress" } && busy.sent().isEmpty(), "Busy uploader is a 409 before 4/4")
    }

    /** The uploader is the face's from before 4/4 until its upload starts or the install gives up. */
    private fun reservations() {
        val uploads = DataUpload()
        val rig = FeatureRig(uploads, WatchFacesFeature(replyTimeoutMs = 300, uploads = uploads))
        rig.connect()
        rig.receive(three("200"))
        rig.take()

        val c = Post(rig, body(face()))
        waitSent(rig, 1)
        check(uploads.busy && !uploads.start(DataUpload.TYPE_NOTIFICATION_ICON, ByteArray(4)) {}, "While the watch prepares for the face, an icon cannot take the uploader")
        rig.receive(installReply(0))
        val started = c.join()
        val sent = rig.take()
        check(started.first == 200 && sent.size == 2 && sent[1].startsWith("08161000c201") && sent[1].contains("0810"), "The face's own upload starts after the go-ahead: $started $sent")

        // Switching or deleting faces waits for the install.
        val busy = "A watch face is being installed; try again when it finishes"
        check(rig.call("POST", "/api/watch-faces/100/activate").let { it.first == 409 && it.second.getString("error") == busy } && rig.sent().isEmpty(), "Activate is a 409 while installing")
        check(rig.call("DELETE", "/api/watch-faces/654365463").let { it.first == 409 && it.second.getString("error") == busy } && rig.sent().isEmpty(), "Delete is a 409 while installing")
        rig.receive(Proto.command(22, 0, Proto.bytes(24, Proto.bytes(2, Proto.num(2, 7)))))
        check(!uploads.busy && (install(rig) as JSONObject).getString("state") == "failed", "The refused upload frees the uploader")
        check(rig.call("POST", "/api/watch-faces/200/activate").first == 200, "and faces can be switched again")

        // A refused or unanswered 4/4 gives the reservation back.
        val refused = Post(rig, body(face()))
        waitSent(rig, 1)
        rig.receive(installReply(1))
        check(refused.join().first == 503 && !uploads.busy, "Refused install start: uploader free")
        rig.take()
        check(rig.call("POST", "/api/watch-faces/upload", body(face())).first == 503 && !uploads.busy, "Unanswered install start: uploader free")
        rig.take()

        // A disconnect during the wait voids it and fails the route.
        val dropped = Post(rig, body(face()))
        waitSent(rig, 1)
        rig.disconnect()
        check(dropped.join().let { it.first == 503 && it.second.getString("error") == "The watch disconnected" } && !uploads.busy, "Disconnect during 4/4: uploader free")
    }

    /** The upload body is read from its bytes; anything but a plain `{"data": "..."}` is parsed as JSON. */
    private fun bodyBytes() {
        fun decoded(body: String): String = HealthCapture.hex(WatchFacesFeature.decodeFile(body.toByteArray()))
        fun error(body: String): String? =
            try {
                WatchFacesFeature.decodeFile(body.toByteArray())
                null
            } catch (e: IllegalArgumentException) {
                e.message
            }
        check(decoded("{\"data\":\"AQI/\"}") == "01023f", "Plain body")
        check(decoded(" {\n  \"data\" :\t\"AQI/\"\r\n} \n") == "01023f", "Whitespace anywhere JSON allows it")
        check(decoded("{\"data\":\"data:application/octet-stream;base64,AQI/\"}") == "01023f", "Data URL")
        check(decoded("{\"data\":\"AQI\\/\"}") == "01023f", "An escaped slash goes through the JSON parser")
        check(decoded("{\"name\":\"x\",\"data\":\"AQI/\"}") == "01023f" && decoded("{\"data\":\"AQI/\",\"x\":1}") == "01023f", "So do other members")
        check(error("{\"data\":\"AQI/\"") == "Invalid JSON" && error("data=AQI/") == "Invalid JSON", "Broken JSON is a 400")
        check(error("{\"data\":\"AQ\u00e9/\"}") == "data is not valid base64", "Non-ASCII in data")
        check(error("{\"data\":\"data:;base64\"}") == "The watch face file is empty", "Data URL without a payload")
        check(error("{\"data\":7}") == "Send the watch face file as base64 in data" && error("") == "Send the watch face file as base64 in data", "Not a string, or no body")
    }
}
