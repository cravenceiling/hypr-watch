package local.hyprwatch

import org.json.JSONObject

/** MusicFeature through FeatureRig; expected bytes are hand-encoded from wear_media.proto. */
object MusicFeatureTest {
    private var assertions = 0

    private fun check(b: Boolean, label: String) {
        assertions++
        if (!b) throw AssertionError(label)
    }

    // 18/1 { music(20) { musicInfo(1) { state 1, volume 40, track "Song", artist "Band", position 61 s, duration 200 s } } }
    private const val SONG_PLAYING = "08121001a201170a15080110282204536f6e672a0442616e64303d38c801"
    private const val SONG_PAUSED = "08121001a201170a15080210282204536f6e672a0442616e64303d38c801"
    private const val NEXT_PLAYING = "08121001a201170a150801102822044e6578742a0442616e64300038c801"

    // SONG_PLAYING at position 10 s
    private const val SONG_AT_10 = "08121001a201170a15080110282204536f6e672a0442616e64300a38c801"

    // state 0 only, then state 0 with the music volume 40
    private const val NOTHING = "08121001a201040a020800"
    private const val NOTHING_VOL = "08121001a201060a0408001028"

    private val song = NowPlaying("Song", "Band", "Album", playing = true, durationMs = 200_500, positionMs = 61_900)

    private fun key(k: Int, volume: Int? = null): ByteArray {
        val mk = if (volume == null) Proto.num(1, k.toLong()) else Proto.num(1, k.toLong()) + Proto.num(2, volume.toLong())
        return Proto.command(18, 2, Proto.bytes(20, Proto.bytes(2, mk)))
    }

    @JvmStatic
    fun main(args: Array<String>) {
        connectAndAsk()
        debounceAndDedupe()
        mediaKeys()
        routesAndSettings()
        disconnectMidDebounce()
        accessAndNothingPlaying()
        refusedAndTruncated()
        println("Music feature: $assertions assertions passed")
    }

    private fun connectAndAsk() {
        var now = 1_000L
        val rig = FeatureRig(MusicFeature { now })
        rig.phone.playing = song
        val before = rig.status().getJSONObject("music")
        check(before.getBoolean("enabled") && before.isNull("last_push") && before.getString("supported") == "unknown", "Starts enabled, nothing pushed")
        rig.connect()
        check(rig.take() == listOf(SONG_PLAYING), "Connect pushes now playing")
        val st = rig.status().getJSONObject("music")
        check(st.getLong("last_push") == 1_000L && st.getString("access") == "granted", "Status records the push and access")
        check(st.keySet() == setOf("enabled", "access", "supported", "last_push"), "Status has only its four keys")
        check(!rig.status().toString().contains("Song") && !rig.status().toString().contains("Band"), "Status never carries track text")

        now = 2_000L
        rig.receive(Proto.command(18, 0))
        check(rig.take() == listOf(SONG_PLAYING), "18/0 is answered even when unchanged")
        check(rig.status().getJSONObject("music").getString("supported") == "yes", "A watch request proves support")
        check(rig.status().getJSONObject("music").getLong("last_push") == 2_000L, "last_push moves")
        check(rig.logs.none { it.contains("Song") || it.contains("Band") || it.contains("Album") }, "No track text in logs")
    }

    private fun debounceAndDedupe() {
        var now = 0L
        val rig = FeatureRig(MusicFeature { now })
        rig.phone.playing = song
        rig.connect()
        rig.take()

        // 30 s of playback later the track is 30 s (give or take 3 s) further on.
        now = 30_000L
        rig.phone.playing = song.copy(positionMs = 94_400)
        rig.phone.mediaChanged()
        check(rig.watch.delays() == listOf(500L), "A media change waits 500 ms")
        rig.fire()
        check(rig.take().isEmpty(), "Position moving with playback is not re-sent")

        rig.phone.playing = song.copy(positionMs = 10_000)
        rig.phone.mediaChanged()
        rig.fire()
        check(rig.take() == listOf(SONG_AT_10), "A seek is sent")
        rig.phone.playing = song.copy(positionMs = 10_000, playing = false)
        rig.phone.mediaChanged()
        rig.fire()
        rig.take()
        now = 90_000L
        rig.phone.playing = song.copy(positionMs = 12_000, playing = false)
        rig.phone.mediaChanged()
        rig.fire()
        check(rig.take().isEmpty(), "Paused, 2 s of drift is not a seek")
        rig.phone.playing = song.copy(positionMs = 61_900, playing = false)
        rig.phone.mediaChanged()
        rig.fire()
        check(rig.take() == listOf(SONG_PAUSED), "Paused, a seek is sent")

        rig.phone.playing = song.copy(title = "Next", positionMs = 0)
        rig.phone.mediaChanged()
        rig.phone.mediaChanged()
        rig.phone.mediaChanged()
        check(rig.watch.delays() == listOf(500L), "A burst keeps one pending push")
        check(rig.take().isEmpty(), "Nothing goes out before the debounce")
        rig.fire()
        check(rig.take() == listOf(NEXT_PLAYING), "The burst is one push")

        rig.phone.playing = song.copy(playing = false)
        rig.phone.mediaChanged()
        rig.fire()
        check(rig.take() == listOf(SONG_PAUSED), "Pause pushes state 2")

        rig.phone.playing = null
        rig.phone.mediaChanged()
        rig.fire()
        check(rig.take() == listOf(NOTHING_VOL), "Session gone: state 0 with the last volume")
    }

    private fun mediaKeys() {
        val rig = FeatureRig(MusicFeature { 0L })
        rig.phone.playing = song
        rig.connect()
        rig.take()

        rig.receive(key(0))
        rig.receive(key(1))
        rig.receive(key(3))
        rig.receive(key(4))
        check(rig.phone.actions == listOf(MediaAction.PLAY, MediaAction.PAUSE, MediaAction.PREVIOUS, MediaAction.NEXT), "Keys 0, 1, 3, 4 map to play, pause, previous, next")
        check(rig.watch.delays().isEmpty(), "Keys push nothing themselves: the player's change does")

        rig.phone.actions.clear()
        rig.receive(key(5, 100))
        rig.receive(key(5, 0))
        rig.receive(key(5, 40))
        check(rig.phone.actions == listOf(MediaAction.VOLUME_UP, MediaAction.VOLUME_DOWN, MediaAction.VOLUME_DOWN), "Key 5 compares with the phone volume (40)")

        rig.phone.actions.clear()
        rig.receive(key(2))
        rig.receive(Proto.command(18, 2))
        check(rig.phone.actions.isEmpty(), "Undefined key 2 and a missing MediaKey do nothing")
        check(rig.logs.contains("Unknown media key 2") && rig.logs.contains("Unknown media key -1"), "They are logged by number")

        rig.phone.volume = 50
        rig.phone.mediaChanged()
        rig.fire()
        check(rig.take() == listOf("08121001a201170a15080110322204536f6e672a0442616e64303d38c801"), "A volume change pushes the new volume (50)")
        rig.fire()
        check(rig.sent().isEmpty(), "Exactly one push")

        rig.phone.playing = null
        rig.receive(key(4))
        check(rig.logs.last() == "No player took media key 4", "No session: logged")
    }

    private fun routesAndSettings() {
        val rig = FeatureRig(MusicFeature { 0L })
        rig.phone.playing = song
        rig.connect()
        rig.take()

        val (code, body) = rig.call("GET", "/api/music")
        val np = body.getJSONObject("now_playing")
        check(code == 200 && body.getBoolean("enabled") && body.getBoolean("connected") && body.getString("access") == "granted", "GET reports state")
        check(np.getString("title") == "Song" && np.getString("artist") == "Band" && np.getString("album") == "Album", "GET carries track text")
        check(np.getString("state") == "playing" && np.getLong("position_ms") == 61_900L && np.getLong("duration_ms") == 200_500L && np.getInt("volume") == 40, "GET carries position, duration and volume")

        val bad1 = rig.call("PUT", "/api/music/settings", JSONObject().put("enabled", "no"))
        check(bad1.first == 400 && bad1.second.getString("error") == "Set enabled to true or false", "Non-boolean is a 400")
        val bad2 = rig.call("PUT", "/api/music/settings", JSONObject().put("enabled", true).put("volume", 3))
        check(bad2.first == 400 && bad2.second.getString("error") == "Unknown music setting 'volume'", "Unknown key is a 400")
        val bad3 = rig.call("PUT", "/api/music/settings")
        check(bad3.first == 400, "Empty body is a 400")
        check(rig.call("POST", "/api/music").first == 400, "Unrouted method is a 400")
        check(rig.settings.values.isEmpty() && rig.take().isEmpty(), "Rejected PUTs change nothing")

        val (offCode, off) = rig.call("PUT", "/api/music/settings", JSONObject().put("enabled", false))
        check(offCode == 200 && !off.getBoolean("enabled"), "PUT answers the new view")
        check(rig.settings.values["feature.music.settings"] == """{"enabled":false}""", "Saved under the feature prefix")
        check(rig.take() == listOf(NOTHING), "Disabling clears the watch's track")

        rig.receive(Proto.command(18, 0))
        check(rig.take() == listOf(NOTHING), "Disabled: 18/0 gets state 0 without phone data")
        rig.receive(key(4))
        check(rig.phone.actions.isEmpty() && rig.logs.last() == "Music is off; ignored media key 4", "Disabled: keys are ignored")
        rig.phone.mediaChanged()
        check(rig.watch.delays().isEmpty(), "Disabled: media changes schedule nothing")
        check(!rig.status().getJSONObject("music").getBoolean("enabled"), "Status shows disabled")

        rig.call("PUT", "/api/music/settings", JSONObject().put("enabled", true))
        check(rig.take() == listOf(SONG_PLAYING), "Re-enabling pushes now playing")

        rig.disconnect()
        val offline = rig.call("PUT", "/api/music/settings", JSONObject().put("enabled", true))
        check(offline.first == 200 && !offline.second.getBoolean("connected") && rig.take().isEmpty(), "Offline PUT saves without sending")
    }

    private fun disconnectMidDebounce() {
        val rig = FeatureRig(MusicFeature { 0L })
        rig.phone.playing = song
        rig.connect()
        rig.take()
        rig.phone.playing = song.copy(title = "Next")
        rig.phone.mediaChanged()
        check(rig.watch.delays() == listOf(500L), "Push pending")
        rig.disconnect()
        check(rig.watch.delays().isEmpty(), "Disconnect cancels the pending push")
        rig.fire()
        rig.phone.mediaChanged()
        check(rig.watch.delays().isEmpty() && rig.sent().isEmpty(), "Offline changes schedule nothing")
        rig.receive(Proto.command(18, 0))
        check(rig.sent().isEmpty() && rig.logs.none { it.startsWith("Dropped") }, "Offline 18/0 sends nothing and logs nothing")
        rig.connect()
        check(rig.take() == listOf("08121001a201170a150801102822044e6578742a0442616e64303d38c801"), "Reconnect pushes the current track")
    }

    private fun accessAndNothingPlaying() {
        val rig = FeatureRig(MusicFeature { 0L }, phone = FakePhone().also { it.access = false })
        rig.connect()
        check(rig.take() == listOf(NOTHING_VOL), "No session: state 0 with the music volume")
        check(rig.status().getJSONObject("music").getString("access") == "denied", "Access denied")
        rig.phone.mediaChanged()
        check(rig.watch.delays().isEmpty(), "No listener without access")
        rig.phone.access = true
        val (_, body) = rig.call("GET", "/api/music")
        check(body.isNull("now_playing") && body.getString("access") == "granted", "GET shows no track, access granted")
        rig.phone.mediaChanged()
        check(rig.watch.delays() == listOf(500L), "Once access is granted, GET registers the listener")
        rig.fire()
        check(rig.take().isEmpty(), "Still nothing playing: no duplicate push")
    }

    private fun refusedAndTruncated() {
        val rig = FeatureRig(MusicFeature { 5L })
        val long = "a".repeat(63) + "🎵" + "tail"
        rig.phone.playing = song.copy(title = long, artist = "b".repeat(80))
        rig.watch.refuse = true
        rig.connect()
        check(rig.sent().isEmpty() && rig.status().getJSONObject("music").isNull("last_push"), "Refused send is not a push")
        rig.watch.refuse = false
        rig.receive(Proto.command(18, 0))
        val info = Proto.message(Proto.message(Proto.decode(rig.watch.sent.single()), 20), 1)
        check(Proto.string(info, 4) == "a".repeat(63) && Proto.string(info, 5) == "b".repeat(64), "Text is cut to 64 units without splitting a surrogate pair")
        check(rig.logs.none { it.contains("aaaa") || it.contains("bbbb") }, "Refusal logs carry no track text")
    }
}
