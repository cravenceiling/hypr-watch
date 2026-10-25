package local.hyprwatch

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Turns posted and removed phone notifications into [NotificationEvent]s for [NotificationBridge],
 * and carries out the watch's [WatchAction]s. Runs only while the user grants notification access.
 */
class WristNotificationListener : NotificationListenerService() {
    private val main = Handler(Looper.getMainLooper())

    override fun onListenerConnected() {
        NotificationBridge.actions = { a -> main.post { guard("watch action") { act(a) } } }
        val repost: () -> Unit = { main.post { guard("call repost") { ringingCalls().forEach(::onNotificationPosted) } } }
        NotificationBridge.repost = repost
        // The watch may have connected first; while it is not connected the session sends nothing.
        repost()
    }

    override fun onListenerDisconnected() {
        unbind()
    }

    override fun onDestroy() {
        unbind()
        super.onDestroy()
    }

    private fun unbind() {
        NotificationBridge.actions = null
        NotificationBridge.repost = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        guard("notification posted") {
            val now = System.currentTimeMillis()
            if (sbn.packageName != packageName) recent.add(sbn.packageName, now, sbn.notification.extras?.let(::appInfo))
            val filter = filter(this)
            if (!filter.wants(sbn.packageName, sbn.notification.category == Notification.CATEGORY_CALL)) return@guard
            val n = model(sbn)
            val flags = sbn.notification.flags
            val f = NotificationFlags(
                ongoing = flags and Notification.FLAG_ONGOING_EVENT != 0,
                foregroundService = flags and Notification.FLAG_FOREGROUND_SERVICE != 0,
                groupSummary = flags and Notification.FLAG_GROUP_SUMMARY != 0,
                localOnly = flags and Notification.FLAG_LOCAL_ONLY != 0,
                alertOnce = flags and Notification.FLAG_ONLY_ALERT_ONCE != 0,
            )
            if (!filter.accept(n, f, now)) return@guard
            if (n.isCall && !ringing(sbn.notification)) {
                // Answered: the codec turns this into the watch's end-call dismiss.
                NotificationBridge.post(NotificationEvent.Removed(n.key))
            } else {
                NotificationBridge.post(NotificationEvent.Posted(n))
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        guard("notification removed") { NotificationBridge.post(NotificationEvent.Removed(sbn.key)) }
    }

    private fun model(sbn: StatusBarNotification): PhoneNotification {
        val n = sbn.notification
        val e = n.extras ?: Bundle.EMPTY
        val title = e.getCharSequence(Notification.EXTRA_TITLE_BIG) ?: e.getCharSequence(Notification.EXTRA_TITLE)
        val lines = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n")
        val body = e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT) ?: lines
        return PhoneNotification(
            key = sbn.key,
            pkg = sbn.packageName,
            appName = appName(sbn.packageName, e),
            title = title?.toString() ?: "",
            body = body?.toString() ?: "",
            whenMs = if (n.`when` > 0) n.`when` else sbn.postTime,
            isCall = n.category == Notification.CATEGORY_CALL,
            postTime = sbn.postTime,
        )
    }

    private fun appName(pkg: String, extras: Bundle): String = label(this, pkg, appInfo(extras))

    /** CallStyle (API 31+) says incoming vs ongoing; older dialers only set a full-screen intent while ringing. */
    private fun ringing(n: Notification): Boolean =
        when (n.extras?.getInt(EXTRA_CALL_TYPE, 0) ?: 0) {
            CALL_TYPE_INCOMING, CALL_TYPE_SCREENING -> true
            CALL_TYPE_ONGOING -> false
            else -> n.fullScreenIntent != null || n.flags and Notification.FLAG_ONGOING_EVENT == 0
        }

    private fun act(a: WatchAction) {
        when (a) {
            is WatchAction.Dismiss -> a.keys.forEach { cancelNotification(it) }
            is WatchAction.Open -> {
                val sbn = find(a.key) ?: return log("Notification to open is gone")
                val open = sbn.notification.contentIntent ?: return log("Notification has nothing to open")
                // Android may still block the launch without telling, so the notification stays.
                send(open, "open notification")
            }
            is WatchAction.RejectCall -> {
                val call = a.call ?: return log("No call to reject")
                // Only the exact ringing post the watch showed: never an answered call's hang-up or another call.
                val sbn = find(call.key)
                if (sbn == null || sbn.postTime != call.postTime || !ringing(sbn.notification)) return log("Call changed before the reject; left alone")
                val decline = declineIntent(sbn.notification) ?: return log("Call notification has no decline action")
                send(decline, "reject call")
            }
            WatchAction.IgnoreCall -> if (!ignoreLogged) {
                ignoreLogged = true
                Log.d(TAG, "Ignore call is not supported: muting the ringer needs the default dialer role")
            }
        }
    }

    private fun find(key: String): StatusBarNotification? =
        try {
            getActiveNotifications(arrayOf(key))?.firstOrNull()
        } catch (e: SecurityException) {
            null
        }

    private fun ringingCalls(): List<StatusBarNotification> =
        try {
            activeNotifications?.filter { it.notification.category == Notification.CATEGORY_CALL && ringing(it.notification) } ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }

    @Suppress("DEPRECATION")
    private fun declineIntent(n: Notification): PendingIntent? {
        val e = n.extras
        if (e != null) {
            // Notification.CallStyle stores these under hidden but stable extra keys.
            (e.getParcelable<PendingIntent>(EXTRA_DECLINE_INTENT) ?: e.getParcelable<PendingIntent>(EXTRA_HANG_UP_INTENT))?.let { return it }
        }
        return n.actions?.firstOrNull { DECLINE.containsMatchIn(it.title?.toString() ?: "") }?.actionIntent
    }

    /**
     * Grants the intent this app's background activity start privilege, which Android 14+ requires
     * to open an activity from the background. Ported from Gadgetbridge's
     * NotificationListener.sendAllowingBackgroundActivityStart, AGPL-3.0-or-later.
     */
    @Suppress("DEPRECATION")
    private fun send(p: PendingIntent, what: String) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                p.send(ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle())
            } else {
                p.send()
            }
            log("Sent intent to $what")
        } catch (e: PendingIntent.CanceledException) {
            log("Could not $what: intent was cancelled")
        }
    }

    private fun guard(what: String, f: () -> Unit) {
        try {
            f()
        } catch (e: Exception) {
            Log.w(TAG, "$what failed", e)
        }
    }

    private fun log(message: String) {
        Log.i(TAG, message)
    }

    /** [NotificationsFeature]'s routes on Android. */
    class RouteSettings(private val context: Context) : NotificationsFeature.ListenerSettings {
        override fun get(): JSONObject = settings(context)

        override fun put(input: JSONObject): JSONObject {
            saveFilter(context, NotificationFilter.fromApi(context.packageName, input))
            return settings(context)
        }

        override fun openAccess(): Boolean =
            try {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (e: ActivityNotFoundException) {
                false
            }
    }

    companion object {
        private const val TAG = "HyprWatch"
        @Volatile private var ignoreLogged = false
        private val recent = RecentApps<ApplicationInfo?>()
        private const val FILTER_FILE = "notification-filter.json"
        private const val EXTRA_APP_INFO = "android.appInfo"
        private const val EXTRA_CALL_TYPE = "android.callType"
        private const val EXTRA_DECLINE_INTENT = "android.declineIntent"
        private const val EXTRA_HANG_UP_INTENT = "android.hangUpIntent"
        private const val CALL_TYPE_INCOMING = 1
        private const val CALL_TYPE_ONGOING = 2
        private const val CALL_TYPE_SCREENING = 3
        private val DECLINE = Regex("decline|reject|hang ?up|end call", RegexOption.IGNORE_CASE)

        @Volatile private var current: NotificationFilter? = null

        private fun filterFile(context: Context): File = File(context.filesDir, FILTER_FILE)

        /** The live filter, loaded once from app-private storage. */
        @Synchronized
        fun filter(context: Context): NotificationFilter =
            current ?: run {
                val file = filterFile(context)
                NotificationFilter.fromJson(context.packageName, if (file.exists()) file.readText() else null).also { current = it }
            }

        /** Runs a restore's [swap] with no filter save in between; the next [filter] call reads the file again. */
        @Synchronized
        fun replacingFilter(swap: () -> Unit) {
            try {
                swap()
            } finally {
                current = null
            }
        }

        /** Persists [filter] atomically and makes it live. */
        @Synchronized
        private fun saveFilter(context: Context, filter: NotificationFilter) {
            // A request still running when a restore stopped the service must not overwrite the restored file.
            if (PhoneBackup.restoring) throw IOException("A restore is running")
            val file = filterFile(context)
            val tmp = File(file.path + ".tmp")
            tmp.writeText(filter.toJson().toString())
            if (!tmp.renameTo(file)) throw IOException("Could not save notification filter")
            current = filter
        }

        fun hasAccess(context: Context): Boolean {
            val component = ComponentName(context, WristNotificationListener::class.java)
            if (Build.VERSION.SDK_INT >= 27) {
                return context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component)
            }
            return Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                ?.split(':')?.any { ComponentName.unflattenFromString(it) == component } == true
        }

        /** GET /api/notifications: access, the filter with app names, and apps that posted lately (package and label only). */
        private fun settings(context: Context): JSONObject {
            val seen = recent.list()
            val apps = JSONArray()
            for (e in seen) {
                apps.put(JSONObject().put("package", e.pkg).put("app_name", label(context, e.pkg, e.info)).put("last_seen_ms", e.lastSeen))
            }
            val info = seen.associate { it.pkg to it.info }
            return filter(context).toApi { pkg -> label(context, pkg, info[pkg]) }.put("access", hasAccess(context)).put("recent", apps)
        }

        @Suppress("DEPRECATION")
        private fun appInfo(extras: Bundle): ApplicationInfo? = extras.getParcelable(EXTRA_APP_INFO)

        /** The ApplicationInfo the latest notification of [pkg] carried, for apps package visibility hides. */
        fun recentAppInfo(pkg: String): ApplicationInfo? = recent.list().firstOrNull { it.pkg == pkg }?.info

        /** Package visibility can hide the poster on API 30+; [fallback] is the ApplicationInfo its notification carried. */
        private fun label(context: Context, pkg: String, fallback: ApplicationInfo?): String {
            val pm = context.packageManager
            val info = try {
                pm.getApplicationInfo(pkg, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                fallback
            }
            return info?.loadLabel(pm)?.toString() ?: pkg
        }
    }
}
