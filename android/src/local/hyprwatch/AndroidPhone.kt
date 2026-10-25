package local.hyprwatch

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.format.DateFormat
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * [Phone] on Android. Callbacks arrive on one handler thread; listeners run on another, and [every]
 * and [at] tasks on a third, so a slow task never delays a listener.
 */
class AndroidPhone(private val context: Context) : Phone {
    private val callbacks = HandlerThread("phone-callbacks").apply { start() }
    private val handler = Handler(callbacks.looper)
    private val tasks = Executors.newSingleThreadExecutor()
    private val scheduled = Executors.newSingleThreadExecutor()
    private val alarms = AtomicInteger()
    private val cancels = ArrayList<() -> Unit>()
    private val app = context.applicationContext as Application

    // Find phone's start, stop and close run one at a time under this lock.
    private val ringLock = Any()
    private var ring: Ring? = null
    private var closed = false

    // Media sessions are visible to an enabled notification listener only.
    private val listener = ComponentName(context, WristNotificationListener::class.java)

    override fun coarseLocation(): PhoneLocation? {
        if (!locationGranted(context)) {
            forgetFix(context)
            return null
        }
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val known = try {
            lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }?.let(::rounded)
        } catch (e: SecurityException) {
            null
        }
        return listOfNotNull(known, savedFix(context)).maxByOrNull { it.timeMs }
    }

    override fun locationAccess(): LocationAccess {
        if (!locationGranted(context)) return LocationAccess.NOT_GRANTED
        val lm = context.getSystemService(LocationManager::class.java) ?: return LocationAccess.TURNED_OFF
        val on = if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled else lm.getProviders(true).any { it != LocationManager.PASSIVE_PROVIDER }
        return if (on) LocationAccess.ALLOWED else LocationAccess.TURNED_OFF
    }

    override fun onLocation(listener: () -> Unit): () -> Unit {
        val fire = { guard("location listener") { tasks.execute { guard("location listener") { listener() } } } }
        synchronized(fixListeners) { fixListeners.add(fire) }
        return { synchronized(fixListeners) { fixListeners.remove(fire) } }
    }

    override fun appLabel(pkg: String): String {
        val pm = context.packageManager
        return try {
            pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    override fun appIcon(pkg: String, size: Int): IntArray? {
        val pm = context.packageManager
        // Package visibility can hide the app on API 30+; the notification it posted carried its ApplicationInfo.
        val info: ApplicationInfo = try {
            pm.getApplicationInfo(pkg, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            WristNotificationListener.recentAppInfo(pkg)
        } ?: return null
        val icon = try {
            info.loadIcon(pm)
        } catch (e: Exception) {
            null
        } ?: return null
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            icon.setBounds(0, 0, size, size)
            icon.draw(Canvas(bitmap))
            val pixels = IntArray(size * size)
            bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
            return pixels
        } finally {
            bitmap.recycle()
        }
    }

    private fun controller(): MediaController? =
        try {
            context.getSystemService(MediaSessionManager::class.java)?.getActiveSessions(listener)?.firstOrNull()
        } catch (e: SecurityException) {
            null
        }

    private fun audio(): AudioManager = context.getSystemService(AudioManager::class.java)

    override fun mediaAccess(): Boolean = WristNotificationListener.hasAccess(context)

    override fun nowPlaying(): NowPlaying? {
        val c = controller() ?: return null
        val m = c.metadata
        val s = c.playbackState
        val playing = s?.state == PlaybackState.STATE_PLAYING
        val duration = m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        var position = s?.position ?: 0L
        // The session reports where playback was at its last update, not where it is now.
        if (playing && s.lastPositionUpdateTime > 0) {
            position += ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong()
            if (duration > 0) position = position.coerceAtMost(duration)
        }
        return NowPlaying(
            title = m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "",
            artist = m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "",
            album = m?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "",
            playing = playing,
            durationMs = duration,
            positionMs = position,
        )
    }

    override fun musicVolume(): Int {
        val a = audio()
        return a.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / a.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }

    override fun media(action: MediaAction): Boolean {
        when (action) {
            MediaAction.VOLUME_UP, MediaAction.VOLUME_DOWN -> {
                val direction = if (action == MediaAction.VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                audio().adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
                return true
            }
            else -> {
                val controls = controller()?.transportControls ?: return false
                when (action) {
                    MediaAction.PLAY -> controls.play()
                    MediaAction.PAUSE -> controls.pause()
                    MediaAction.NEXT -> controls.skipToNext()
                    else -> controls.skipToPrevious()
                }
                return true
            }
        }
    }

    override fun onMediaChanged(listener: () -> Unit): () -> Unit {
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return {}
        val fire = { tasks.execute { guard("media listener") { listener() } } }
        val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) = fire()

            override fun onMetadataChanged(metadata: MediaMetadata?) = fire()
        }
        var bound = emptyList<MediaController>()
        val rebind = { list: List<MediaController>? ->
            bound.forEach { it.unregisterCallback(callback) }
            bound = list ?: emptyList()
            bound.forEach { it.registerCallback(callback, handler) }
            fire()
        }
        val sessions = MediaSessionManager.OnActiveSessionsChangedListener { rebind(it) }
        try {
            manager.addOnActiveSessionsChangedListener(sessions, this.listener, handler)
            handler.post { guard("media sessions") { rebind(manager.getActiveSessions(this.listener)) } }
        } catch (e: SecurityException) {
            return {}
        }
        // No public constant: the system's volume broadcast, which media sessions do not report.
        val volume = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) == AudioManager.STREAM_MUSIC) fire()
            }
        }
        register(volume, IntentFilter("android.media.VOLUME_CHANGED_ACTION"))
        return remember {
            manager.removeOnActiveSessionsChangedListener(sessions)
            context.unregisterReceiver(volume)
            handler.post { rebind(null) }
        }
    }

    override fun every(ms: Long, task: () -> Unit): () -> Unit {
        val action = "${context.packageName}.EVERY.${alarms.incrementAndGet()}"
        val pending = PendingIntent.getBroadcast(context, 0, Intent(action).setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
        val am = context.getSystemService(AlarmManager::class.java)
        // One-shot and re-armed on each run: a repeating alarm waits out Doze, allow-while-idle does not.
        val arm = { am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + ms, pending) }
        val active = AtomicBoolean(true)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (!active.get()) return
                arm()
                runAwake(task)
            }
        }
        register(receiver, IntentFilter(action))
        arm()
        return remember {
            active.set(false)
            am.cancel(pending)
            context.unregisterReceiver(receiver)
        }
    }

    override fun at(wallMs: Long, task: () -> Unit): () -> Unit {
        val action = "${context.packageName}.AT.${alarms.incrementAndGet()}"
        val pending = PendingIntent.getBroadcast(context, 0, Intent(action).setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
        val am = context.getSystemService(AlarmManager::class.java)
        val active = AtomicBoolean(true)
        lateinit var receiver: BroadcastReceiver
        val cancel = remember {
            active.set(false)
            am.cancel(pending)
            context.unregisterReceiver(receiver)
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (!active.get()) return
                cancel()
                runAwake(task)
            }
        }
        register(receiver, IntentFilter(action))
        // Wall-clock, so it follows a clock change; inexact, since exact alarms need a permission.
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wallMs, pending)
        return cancel
    }

    /** Runs [task] on the scheduled thread; called from an alarm's onReceive, which keeps the phone awake only until it returns. */
    private fun runAwake(task: () -> Unit) {
        val wake = context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HyprWatch:every")
        wake.setReferenceCounted(false)
        wake.acquire(TASK_MS)
        try {
            scheduled.execute {
                try {
                    guard("scheduled task") { task() }
                } finally {
                    wake.release()
                }
            }
        } catch (e: RejectedExecutionException) {
            wake.release()
        }
    }

    private fun register(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter, null, handler)
        }
    }

    override fun uses24HourTime(): Boolean = DateFormat.is24HourFormat(context)

    override fun onTimeChanged(listener: () -> Unit): () -> Unit {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                tasks.execute { guard("time listener") { listener() } }
            }
        }
        // ACTION_TIME_CHANGED also fires when the user switches between 12- and 24-hour time.
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        register(receiver, filter)
        return remember { context.unregisterReceiver(receiver) }
    }

    /** One find-phone ring; it ends when any activity of the app resumes. */
    private inner class Ring(val tone: Ringtone?, val vibrator: Vibrator?, val stopped: () -> Unit) : Application.ActivityLifecycleCallbacks {
        val timeout = Runnable { end(this, true) }

        override fun onActivityResumed(activity: Activity) = end(this, true)

        override fun onActivityCreated(activity: Activity, state: Bundle?) {}

        override fun onActivityStarted(activity: Activity) {}

        override fun onActivityPaused(activity: Activity) {}

        override fun onActivityStopped(activity: Activity) {}

        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}

        override fun onActivityDestroyed(activity: Activity) {}
    }

    override fun ringPhone(ms: Long, stopped: () -> Unit): Boolean {
        synchronized(ringLock) { return startRing(ms, stopped) }
    }

    /** Under [ringLock]. */
    private fun startRing(ms: Long, stopped: () -> Unit): Boolean {
        if (closed) return false
        ring?.let { end(it, false) }
        val tone = listOf(RingtoneManager.TYPE_ALARM, RingtoneManager.TYPE_RINGTONE)
            .firstNotNullOfOrNull { type -> RingtoneManager.getDefaultUri(type)?.let { RingtoneManager.getRingtone(context, it) } }
        val vibrator = context.getSystemService(Vibrator::class.java)?.takeIf { it.hasVibrator() }
        var started = false
        if (tone != null) {
            guard("find phone ring") {
                // USAGE_ALARM plays on the alarm stream, at alarm volume, even when the ringer is silenced.
                tone.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= 28) tone.isLooping = true
                tone.play()
                started = true
            }
        }
        if (vibrator != null) {
            guard("find phone vibration") {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0))
                started = true
            }
        }
        if (!started) return false
        val r = Ring(tone, vibrator, stopped)
        ring = r
        app.registerActivityLifecycleCallbacks(r)
        // A quitting handler drops the timeout, and a ring without one would never end.
        if (!handler.postDelayed(r.timeout, ms)) {
            end(r, false)
            return false
        }
        return true
    }

    override fun stopRinging() {
        synchronized(ringLock) { ring?.let { end(it, false) } }
    }

    private fun end(r: Ring, notify: Boolean) {
        synchronized(ringLock) {
            if (ring !== r) return
            ring = null
            handler.removeCallbacks(r.timeout)
            app.unregisterActivityLifecycleCallbacks(r)
            guard("find phone ring stop") { r.tone?.stop() }
            guard("find phone vibration stop") { r.vibrator?.cancel() }
        }
        if (notify) guard("find phone stopped") { tasks.execute { guard("find phone stopped") { r.stopped() } } }
    }

    /** A cancel function that runs once, here or at [close], and is then forgotten. */
    private fun remember(cancel: () -> Unit): () -> Unit {
        var done = false
        lateinit var once: () -> Unit
        once = {
            val run = synchronized(cancels) {
                cancels.remove(once)
                !done.also { done = true }
            }
            if (run) guard("cancel") { cancel() }
        }
        synchronized(cancels) { cancels.add(once) }
        return once
    }

    fun close() {
        synchronized(ringLock) {
            closed = true
            ring?.let { end(it, false) }
        }
        synchronized(cancels) { ArrayList(cancels) }.forEach { it() }
        callbacks.quitSafely()
        tasks.shutdownNow()
        scheduled.shutdownNow()
    }

    private inline fun guard(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.i("HyprWatch", "$what failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    companion object {
        /** The longest an [every] or [at] task keeps the phone awake. */
        private const val TASK_MS = 60_000L

        /** How long [takeFix] waits for a fix. */
        private const val FIX_MS = 30_000L

        private const val FIX_FILE = "phone-location"
        private val fixListeners = ArrayList<() -> Unit>()
        private val asking = AtomicBoolean()

        /**
         * From the Activity while it is in the foreground, where access granted only while the app is
         * in use allows a fix: asks Android for one, keeps it in private storage rounded to 2 decimals
         * (about 1 km) so weather has it after a restart, then calls the [onLocation] listeners.
         */
        fun takeFix(context: Context) {
            if (!locationGranted(context)) return forgetFix(context)
            if (!asking.compareAndSet(false, true)) return
            val lm = context.getSystemService(LocationManager::class.java) ?: return asking.set(false)
            // In the foreground Android also hands out its last-known fix, which a fresh request indoors
            // often cannot beat: keep that one too.
            try {
                lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
                    ?.takeIf { it.time > (savedFix(context)?.timeMs ?: 0) }?.let { save(context, it) }
            } catch (e: SecurityException) {
                Log.i("HyprWatch", "Last-known location refused")
            }
            val main = Handler(Looper.getMainLooper())
            val done = AtomicBoolean()
            val deliver = { loc: Location? ->
                if (done.compareAndSet(false, true)) {
                    asking.set(false)
                    if (loc != null) save(context, loc)
                }
            }
            // Coarse access alone cannot use GPS before Android 12.
            val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val providers = ArrayList<String>()
            if (Build.VERSION.SDK_INT >= 31) providers.add(LocationManager.FUSED_PROVIDER)
            providers.add(LocationManager.NETWORK_PROVIDER)
            if (fine || Build.VERSION.SDK_INT >= 31) providers.add(LocationManager.GPS_PROVIDER)
            val provider = providers.firstOrNull { lm.isProviderEnabled(it) } ?: return deliver(null)
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    val cancel = CancellationSignal()
                    lm.getCurrentLocation(provider, cancel, { main.post(it) }, { deliver(it) })
                    main.postDelayed({ cancel.cancel(); deliver(null) }, FIX_MS)
                } else {
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) = deliver(location)
                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) {}
                        @Suppress("OVERRIDE_DEPRECATION")
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    }
                    @Suppress("DEPRECATION")
                    lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                    main.postDelayed({ lm.removeUpdates(listener); deliver(null) }, FIX_MS)
                }
            } catch (e: Exception) {
                Log.i("HyprWatch", "Location request failed: ${e.message ?: e.javaClass.simpleName}")
                deliver(null)
            }
        }

        /** Keeps [loc] rounded in private storage, then calls the [onLocation] listeners. */
        private fun save(context: Context, loc: Location) {
            val fix = rounded(loc)
            context.applicationContext.getSharedPreferences(FIX_FILE, Context.MODE_PRIVATE).edit()
                .putString("lat", fix.latitude.toString()).putString("lon", fix.longitude.toString())
                .putFloat("accuracy", fix.accuracyM).putLong("time", fix.timeMs).apply()
            synchronized(fixListeners) { ArrayList(fixListeners) }.forEach { it() }
        }

        /**
         * The kept fix has no expiry: the app may stay closed for days, and a fix about 1 km wide from
         * then gives better weather than none. It goes when the location permission does.
         */
        private fun forgetFix(context: Context) {
            val prefs = context.applicationContext.getSharedPreferences(FIX_FILE, Context.MODE_PRIVATE)
            if (prefs.contains("lat")) prefs.edit().clear().apply()
        }

        private fun savedFix(context: Context): PhoneLocation? {
            val prefs = context.applicationContext.getSharedPreferences(FIX_FILE, Context.MODE_PRIVATE)
            val lat = prefs.getString("lat", null)?.toDoubleOrNull() ?: return null
            val lon = prefs.getString("lon", null)?.toDoubleOrNull() ?: return null
            return PhoneLocation(lat, lon, prefs.getFloat("accuracy", 0f), prefs.getLong("time", 0))
        }

        private fun locationGranted(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        private fun rounded(l: Location) = PhoneLocation(Math.round(l.latitude * 100) / 100.0, Math.round(l.longitude * 100) / 100.0, l.accuracy, l.time)
    }
}
