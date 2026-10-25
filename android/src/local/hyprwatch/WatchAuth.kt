// SPDX-License-Identifier: AGPL-3.0-or-later
// Derived from Gadgetbridge's XiaomiAuthService and XiaomiSppProtocolV2; see NOTICE.
package local.hyprwatch

import java.io.IOException

/**
 * Authentication after each session config, and the session lifecycle around it, apart from
 * Android so tests drive the same sequence. The watch may send its session config again while
 * connected; that voids the link's sequence numbers and keys, so the session sees a disconnect
 * then and a connect after the next 1/27, as on a new connection.
 *
 * Every session config and every disconnect starts a new generation, under this object's lock.
 * A 1/27 counts only while its generation's authentication is under way, and a deadline only in
 * the generation that armed it, so neither can act on a session that has since ended. The connect
 * hooks run under the same lock, so a disconnect lands before or after all of them.
 */
class WatchAuth(
    private val link: SppLink,
    private val session: WatchSession,
    private val timer: WatchTimer,
    /** Must close the connection. */
    private val drop: (String) -> Unit,
    private val stage: (String) -> Unit,
    /** Runs after each successful authentication, once the session is connected; must not block. */
    private val authenticated: () -> Unit,
) {
    @Volatile var isAuthenticated = false
        private set

    private var generation = 0L

    /** Set from session config until the next disconnect. */
    private var crypto: WatchCrypto? = null
    private var cancelDeadline: (() -> Unit)? = null

    init {
        link.on(1, 26) { cmd ->
            status(cmd)
            synchronized(this) {
                val c = crypto ?: return@on
                val nonce = Proto.message(Proto.message(cmd, 3), 31)
                stage("waiting for authentication confirmation")
                link.send(c.finish(Proto.blob(nonce, 1), Proto.blob(nonce, 2)), false)
            }
        }
        link.on(1, 27) { cmd ->
            status(cmd)
            synchronized(this) {
                if (crypto == null || isAuthenticated) return@on
                stopDeadline()
                isAuthenticated = true
                link.authenticated()
                stage("authenticated")
                session.onConnected()
                authenticated()
            }
        }
    }

    /** The watch sent its session config: authenticate with [crypto], within [TIMEOUT_MS]. */
    @Synchronized
    fun begin(crypto: WatchCrypto) {
        val wasConnected = isAuthenticated
        isAuthenticated = false
        val gen = ++generation
        // Before the session hears of it, so a send that already passed its connected check is refused.
        link.reset(IOException("Watch restarted the session"))
        if (wasConnected) session.onDisconnected()
        this.crypto = crypto
        stopDeadline()
        cancelDeadline = timer.after(TIMEOUT_MS) { expire(gen) }
        stage("waiting for authentication challenge")
        link.send(crypto.begin(), false)
    }

    // Cancelling cannot stop a deadline that already started, so it checks its generation.
    @Synchronized
    private fun expire(gen: Long) {
        if (gen == generation && !isAuthenticated) drop("Watch did not finish authentication")
    }

    /** The connection closed; a request waiting for a reply fails with [cause]. */
    @Synchronized
    fun disconnected(cause: Exception) {
        generation++
        crypto = null
        isAuthenticated = false
        stopDeadline()
        link.reset(cause)
        session.onDisconnected()
    }

    private fun stopDeadline() {
        cancelDeadline?.invoke()
        cancelDeadline = null
    }

    private fun status(cmd: Map<Int, List<Any>>) {
        if (Proto.number(cmd, 100, 0) != 0L) throw PhoneSetupException("Watch rejected authentication", needsKey = true)
    }

    companion object {
        const val TIMEOUT_MS = 25_000L
    }
}
