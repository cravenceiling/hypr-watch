package local.hyprwatch

import org.json.JSONObject
import java.io.IOException
import java.time.ZonedDateTime

/*
 * The set-time message, the battery, device info and device state replies, and find watch / find
 * phone are ported from Gadgetbridge's XiaomiSystemService (Copyright (C) 2023-2024 Andreas
 * Shimokawa, José Rebelo, LuK1337, Yoran Vulker), AGPL-3.0-or-later.
 */

/**
 * Device basics on command type 2: the watch clock (2/3), battery (2/1, 2/78, 2/79), device info
 * (2/2), find watch (2/18) and find phone (2/17). Every command is fire-and-forget; replies arrive
 * as watch-initiated commands, and a missing one only leaves its status null.
 */
class DeviceFeature(private val now: () -> ZonedDateTime = ZonedDateTime::now) : WatchFeature {
    override val name = "device"
    private lateinit var host: FeatureHost
    private val cancels = ArrayList<() -> Unit>()

    // Guarded by this.
    private var battery: JSONObject? = null
    private var device: JSONObject? = null

    // From 2/78 or 2/79; used when a 2/1 reply has no charging state.
    private var charging: Boolean? = null

    /**
     * Whether the watch answers 2/78: null until it does, false once it refused (saved, so later
     * connects skip 2/78; the Redmi Watch 5 Active on 2.2.105 refuses it with status 1). 2/79 pushes
     * and 2/1 still carry the charging state. A firmware other than the one that refused asks again.
     */
    private var stateSupported: Boolean? = null
    private var timeSyncedAt: Long? = null
    private var ringing = false
    private var vibrating = false
    private var stopWatch: (() -> Unit)? = null

    override fun install(host: FeatureHost) {
        this.host = host
        host.on(TYPE, BATTERY) { cmd -> reply(cmd, "battery")?.let { onBattery(Proto.message(Proto.message(it, 2), 1)) } }
        host.on(TYPE, DEVICE_INFO) { cmd -> reply(cmd, "device info")?.let { onDeviceInfo(Proto.message(it, 3)) } }
        host.on(TYPE, BASIC_STATE) { cmd ->
            val status = Proto.number(cmd, 100, 0)
            if (status != 0L) basicStateRefused(status) else reply(cmd, "device state")?.let { onBasicState(it) }
        }
        host.on(TYPE, STATE_PUSH) { cmd -> reply(cmd, "device state")?.let { onStatePush(it) } }
        host.on(TYPE, FIND_PHONE) { cmd -> reply(cmd, "find phone")?.let { onFindPhone(it) } }
        host.on(TYPE, FIND_WATCH) { cmd ->
            // The watch's "found it" (GB does not know which value). The stop timer stays armed:
            // a stop after the watch already stopped does no harm.
            if (reply(cmd, "find watch") != null) synchronized(this) { vibrating = false }
        }
        host.route("GET", "/api/device") { status() }
        host.route("POST", "/api/device/time-sync") {
            if (!sendTime()) throw IOException(if (host.connected) "The watch did not take the time; try again" else "Connect the watch first")
            JSONObject().put("sent", true).put("time_synced_at", synchronized(this) { timeSyncedAt }).put("uses_24_hour", host.phone.uses24HourTime())
        }
        host.route("POST", "/api/device/find-watch") { r ->
            val on = r.body.opt("on") as? Boolean ?: throw IllegalArgumentException("Send {\"on\": true} or {\"on\": false}")
            JSONObject().put("on", on).put("sent", findWatch(on))
        }
        // The app is already open, so opening it cannot stop the ring.
        host.route("POST", "/api/device/find-phone/stop") {
            val was = synchronized(this) { ringing.also { ringing = false } }
            host.phone.stopRinging()
            JSONObject().put("stopped", was).put("sent", was && host.connected && host.send(findPhoneStop(), "find phone stop"))
        }
        cancels.add(host.phone.onTimeChanged { if (host.connected) sendTime() })
        cancels.add(host.phone.every(DAY_MS) { if (host.connected) sendTime() })
    }

    override fun onConnected() {
        sendTime()
        val refused = host.settings.get(SUPPORT)?.optBoolean(STATE_KEY, true) == false
        val ask = synchronized(this) {
            if (refused && stateSupported == null) stateSupported = false
            stateSupported != false
        }
        for (sub in listOfNotNull(DEVICE_INFO, BATTERY, BASIC_STATE.takeIf { ask })) host.send(Proto.command(TYPE, sub), "device request $TYPE/$sub")
    }

    override fun onDisconnected() {
        val cancel = synchronized(this) {
            vibrating = false
            stopWatch.also { stopWatch = null }
        }
        cancel?.invoke()
        // A ringing phone keeps ringing: the watch may have dropped because it is far from the phone.
    }

    override fun close() {
        cancels.forEach { it() }
        host.phone.stopRinging()
    }

    @Synchronized
    override fun status(): JSONObject =
        JSONObject()
            .put("battery", battery ?: JSONObject.NULL)
            .put("device", device ?: JSONObject.NULL)
            .put("time_synced_at", timeSyncedAt ?: JSONObject.NULL)
            .put("device_state_supported", stateSupported ?: JSONObject.NULL)
            .put("find_phone", JSONObject().put("ringing", ringing))
            .put("find_watch", JSONObject().put("vibrating", vibrating))

    /** The System sub-message of an accepted watch command, or null (logged) for a rejection or none. */
    private fun reply(cmd: Map<Int, List<Any>>, what: String): Map<Int, List<Any>>? {
        val status = Proto.number(cmd, 100, 0)
        if (status != 0L) {
            host.log("Watch rejected the $what command with status $status")
            return null
        }
        if (cmd[SYSTEM] == null) {
            host.log("Watch sent a $what command without a payload")
            return null
        }
        return Proto.message(cmd, SYSTEM)
    }

    private fun sendTime(): Boolean {
        val sent = host.send(clock(now(), host.phone.uses24HourTime()), "time")
        if (sent) synchronized(this) { timeSyncedAt = now().toInstant().toEpochMilli() }
        return sent
    }

    /** Battery { level = 1, state = 2 }: state 1 charging, 2 or 3 not. */
    private fun onBattery(b: Map<Int, List<Any>>) {
        val level = Proto.first(b, 1) as? Long ?: return host.log("Watch sent a battery reply without a level")
        val state = Proto.first(b, 2) as? Long
        synchronized(this) {
            if (state != null) chargingState(state)?.let { charging = it }
            setBattery(level, charging)
        }
    }

    /** The watch refused 2/78: remember it, so no later connect asks again. */
    private fun basicStateRefused(status: Long) {
        val first = synchronized(this) { (stateSupported != false).also { stateSupported = false } }
        if (!first) return host.log("Watch rejected the device state command with status $status")
        host.log("Watch rejected the device state command with status $status; not asking for it again")
        val firmware = synchronized(this) { device?.optString("firmware")?.takeIf { it.isNotEmpty() } }
        host.settings.put(SUPPORT, JSONObject().put(STATE_KEY, false).put("firmware", firmware ?: JSONObject.NULL))
    }

    /** New firmware may answer 2/78: forget an earlier refusal, so the next connect asks again. */
    private fun forgetRefusalOnUpdate(firmware: String) {
        val saved = host.settings.get(SUPPORT) ?: return
        val refusedOn = saved.optString("firmware").takeIf { !saved.isNull("firmware") && it.isNotEmpty() } ?: return
        if (refusedOn == firmware || firmware.isEmpty()) return
        host.settings.put(SUPPORT, JSONObject())
        synchronized(this) { if (stateSupported == false) stateSupported = null }
        host.log("Watch firmware changed; will ask for the device state again")
    }

    /** BasicDeviceState { isCharging = 1, batteryLevel = 2 }. */
    private fun onBasicState(system: Map<Int, List<Any>>) {
        val wasRefused = synchronized(this) { (stateSupported == false).also { stateSupported = true } }
        if (wasRefused) host.settings.put(SUPPORT, JSONObject())
        val s = Proto.message(system, 48)
        val isCharging = Proto.first(s, 1) as? Long
        val level = Proto.first(s, 2) as? Long
        synchronized(this) {
            if (isCharging != null) charging = isCharging != 0L
            if (level != null) setBattery(level, charging)
        }
        if (level == null) host.send(Proto.command(TYPE, BATTERY), "battery request")
    }

    /** DeviceState { chargingState = 1 }: cached, then the level is asked for again, as GB does. */
    private fun onStatePush(system: Map<Int, List<Any>>) {
        val state = Proto.first(Proto.message(system, 49), 1) as? Long
        if (state != null) chargingState(state)?.let { synchronized(this) { charging = it } }
        host.send(Proto.command(TYPE, BATTERY), "battery request")
    }

    private fun chargingState(state: Long): Boolean? =
        when (state) {
            1L -> true
            2L, 3L -> false
            else -> null
        }

    private fun setBattery(level: Long, charging: Boolean?) {
        battery = JSONObject()
            .put("level", level.coerceIn(0, 100))
            .put("charging", charging ?: JSONObject.NULL)
            .put("at", now().toInstant().toEpochMilli())
    }

    /** DeviceInfo { serialNumber = 1, firmware = 2, model = 4 }. Only the serial's last 4 characters are kept. */
    private fun onDeviceInfo(info: Map<Int, List<Any>>) {
        if (info.isEmpty()) return host.log("Watch sent device info without a payload")
        val serial = Proto.string(info, 1)
        val d = JSONObject()
            .put("model", Proto.string(info, 4))
            .put("firmware", Proto.string(info, 2))
            .put("serial_suffix", serial.takeLast(4))
        synchronized(this) { device = d }
        forgetRefusalOnUpdate(d.getString("firmware"))
    }

    /**
     * 2/17 findDevice: 0 starts ringing the phone, anything else stops it. A missing value is 0, as
     * in GB's proto3 reading; an echo of this app's own stop would carry 1.
     */
    private fun onFindPhone(system: Map<Int, List<Any>>) {
        if (Proto.number(system, FIND_DEVICE, 0) == 0L) {
            val started = host.phone.ringPhone(RING_MS, ::phoneStopped)
            synchronized(this) { ringing = started }
            if (!started) {
                host.log("Find phone: the phone can neither ring nor vibrate")
                host.send(findPhoneStop(), "find phone stop")
            }
        } else {
            host.phone.stopRinging()
            synchronized(this) { ringing = false }
        }
    }

    /** The phone stopped on its own (timeout, app opened): close the watch's find-phone screen. */
    private fun phoneStopped() {
        synchronized(this) { ringing = false }
        host.send(findPhoneStop(), "find phone stop")
    }

    /** 2/18 findDevice: 0 starts vibrating the watch, 1 stops it. A start is stopped after [VIBRATE_MS]. */
    private fun findWatch(on: Boolean): Boolean {
        val old = synchronized(this) { stopWatch.also { stopWatch = null } }
        old?.invoke()
        if (!on) {
            synchronized(this) { vibrating = false }
            return host.connected && host.send(findWatchCommand(false), "find watch stop")
        }
        if (!host.send(findWatchCommand(true), "find watch")) {
            throw IOException(if (host.connected) "The watch did not take the command; try again" else "Connect the watch first")
        }
        var cancel: (() -> Unit)? = null
        cancel = host.after(VIBRATE_MS) {
            synchronized(this) {
                if (stopWatch === cancel) {
                    stopWatch = null
                    vibrating = false
                }
            }
            host.send(findWatchCommand(false), "find watch stop")
        }
        synchronized(this) {
            stopWatch = cancel
            vibrating = true
        }
        return true
    }

    companion object {
        const val TYPE = 2
        const val BATTERY = 1
        const val DEVICE_INFO = 2
        const val CLOCK = 3
        const val FIND_PHONE = 17
        const val FIND_WATCH = 18
        const val BASIC_STATE = 78
        const val STATE_PUSH = 79
        private const val SYSTEM = 4
        private const val FIND_DEVICE = 5
        private const val SUPPORT = "support"
        private const val STATE_KEY = "device_state_supported"
        const val RING_MS = 30_000L
        const val VIBRATE_MS = 30_000L
        const val DAY_MS = 24 * 60 * 60 * 1000L

        /**
         * 2/3 Clock { date, time, timezone { zoneOffset, dstOffset, name }, isNot24hour }. Offsets are
         * sint32 in 15-minute units; zoneOffset is the standard offset without DST.
         */
        fun clock(t: ZonedDateTime, uses24Hour: Boolean): ByteArray {
            val instant = t.toInstant()
            val rules = t.zone.rules
            val zone = rules.getStandardOffset(instant).totalSeconds / 900
            val dst = (rules.getDaylightSavings(instant).seconds / 900).toInt()
            val date = Proto.join(Proto.num(1, t.year.toLong()), Proto.num(2, t.monthValue.toLong()), Proto.num(3, t.dayOfMonth.toLong()))
            val time = Proto.join(Proto.num(1, t.hour.toLong()), Proto.num(2, t.minute.toLong()), Proto.num(3, t.second.toLong()), Proto.num(4, (t.nano / 1_000_000).toLong()))
            val tz = Proto.join(Proto.s32(1, zone), Proto.s32(2, dst), Proto.text(3, t.zone.id))
            val c = Proto.join(Proto.bytes(1, date), Proto.bytes(2, time), Proto.bytes(3, tz), Proto.num(4, if (uses24Hour) 0 else 1))
            return Proto.command(TYPE, CLOCK, Proto.bytes(SYSTEM, Proto.bytes(4, c)))
        }

        fun findWatchCommand(on: Boolean): ByteArray = Proto.command(TYPE, FIND_WATCH, Proto.bytes(SYSTEM, Proto.num(FIND_DEVICE, if (on) 0 else 1)))

        fun findPhoneStop(): ByteArray = Proto.command(TYPE, FIND_PHONE, Proto.bytes(SYSTEM, Proto.num(FIND_DEVICE, 1)))
    }
}
