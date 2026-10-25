/*  Copyright (C) 2023-2024 José Rebelo, Yoran Vulker (Gadgetbridge XiaomiHealthService)

    Ported from Gadgetbridge, which is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published by the Free
    Software Foundation, either version 3 of the License, or (at your option) any later version. */
package local.hyprwatch

import org.json.JSONObject
import java.io.IOException
import java.time.Instant

/**
 * The watch's realtime stats stream (8/45 start, 8/46 stop, 8/47 pushes) behind
 * /api/heart-rate/live. The stream runs while any consumer holds it; the API's hold lapses after
 * [leaseMs] unless renewed. Heart rate is health data: never log it.
 */
class LiveHeartRateFeature(
    private val clock: () -> Long = System::currentTimeMillis,
    private val leaseMs: Long = 120_000,
    private val freshMs: Long = 10_000,
) : WatchFeature {
    override val name = "live_heart_rate"
    private lateinit var host: FeatureHost
    private val lock = Any()

    /** A field the push leaves out is null: proto3 drops zero values, and some firmware may never send it. */
    private class Stats(val heartRate: Long?, val steps: Long?, val calories: Long?, val at: Long)

    private val consumers = HashSet<String>()
    private var latest: Stats? = null
    private var cancelLease: (() -> Unit)? = null
    private var lease = 0L

    /** Whether this session already logged which RealTimeStats fields the watch sends. */
    private var fieldsLogged = false

    override fun install(host: FeatureHost) {
        this.host = host
        host.on(TYPE, PUSH) { cmd -> onPush(cmd) }
        host.route("GET", "/api/heart-rate/live") { live() }
        host.route("POST", "/api/heart-rate/live") { r ->
            when (r.body.opt("on")) {
                true -> {
                    require(host.connected) { "Connect the watch first" }
                    if (!hold(API, true)) throw IOException("Could not start live heart rate; try again")
                    renewLease()
                }
                false -> {
                    endLease()
                    hold(API, false)
                }
                else -> throw IllegalArgumentException("on must be true or false")
            }
            live()
        }
    }

    // The watch forgets the stream on reconnect, so every hold ends with the session.
    override fun onConnected() = clear()

    override fun onDisconnected() = clear()

    override fun status(): JSONObject = synchronized(lock) { JSONObject().put("on", consumers.isNotEmpty()) }

    /**
     * Adds or removes [consumer]; the first one starts the stream and the last one stops it. False
     * when a start could not be sent, leaving [consumer] out.
     */
    fun hold(consumer: String, on: Boolean): Boolean {
        val change = synchronized(lock) {
            val was = consumers.isNotEmpty()
            if (on) consumers.add(consumer) else consumers.remove(consumer)
            if (was == consumers.isNotEmpty()) return true
            !was
        }
        val sent = host.send(Proto.command(TYPE, if (change) START else STOP), "live heart rate ${if (change) "start" else "stop"}")
        if (change && !sent) synchronized(lock) { consumers.remove(consumer) }
        return sent || !change
    }

    private fun renewLease() {
        val (old, token) = synchronized(lock) { cancelLease.also { cancelLease = null } to ++lease }
        old?.invoke()
        val cancel = host.after(leaseMs) {
            if (synchronized(lock) { lease == token }) {
                host.log("Live heart rate stopped after ${leaseMs / 1000} s without renewal")
                hold(API, false)
            }
        }
        synchronized(lock) { if (lease == token) cancelLease = cancel else cancel() }
    }

    private fun endLease() {
        val old = synchronized(lock) {
            lease++
            cancelLease.also { cancelLease = null }
        }
        old?.invoke()
    }

    private fun clear() {
        endLease()
        synchronized(lock) {
            consumers.clear()
            latest = null
            fieldsLogged = false
        }
    }

    private fun onPush(cmd: Map<Int, List<Any>>) {
        val s = Proto.message(Proto.message(cmd, 10), 39)
        val (idle, firstPush) = synchronized(lock) {
            if (consumers.isNotEmpty()) {
                latest = Stats(field(s, HEART_RATE), field(s, STEPS), field(s, CALORIES), clock())
            }
            consumers.isEmpty() to !fieldsLogged.also { fieldsLogged = true }
        }
        // Field numbers only, never values: shows on hardware which RealTimeStats fields the firmware fills.
        if (firstPush) host.log("Live heart rate push carries RealTimeStats fields ${s.keys.sorted()}")
        // Nobody asked for the stream (a stop got lost, or another app started it): stop it.
        if (idle) host.send(Proto.command(TYPE, STOP), "live heart rate stop")
    }

    private fun live(): JSONObject = synchronized(lock) {
        val s = latest?.takeIf { clock() - it.at <= freshMs }
        JSONObject()
            .put("on", consumers.isNotEmpty())
            // 10 bpm or less means the watch has no reading yet.
            .put("bpm", s?.heartRate?.takeIf { it > 10 } ?: JSONObject.NULL)
            // Cumulative for the day (GB); null when the watch left the field out.
            .put("steps", s?.steps ?: JSONObject.NULL)
            .put("calories", s?.calories ?: JSONObject.NULL)
            .put("at", s?.let { Instant.ofEpochMilli(it.at).toString() } ?: JSONObject.NULL)
    }

    private companion object {
        const val TYPE = 8
        const val START = 45
        const val STOP = 46
        const val PUSH = 47
        const val API = "api"

        // RealTimeStats (GB wear_fitness.proto): steps 1, calories 2, heartRate 4.
        const val STEPS = 1
        const val CALORIES = 2
        const val HEART_RATE = 4

        fun field(m: Map<Int, List<Any>>, n: Int): Long? = Proto.first(m, n) as? Long
    }
}
