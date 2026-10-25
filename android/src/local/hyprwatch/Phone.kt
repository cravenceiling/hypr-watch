package local.hyprwatch

/**
 * Android services a [WatchFeature] may use, in plain types so features stay host-testable.
 * AndroidPhone is the device adapter, FakePhone the test one. A feature that needs another service
 * adds a method here and to both adapters.
 */
interface Phone {
    /**
     * The newest coarse location Android or the app knows; null without a location permission or a
     * fix. Never waits for one.
     */
    fun coarseLocation(): PhoneLocation?

    /** Why [coarseLocation] may have nothing: the permission, or Location turned off in the phone's settings. */
    fun locationAccess(): LocationAccess

    /**
     * Calls [listener] on a background thread when a fresh fix lands; returns a cancel function.
     * Access granted only while the app is in use gives a fix only while it is open, so one is taken
     * each time the app opens.
     */
    fun onLocation(listener: () -> Unit): () -> Unit

    /** The app's label, or [pkg] when Android cannot resolve it. */
    fun appLabel(pkg: String): String

    /**
     * The app's launcher icon drawn at [size] x [size], as ARGB pixels row by row (unpremultiplied);
     * null when Android cannot find the app or its icon. May block briefly: not on a link thread.
     */
    fun appIcon(pkg: String, size: Int): IntArray?

    /** Whether the user granted notification access, which media sessions need. */
    fun mediaAccess(): Boolean

    /**
     * The first active media session's state, its position extrapolated to now while playing; null
     * when there is none or notification access is off.
     */
    fun nowPlaying(): NowPlaying?

    /** The music stream's volume from 0 to 100, with or without a session. */
    fun musicVolume(): Int

    /** Sends [action] to that session (volume goes to the music stream); false when nothing took it. */
    fun media(action: MediaAction): Boolean

    /**
     * Calls [listener] on a background thread when [nowPlaying] or [musicVolume] may have changed;
     * returns a cancel function. Without notification access it never fires: register again once
     * access is granted.
     */
    fun onMediaChanged(listener: () -> Unit): () -> Unit

    /**
     * Runs [task] about every [ms] on a background thread of its own where it may block, holding the
     * phone awake for up to a minute while it runs; returns a cancel function. Doze may stretch the
     * interval (to at least 9 minutes) but does not skip it.
     */
    fun every(ms: Long, task: () -> Unit): () -> Unit

    /**
     * Runs [task] once at wall-clock time [wallMs] (epoch ms) on [every]'s thread, waking the phone
     * for it as an [every] run does; returns a cancel function. Never early, but Android may run it
     * late: Doze by some minutes, Android 12 and later by up to an hour. A time already past runs at once.
     */
    fun at(wallMs: Long, task: () -> Unit): () -> Unit

    /** Whether Android is set to 24-hour time. */
    fun uses24HourTime(): Boolean

    /**
     * Calls [listener] on a background thread after the clock, the date, the time zone or the
     * 12/24-hour setting changes; returns a cancel function.
     */
    fun onTimeChanged(listener: () -> Unit): () -> Unit

    /**
     * Rings the default alarm sound at alarm volume and vibrates, replacing any ring in progress,
     * until [stopRinging], [ms] pass or the user opens the app. In the last two cases [stopped] runs
     * on a background thread. False when it could neither ring nor vibrate, or the app is stopping.
     */
    fun ringPhone(ms: Long, stopped: () -> Unit): Boolean

    /** Stops [ringPhone] without calling its stopped callback. */
    fun stopRinging()
}

enum class LocationAccess { ALLOWED, NOT_GRANTED, TURNED_OFF }

data class PhoneLocation(val latitude: Double, val longitude: Double, val accuracyM: Float, val timeMs: Long)

/** Track text is the user's: never log or store it. */
data class NowPlaying(
    val title: String,
    val artist: String,
    val album: String,
    val playing: Boolean,
    val durationMs: Long,
    val positionMs: Long,
)

enum class MediaAction { PLAY, PAUSE, NEXT, PREVIOUS, VOLUME_UP, VOLUME_DOWN }
