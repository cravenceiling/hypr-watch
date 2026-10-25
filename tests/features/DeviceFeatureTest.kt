package local.hyprwatch

import org.json.JSONObject
import java.time.ZoneId
import java.time.ZonedDateTime

/** DeviceFeature through FeatureRig: clock bytes, battery and info replies, find watch and find phone. */
object DeviceFeatureTest {
    private var assertions = 0

    private fun check(b: Boolean, label: String) {
        assertions++
        if (!b) throw AssertionError(label)
    }

    private fun bytes(hex: String): ByteArray = hex.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private const val MADRID_CLOCK = "0802 1003 222c 222a 0a07 08ea0f 100a 1818 1208 080e 1005 1809 207b 1a13 0808 1008 1a0d 4575726f70652f4d6164726964 2000"
    private const val NEW_YORK_12H = "0802 1003 222f 222d 0a07 08ea0f 1001 180f 1208 0809 101e 1800 2000 1a16 0827 1000 1a10 416d65726963612f4e65775f596f726b 2001"
    private const val DEVICE_INFO = "08021002"
    private const val BATTERY = "08021001"
    private const val BASIC_STATE = "0802104e"
    private const val FIND_WATCH_START = "0802101222022800"
    private const val FIND_WATCH_STOP = "0802101222022801"
    private const val FIND_PHONE_STOP = "0802101122022801"

    private fun h(s: String) = s.replace(" ", "")

    @JvmStatic
    fun main(args: Array<String>) {
        clockAndConnect()
        replies()
        deviceStateRefused()
        timeRoutes()
        findWatch()
        findPhone()
        println("Device feature: $assertions assertions passed")
    }

    private val madrid = ZonedDateTime.of(2026, 10, 24, 14, 5, 9, 123_000_000, ZoneId.of("Europe/Madrid"))

    private fun clockAndConnect() {
        var t = madrid
        val rig = FeatureRig(DeviceFeature { t })
        val device = rig.status().getJSONObject("device")
        check(device.isNull("battery") && device.isNull("device") && device.isNull("time_synced_at"), "Nothing known before connect")
        check(!device.getJSONObject("find_phone").getBoolean("ringing"), "Phone not ringing")

        rig.connect()
        check(rig.take() == listOf(h(MADRID_CLOCK), DEVICE_INFO, BATTERY, BASIC_STATE), "Connect sets the clock first, then asks for info, battery and state")
        check(rig.status().getJSONObject("device").getLong("time_synced_at") == madrid.toInstant().toEpochMilli(), "Time sync recorded")

        // Winter in New York: standard offset -5 h = -20 quarters (zigzag 39), no DST, 12-hour.
        t = ZonedDateTime.of(2026, 1, 15, 9, 30, 0, 0, ZoneId.of("America/New_York"))
        rig.phone.uses24Hour = false
        rig.phone.timeChanged()
        check(rig.take() == listOf(h(NEW_YORK_12H)), "A time zone change re-sends the clock with negative offsets and 12-hour time")

        check(rig.phone.periodic.single().first == 24 * 60 * 60 * 1000L, "A daily resync is scheduled")
        rig.phone.tick()
        check(rig.take() == listOf(h(NEW_YORK_12H)), "The daily tick re-sends the clock")

        rig.disconnect()
        rig.phone.timeChanged()
        rig.phone.tick()
        check(rig.take().isEmpty(), "Nothing is sent while disconnected")
    }

    private fun replies() {
        var t = madrid
        val rig = FeatureRig(DeviceFeature { t })
        rig.connect()
        rig.take()

        // Battery { level 75, state 1 } in Power(1) in System.power(2).
        rig.receive(bytes("0802 1001 2208 1206 0a04 084b 1001"))
        var battery = rig.status().getJSONObject("device").getJSONObject("battery")
        check(battery.getInt("level") == 75 && battery.getBoolean("charging") && battery.getLong("at") == madrid.toInstant().toEpochMilli(), "Battery reply lands in status")

        // 2/79 DeviceState { chargingState 2 } is cached and asks for the level again.
        rig.receive(bytes("0802 104f 2205 8a0302 0802"))
        check(rig.take() == listOf(BATTERY), "A state push re-requests the battery")
        t = t.plusMinutes(1)
        rig.receive(bytes("0802 1001 2206 1204 0a02 0850"))
        battery = rig.status().getJSONObject("device").getJSONObject("battery")
        check(battery.getInt("level") == 80 && !battery.getBoolean("charging") && battery.getLong("at") == t.toInstant().toEpochMilli(), "Cached charging state merges into a level-only reply")

        // 2/78 BasicDeviceState { isCharging 1, batteryLevel 42, isWorn 1, isUserAsleep 0 }.
        rig.receive(bytes("0802 104e 220b 8203 08 0801 102a 1801 2000"))
        battery = rig.status().getJSONObject("device").getJSONObject("battery")
        check(battery.getInt("level") == 42 && battery.getBoolean("charging") && rig.take().isEmpty(), "Basic state with a level updates the battery")
        rig.receive(bytes("0802 104e 2209 8203 06 0800 1801 2000"))
        check(rig.take() == listOf(BATTERY) && rig.status().getJSONObject("device").getJSONObject("battery").getInt("level") == 42, "Basic state without a level asks for it")

        // Rejected and empty replies change nothing.
        rig.receive(bytes("0802 1001 2206 1204 0a02 0801 a00601"))
        check(rig.status().getJSONObject("device").getJSONObject("battery").getInt("level") == 42, "A rejected battery reply is ignored")
        check(rig.logs.last() == "Watch rejected the battery command with status 1", "Rejection logged")
        rig.receive(bytes("0802 1001"))
        check(rig.logs.last() == "Watch sent a battery command without a payload", "Empty reply logged")
        rig.receive(bytes("0802 1001 2202 1200"))
        check(rig.logs.last() == "Watch sent a battery reply without a level", "Reply without level logged")

        // DeviceInfo { serial, firmware, unknown3 "", model } in System.deviceInfo(3).
        val info = Proto.join(Proto.text(1, "51234/ABCD6789"), Proto.text(2, "2.2.105"), Proto.text(3, ""), Proto.text(4, "M2351W1"))
        rig.receive(Proto.command(2, 2, Proto.bytes(4, Proto.bytes(3, info))))
        val d = rig.status().getJSONObject("device").getJSONObject("device")
        check(d.getString("model") == "M2351W1" && d.getString("firmware") == "2.2.105" && d.getString("serial_suffix") == "6789" && d.length() == 3, "Device info keeps only the serial's last 4")
        check(rig.logs.none { it.contains("ABCD") }, "The serial is never logged")
        check(rig.call("GET", "/api/device").second.toString() == rig.status().getJSONObject("device").toString(), "GET /api/device is the status")

        rig.disconnect()
        check(rig.status().getJSONObject("device").getJSONObject("battery").getInt("level") == 42, "Last battery stays after a disconnect")
    }

    private fun info(firmware: String): ByteArray =
        Proto.command(2, 2, Proto.bytes(4, Proto.bytes(3, Proto.join(Proto.text(1, "51234/ABCD6789"), Proto.text(2, firmware), Proto.text(4, "M2351W1")))))

    /** Firmware 2.2.105 answers 2/78 with status 1 on every connect: ask once, then rely on 2/79 and 2/1. */
    private fun deviceStateRefused() {
        var t = madrid
        val rig = FeatureRig(DeviceFeature { t })
        fun device() = rig.status().getJSONObject("device")
        rig.connect()
        check(rig.take() == listOf(h(MADRID_CLOCK), DEVICE_INFO, BATTERY, BASIC_STATE), "First connect asks for the device state")
        check(device().isNull("device_state_supported"), "Unknown until the watch answers")
        rig.receive(info("2.2.105"))

        // 2/78 { status(100) = 1 }, as the watch answered it.
        rig.receive(bytes("0802 104e a006 01"))
        check(!device().getBoolean("device_state_supported"), "Refusal shown in status")
        check(rig.logs.last() == "Watch rejected the device state command with status 1; not asking for it again", "Refusal logged: ${rig.logs.last()}")
        check(rig.settings.values["feature.device.support"] == """{"device_state_supported":false,"firmware":"2.2.105"}""", "Refusal saved: ${rig.settings.values}")
        check(rig.take().isEmpty(), "A refusal sends nothing")

        rig.disconnect()
        rig.connect()
        check(rig.take() == listOf(h(MADRID_CLOCK), DEVICE_INFO, BATTERY), "Reconnect skips 2/78")

        // Charging still comes from 2/79 { chargingState 1 } and 2/1.
        rig.receive(bytes("0802 104f 2205 8a0302 0801"))
        check(rig.take() == listOf(BATTERY), "A 2/79 push still asks for the battery")
        rig.receive(bytes("0802 1001 2206 1204 0a02 0846"))
        val battery = device().getJSONObject("battery")
        check(battery.getInt("level") == 70 && battery.getBoolean("charging"), "Charging state from 2/79 merges into 2/1: $battery")

        // The app restarts: the saved refusal still holds.
        val later = FeatureRig(DeviceFeature { t })
        later.settings.values.putAll(rig.settings.values)
        later.connect()
        check(later.take() == listOf(h(MADRID_CLOCK), DEVICE_INFO, BATTERY), "A new app session skips 2/78 too")
        check(!later.status().getJSONObject("device").getBoolean("device_state_supported"), "and reports it unsupported")
        later.receive(info("2.2.105"))
        check(later.settings.values["feature.device.support"] == """{"device_state_supported":false,"firmware":"2.2.105"}""", "Same firmware keeps the refusal")

        // A firmware update asks again, and an answer clears the refusal.
        later.receive(info("2.2.110"))
        check(later.status().getJSONObject("device").isNull("device_state_supported") && later.settings.values["feature.device.support"] == "{}", "New firmware forgets the refusal")
        later.disconnect()
        later.connect()
        check(later.take() == listOf(h(MADRID_CLOCK), DEVICE_INFO, BATTERY, BASIC_STATE), "and asks for the device state again")
        later.receive(bytes("0802 104e 220b 8203 08 0801 102a 1801 2000"))
        check(later.status().getJSONObject("device").getBoolean("device_state_supported"), "An answer marks it supported")
    }

    private fun timeRoutes() {
        val rig = FeatureRig(DeviceFeature { madrid })
        val offline = rig.call("POST", "/api/device/time-sync")
        check(offline.first == 503 && offline.second.getString("error") == "Connect the watch first", "Time sync needs the watch")
        rig.connect()
        rig.take()
        val ok = rig.call("POST", "/api/device/time-sync")
        check(ok.first == 200 && ok.second.getBoolean("sent") && ok.second.getBoolean("uses_24_hour") && rig.take() == listOf(h(MADRID_CLOCK)), "Time sync sends the clock")
        rig.watch.refuse = true
        val full = rig.call("POST", "/api/device/time-sync")
        check(full.first == 503 && full.second.getString("error") == "The watch did not take the time; try again", "A full window is a 503")
    }

    private fun findWatch() {
        val rig = FeatureRig(DeviceFeature { madrid })
        val offline = rig.call("POST", "/api/device/find-watch", JSONObject().put("on", true))
        check(offline.first == 503 && rig.watch.delays().isEmpty(), "Find watch needs the watch")
        rig.connect()
        rig.take()

        for (body in listOf(JSONObject(), JSONObject().put("on", "yes"), JSONObject().put("on", 1))) {
            val bad = rig.call("POST", "/api/device/find-watch", body)
            check(bad.first == 400 && bad.second.getString("error") == "Send {\"on\": true} or {\"on\": false}", "Bad find-watch body is a 400: $body")
        }
        check(rig.take().isEmpty(), "Bad bodies send nothing")

        val start = rig.call("POST", "/api/device/find-watch", JSONObject().put("on", true))
        check(start.first == 200 && start.second.getBoolean("sent") && rig.take() == listOf(FIND_WATCH_START), "Start sends findDevice 0")
        check(rig.watch.delays() == listOf(30_000L) && rig.status().getJSONObject("device").getJSONObject("find_watch").getBoolean("vibrating"), "Start arms a 30 s stop")
        rig.fire()
        check(rig.take() == listOf(FIND_WATCH_STOP) && !rig.status().getJSONObject("device").getJSONObject("find_watch").getBoolean("vibrating"), "The timeout stops the watch")

        rig.call("POST", "/api/device/find-watch", JSONObject().put("on", true))
        rig.call("POST", "/api/device/find-watch", JSONObject().put("on", true))
        check(rig.watch.delays() == listOf(30_000L), "A second start replaces the timer")
        val stop = rig.call("POST", "/api/device/find-watch", JSONObject().put("on", false))
        check(stop.first == 200 && stop.second.getBoolean("sent") && rig.take() == listOf(FIND_WATCH_START, FIND_WATCH_START, FIND_WATCH_STOP) && rig.watch.delays().isEmpty(), "Stop sends findDevice 1 and cancels the timer")

        rig.call("POST", "/api/device/find-watch", JSONObject().put("on", true))
        rig.receive(bytes("0802 1012 2202 2801"))
        check(!rig.status().getJSONObject("device").getJSONObject("find_watch").getBoolean("vibrating"), "Found-it on the watch clears vibrating")
        rig.take()
        rig.disconnect()
        check(rig.watch.delays().isEmpty() && rig.take().isEmpty(), "Disconnect mid-find cancels the stop timer")
        val stopOffline = rig.call("POST", "/api/device/find-watch", JSONObject().put("on", false))
        check(stopOffline.first == 200 && !stopOffline.second.getBoolean("sent"), "Stop while disconnected is not an error")
    }

    private fun findPhone() {
        val rig = FeatureRig(DeviceFeature { madrid })
        rig.connect()
        rig.take()
        fun ringing() = rig.status().getJSONObject("device").getJSONObject("find_phone").getBoolean("ringing")

        rig.receive(bytes("0802 1011 2202 2800"))
        check(rig.phone.ring?.first == 30_000L && ringing() && rig.take().isEmpty(), "Watch find-phone start rings the phone for 30 s")
        rig.receive(bytes("0802 1011 2202 2801"))
        check(rig.phone.ring == null && !ringing() && rig.take().isEmpty(), "Watch stop silences the phone without echoing a stop")

        rig.receive(bytes("0802 1011 2202 2800"))
        rig.phone.ringEnded()
        check(!ringing() && rig.take() == listOf(FIND_PHONE_STOP), "A timeout or opening the app closes the watch's screen")

        rig.receive(bytes("0802 1011"))
        check(rig.phone.ring == null && !ringing() && rig.logs.last() == "Watch sent a find phone command without a payload", "Find phone without a System payload does nothing")
        rig.receive(bytes("0802 1011 2200"))
        check(rig.phone.ring?.first == 30_000L && ringing(), "A System payload without findDevice is 0, a start, as in GB")

        // The app is open, so only the route can stop the ring.
        val stop = rig.call("POST", "/api/device/find-phone/stop")
        check(stop.first == 200 && stop.second.getBoolean("stopped") && stop.second.getBoolean("sent"), "Stop route: ${stop.second}")
        check(rig.phone.ring == null && !ringing() && rig.take() == listOf(FIND_PHONE_STOP), "It silences the phone and closes the watch's screen")
        val idle = rig.call("POST", "/api/device/find-phone/stop")
        check(idle.first == 200 && !idle.second.getBoolean("stopped") && !idle.second.getBoolean("sent") && rig.take().isEmpty(), "Stopping a silent phone sends nothing")

        rig.phone.cannotRing = true
        rig.receive(bytes("0802 1011 2202 2800"))
        check(!ringing() && rig.take() == listOf(FIND_PHONE_STOP), "A phone that cannot ring tells the watch to stop")
        rig.phone.cannotRing = false

        rig.receive(bytes("0802 1011 2202 2800"))
        rig.disconnect()
        check(rig.phone.ring != null && ringing(), "The phone keeps ringing when the watch drops")
        rig.phone.ringEnded()
        check(!ringing() && rig.take().isEmpty() && rig.logs.last() == "Dropped a find phone stop: watch is not connected", "Stop after a drop is dropped, not thrown")
        rig.connect()
        rig.take()
        rig.receive(bytes("0802 1011 2202 2800"))
        rig.disconnect()
        val offline = rig.call("POST", "/api/device/find-phone/stop")
        check(offline.second.getBoolean("stopped") && !offline.second.getBoolean("sent") && rig.phone.ring == null && rig.take().isEmpty(), "The route stops the phone while the watch is away")
    }
}
