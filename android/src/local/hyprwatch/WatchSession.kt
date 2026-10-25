package local.hyprwatch

import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException

/**
 * Decides which thread runs what on one watch link, apart from Android so tests drive this same
 * wiring, and is the [WatchPort] that [features] run on. Reminder commands, feature requests and
 * submitted tasks run one at a time on [commands]. Watch-initiated commands, chunks and connect
 * hooks run on the reader thread, timer tasks on the timer thread; they only send fire-and-forget,
 * so a file streaming on channel 5 never waits behind a reminder reply.
 */
class WatchSession(
    private val link: SppLink,
    private val timer: WatchTimer,
    private val log: (String) -> Unit,
    private val isConnected: () -> Boolean,
    private val commands: ExecutorService,
    private val features: Features,
) : WatchPort {
    private val onCommands = ThreadLocal.withInitial { false }

    /**
     * Installs [features] on this session; call once, after whatever [isConnected] reads exists.
     * Not in the constructor: features register phone listeners that may call back, on any thread,
     * before install returns, and the owner's auth would not be built yet.
     */
    fun install() = features.install(this)

    /** Runs [task] on the commands thread, within [CALL_TIMEOUT_MS]. */
    fun <T> command(task: () -> T): T =
        callWithin(
            commands,
            CALL_TIMEOUT_MS,
            Callable {
                if (!isConnected()) throw IOException("Connect the watch first")
                onCommandThread(task)
            },
        )

    /** After authentication, under the session lock. */
    fun onConnected() = features.connected()

    /** Call after [isConnected] turned false. */
    fun onDisconnected() = features.disconnected()

    override val connected: Boolean get() = isConnected()

    override fun on(type: Int, sub: Int, handler: (Map<Int, List<Any>>) -> Unit) = link.on(type, sub, handler)

    override fun onChunk(handler: (ByteArray) -> Unit) {
        link.onChunk = handler
    }

    override fun onAcked(handler: () -> Unit) {
        link.onAcked = handler
    }

    override fun after(ms: Long, task: () -> Unit): () -> Unit = timer.after(ms, task)

    /** Fire-and-forget; false when nothing was sent. Never throws, as HealthCapture.Port requires. */
    override fun send(what: String, cmd: ByteArray): Boolean = fire(what) {
        link.send(cmd)
        true
    }

    /** As [send]; a send the link holds back for its window answers false without a log line. */
    override fun sendPaced(what: String, cmd: ByteArray): Boolean = fire(what) { link.sendPaced(cmd) }

    private fun fire(what: String, send: () -> Boolean): Boolean {
        if (!isConnected()) {
            log("Dropped a $what: watch is not connected")
            return false
        }
        return try {
            send()
        } catch (e: Exception) {
            log("Dropped a $what: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    override fun linkEpoch(): Long = link.epoch

    /** Upload data never waits: a full window answers false at once. */
    override fun sendData(epoch: Long, chunk: ByteArray): Boolean {
        if (!isConnected()) throw IOException("Watch is not connected")
        return link.sendData(epoch, chunk)
    }

    override fun dataInFlight(epoch: Long): Int = link.uploadsInFlight(epoch)

    override fun traceUpload(on: Boolean) = link.uploadPhase(on)

    override fun request(cmd: ByteArray): Map<Int, List<Any>> {
        val c = Proto.decode(cmd)
        val type = Proto.number(c, 1, -1).toInt()
        val sub = Proto.number(c, 2, -1).toInt()
        // Already on the commands thread (a submitted task): queuing behind itself would deadlock.
        if (onCommands.get() != true) return command { link.request(cmd, type, sub) }
        if (!isConnected()) throw IOException("Connect the watch first")
        return link.request(cmd, type, sub)
    }

    override fun submit(task: () -> Unit) {
        try {
            commands.execute { onCommandThread(task) }
        } catch (e: RejectedExecutionException) {
            log("Dropped a task: the watch connection is shut down")
        }
    }

    private fun <T> onCommandThread(task: () -> T): T {
        onCommands.set(true)
        try {
            return task()
        } finally {
            onCommands.set(false)
        }
    }

    companion object {
        /**
         * Longer than the reply timeout, so a request that starts promptly reports its own result.
         * One still queued at the deadline is cancelled unsent; one already running finishes
         * before the next command, so the next reminder read sees its effect.
         */
        const val CALL_TIMEOUT_MS = SppLink.REPLY_TIMEOUT_MS + 5_000L
    }
}
