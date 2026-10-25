package local.hyprwatch

import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

/** Decisions [WatchConnection] takes about its socket and its errors, Android-free so host tests check them. */
object LinkGuards {
    /**
     * The failure a connect reports. A handshake failure the reader saw reaches the connect again,
     * wrapped in an [ExecutionException]; its cause keeps the reason (a rejected key stays a key problem).
     */
    fun cause(e: Exception): Exception = if (e is ExecutionException) e.cause as? Exception ?: e else e

    /** The `error` text `/status` shows for [e], a [cause], at connect [stage]. */
    fun errorText(e: Exception, stage: String): String =
        when (e) {
            is TimeoutException -> "Timed out at $stage"
            is ExecutionException -> "Watch operation failed"
            else -> e.message ?: e.javaClass.simpleName
        }

    /**
     * Stores a connect's new [socket], then checks [wanted]: a disconnect before the store is caught
     * here, and one after it finds the socket stored and closes it. False when the connect stops.
     */
    fun <S> adopt(socket: S, store: (S) -> Unit, wanted: () -> Boolean): Boolean {
        store(socket)
        return wanted()
    }

    /**
     * Whether the backstop after a link authenticated unwanted closes it: only while [seen], the
     * socket that authenticated, is still the [current] one and the link is still not [wanted].
     */
    fun backstopCloses(seen: Any?, current: Any?, wanted: Boolean): Boolean = seen != null && seen === current && !wanted
}
