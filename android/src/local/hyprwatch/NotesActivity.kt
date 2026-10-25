package local.hyprwatch

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Insets
import android.graphics.drawable.ColorDrawable
import android.health.connect.HealthConnectManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Behind the status bar, navigation bar, WebView and permission screens. */
private const val LIGHT_BACKGROUND = 0xffe8eef7.toInt()
private const val DARK_BACKGROUND = 0xff0f1726.toInt()

private const val APP_URL = "http://127.0.0.1:8766/"
private const val EXPORT = 2
private const val LOCATION = 3
private const val PICK_FILE = 4
private const val HEALTH = 5
private const val BACKUP = 6
private const val RESTORE = 7

/** How many times, 300 ms apart, the Activity checks that the service's server is up before it gives up. */
private const val SERVER_TRIES = 15
private const val SERVER_ERROR =
    "Hypr Watch could not start its local server on 127.0.0.1:8766. Another app may be using that port. Close that app, then reopen Hypr Watch."
private const val COARSE_LOCATION = "android.permission.ACCESS_COARSE_LOCATION"
private const val ACTION_REQUEST_LOCATION = "local.hyprwatch.REQUEST_LOCATION"
private const val ACTION_REQUEST_HEALTH = "local.hyprwatch.REQUEST_HEALTH_CONNECT"

/** Health Connect opens this (the manifest's ViewPermissionUsageActivity alias) to show why the app asks. */
private const val ACTION_PERMISSION_USAGE = "android.intent.action.VIEW_PERMISSION_USAGE"
private const val HEALTH_RATIONALE =
    "Hypr Watch copies your watch's steps, distance, heart rate, SpO2, sleep and workouts to Health Connect " +
        "while the Health Connect export is on in Settings. It only writes; it never reads your Health Connect data. " +
        "Nothing is sent off this phone."

class NotesActivity : Activity() {
    private var web: WebView? = null
    private var pageLoaded = false

    /** Set by [loadPage] until that load finishes; history.pushState may report finished pages too. */
    private var freshLoad = false
    private var filePick: ValueCallback<Array<Uri>>? = null
    private var lastInsets: WindowInsets? = null

    /** Recreated after a configuration change, so the launch intent was already handled. */
    private var restored = false

    /** OnBackInvokedCallback on API 33+, registered only while the WebView can go back. */
    private var backCallback: Any? = null

    override fun onCreate(saved: Bundle?) {
        // DayNight makes the WebView's prefers-color-scheme follow the system (API 33+).
        if (Build.VERSION.SDK_INT >= 29) setTheme(android.R.style.Theme_DeviceDefault_DayNight)
        super.onCreate(saved)
        restored = saved != null
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        styleSystemBars()
        if (intent?.action == ACTION_PERMISSION_USAGE) {
            setContentView(messageView(HEALTH_RATIONALE))
            return
        }
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission("android.permission.BLUETOOTH_CONNECT") != PackageManager.PERMISSION_GRANTED) {
            val message = messageView("Hypr Watch needs Nearby devices permission to connect to your paired watch. Your notes stay on this phone.")
            message.textSize = 19f
            setContentView(message)
            requestPermissions(arrayOf("android.permission.BLUETOOTH_CONNECT"), 1)
        } else {
            begin()
        }
    }

    override fun onResume() {
        super.onResume()
        AndroidPhone.takeFix(this)
        val view = web ?: return
        // Android refuses to restart the service after a restore that ended in the background.
        val started = PhoneBackup.startIfStopped(this)
        // Or the restarted service bound its server only after the page gave up and showed the error.
        if (pendingResult != null && !working && (started || view.parent == null)) reloadWhenUp()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_REQUEST_LOCATION && web != null) askLocation()
        if (intent.action == ACTION_REQUEST_HEALTH && web != null) askHealth()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) AndroidPhone.takeFix(this)
            if (pageLoaded) web?.evaluateJavascript("if(typeof window.onLocationPermission==='function')window.onLocationPermission($granted)", null)
            return
        }
        if (requestCode == HEALTH) {
            healthAnswered()
            return
        }
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            begin()
        } else {
            setContentView(messageView("Allow Nearby devices in Android Settings, then reopen Hypr Watch."))
        }
    }

    private fun begin() {
        current = this
        startForegroundService(Intent(this, NotesService::class.java))
        val view = WebView(this)
        web = view
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.allowFileAccess = false
        view.settings.allowContentAccess = false
        view.setBackgroundColor(background())
        view.addJavascriptInterface(
            object {
                @JavascriptInterface
                fun exportNotes(json: String?) {
                    if (NotesService.serving) runOnUiThread { export(json) }
                }

                @JavascriptInterface
                fun requestLocation() {
                    if (NotesService.serving) runOnUiThread { askLocation() }
                }

                @JavascriptInterface
                fun requestHealthConnect() {
                    if (NotesService.serving) runOnUiThread { askHealth() }
                }

                @JavascriptInterface
                fun openHealthConnectSettings() {
                    if (NotesService.serving) runOnUiThread { manageHealth() }
                }

                @JavascriptInterface
                fun backup(passphrase: String?) {
                    if (NotesService.serving) runOnUiThread { pickBackupFile(BACKUP, passphrase) }
                }

                @JavascriptInterface
                fun restore(passphrase: String?) {
                    if (NotesService.serving) runOnUiThread { pickBackupFile(RESTORE, passphrase) }
                }
            },
            "AndroidNotes",
        )
        view.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                filePick?.onReceiveValue(null)
                filePick = callback
                // Android has no MIME type for .bin, so an accept filter would grey the files out.
                val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                return try {
                    startActivityForResult(pick, PICK_FILE)
                    true
                } catch (e: Exception) {
                    filePick = null
                    false
                }
            }
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, r: WebResourceRequest): Boolean = !r.url.toString().startsWith(APP_URL)

            override fun onPageFinished(view: WebView, url: String?) {
                if (url != APP_URL) return
                val fresh = freshLoad
                freshLoad = false
                if (!NotesService.serving) return
                try {
                    val token = NotesService.readOrCreateToken(this@NotesActivity)
                    view.evaluateJavascript(safeAreaScript() + "fitEditor();window.setAccessToken(" + JSONObject.quote(token) + ")", null)
                    // Back never returns to the blank page a restore left, or to the page before a reload.
                    // Only after a load: the entry the editor or a sheet pushed must stay for Back to close it.
                    if (fresh) view.clearHistory()
                    updateBackCallback(view)
                    pageLoaded = true
                    pendingResult?.let { backupResult(it) }
                    pendingResult = null
                } catch (ignored: Exception) {
                }
            }

            // Covers pushState too, so back closes the editor sheet before it leaves the app.
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                updateBackCallback(view)
            }
        }
        view.setOnApplyWindowInsetsListener { v, insets ->
            lastInsets = insets
            // Edge-to-edge windows (Android 15) ignore adjustResize, so lift the WebView above the keyboard.
            val keyboard = keyboardHeight(insets)
            val params = v.layoutParams as? ViewGroup.MarginLayoutParams
            if (params != null && params.bottomMargin != keyboard) {
                params.bottomMargin = keyboard
                v.layoutParams = params
            }
            if (pageLoaded) view.evaluateJavascript(safeAreaScript() + "if(typeof fitEditor==='function')fitEditor()", null)
            // WebView keeps its own handling of the remaining insets; the keyboard is already applied.
            v.onApplyWindowInsets(withoutKeyboard(insets))
        }
        setContentView(view)
        Handler(Looper.getMainLooper()).postDelayed({ loadWhenServing(SERVER_TRIES) }, 600)
        if (intent?.action == ACTION_REQUEST_LOCATION && !restored) askLocation()
        if (intent?.action == ACTION_REQUEST_HEALTH && !restored) askHealth()
    }

    /**
     * Loads the page once this app's own server holds 127.0.0.1:8766. Another app's server there
     * never gets the page's API token or the bridge.
     */
    private fun loadWhenServing(tries: Int) {
        if (isDestroyed) return
        when {
            NotesService.serving -> loadPage()
            // A restore keeps the service stopped for as long as its swap takes, so the tries wait for it.
            tries > 0 || working -> Handler(Looper.getMainLooper()).postDelayed({ loadWhenServing(if (working) tries else tries - 1) }, 300)
            else -> loadPage()
        }
    }

    /**
     * Loads the page while this app's own server holds the port, putting the WebView back on screen
     * after the error text; otherwise shows that text.
     */
    private fun loadPage() {
        val view = web ?: return
        if (!NotesService.serving) return setContentView(messageView(SERVER_ERROR))
        if (view.parent == null) setContentView(view)
        freshLoad = true
        view.loadUrl(APP_URL)
    }

    /**
     * Before a restore stops the service, on the restore's thread: the page leaves, so it stops calling
     * the API with its token while another app could take the port. The reload brings it back.
     */
    private fun closePage() {
        val done = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            current?.let {
                it.pageLoaded = false
                it.web?.loadUrl("about:blank")
            }
            done.countDown()
        }
        done.await(2, TimeUnit.SECONDS)
    }

    /** Coarse location for weather; Android shows nothing when it is granted or permanently denied. */
    private fun askLocation() {
        if (checkSelfPermission(COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(COARSE_LOCATION), LOCATION)
    }

    /** Health Connect write access (Android 14+), shown by Health Connect's own screen; the page hears the answer. */
    private fun askHealth() {
        val missing = missingHealthPermissions(this)
        if (missing.isEmpty()) healthAnswered() else requestPermissions(missing.toTypedArray(), HEALTH)
    }

    /**
     * Health Connect's own screen for this app's access. After two refusals Android stops showing the
     * permission prompt, so this is the way back; it falls back to the prompt where the screen is missing.
     */
    private fun manageHealth() {
        if (Build.VERSION.SDK_INT < 34) return healthAnswered()
        try {
            startActivity(Intent(HealthConnectManager.ACTION_MANAGE_HEALTH_PERMISSIONS).putExtra(Intent.EXTRA_PACKAGE_NAME, packageName))
        } catch (e: Exception) {
            askHealth()
        }
    }

    private fun healthAnswered() {
        val allowed = Build.VERSION.SDK_INT >= 34 && HcType.entries.any { checkSelfPermission(it.permission) == PackageManager.PERMISSION_GRANTED }
        if (pageLoaded) web?.evaluateJavascript("if(typeof window.onHealthConnectPermission==='function')window.onHealthConnectPermission($allowed)", null)
    }

    private fun export(json: String?) {
        try {
            val text = requireNotNull(json)
            JSONObject(text)
            openFileOutput("pending-export.json", MODE_PRIVATE).use { out -> out.write(text.toByteArray(Charsets.UTF_8)) }
            val save = Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "hypr-watch.json")
            startActivityForResult(save, EXPORT)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not export notes", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Asks where to save a backup, or which one to restore, then runs it off the main thread. The page
     * hears the outcome through window.onBackupResult({kind, ok, cancelled, message}).
     */
    private fun pickBackupFile(request: Int, passphrase: String?) {
        val kind = if (request == BACKUP) "backup" else "restore"
        if (operation != null) return backupResult(outcomeOf(kind, false, "A backup or restore is already running."))
        if (passphrase.isNullOrEmpty() || (request == BACKUP && passphrase.length < Backup.MIN_PASSPHRASE)) {
            return backupResult(outcomeOf(kind, false, "Use a passphrase of at least ${Backup.MIN_PASSPHRASE} characters."))
        }
        val pick = if (request == BACKUP) {
            Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, "hypr-watch-${LocalDate.now()}.hwbackup")
        } else {
            // Providers rarely know a type for .hwbackup, so a filter would grey the file out.
            Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
        }
        try {
            operation = passphrase
            startActivityForResult(pick.addCategory(Intent.CATEGORY_OPENABLE), request)
        } catch (e: Exception) {
            operation = null
            backupResult(outcomeOf(kind, false, "Couldn’t open the file picker."))
        }
    }

    private fun runBackup(request: Int, uri: Uri, passphrase: String) {
        val app = applicationContext
        val service = NotesService.instance
        working = true
        var closed = false
        Thread({
            try {
                val outcome = if (request == BACKUP) {
                    try {
                        val stream = try {
                            app.contentResolver.openOutputStream(uri, "wt")
                        } catch (e: Exception) {
                            // Not every provider takes "wt"; the document is new and empty anyway.
                            app.contentResolver.openOutputStream(uri)
                        }
                        (stream ?: throw IOException()).use { out ->
                            PhoneBackup.create(app, BufferedOutputStream(out, 65536), passphrase)
                        }
                        outcomeOf("backup", true, "Backup saved. Keep the passphrase safe: without it the file can’t be opened.")
                    } catch (e: Exception) {
                        Log.i("HyprWatch", "Backup failed: ${e.javaClass.simpleName}: ${e.message}")
                        // Leave no partial file behind.
                        try {
                            DocumentsContract.deleteDocument(app.contentResolver, uri)
                        } catch (ignored: Exception) {
                        }
                        outcomeOf("backup", false, if (e is BackupException) e.message!! else "Couldn’t save the backup. Try again, or save it somewhere else.")
                    }
                } else {
                    try {
                        (app.contentResolver.openInputStream(uri) ?: throw IOException()).use { input ->
                            PhoneBackup.restore(app, BufferedInputStream(input, 65536), passphrase) {
                                closed = true
                                closePage()
                            }
                        }
                        val key = File(app.filesDir, "watch.json").exists()
                        outcomeOf("restore", true, if (key) "Backup restored. Reconnecting to the watch…" else "Backup restored. It had no watch key, so the watch stays disconnected.")
                    } catch (e: Exception) {
                        Log.i("HyprWatch", "Restore failed: ${e.javaClass.simpleName}: ${e.message}")
                        outcomeOf("restore", false, (if (e is BackupException) e.message!! + " " else "Couldn’t restore the backup. ") + "Nothing on this phone was changed.")
                    }
                }
                // A restore that closed the page or stopped the service: the page reloads once the service is back, to read the new data.
                val reload = request == RESTORE && (closed || NotesService.instance !== service)
                if (reload) awaitApi()
                Handler(Looper.getMainLooper()).post {
                    // The Activity that started this may have been recreated meanwhile; the result goes to the live one.
                    val live = current
                    if (live == null || reload) pendingResult = outcome
                    if (live == null) return@post
                    if (reload) live.loadPage() else live.backupResult(outcome)
                }
            } finally {
                // Also when the thread dies of an Error, so no later backup is refused as already running.
                working = false
                operation = null
            }
        }, "backup").start()
    }

    /** Loads the page again once the service answers, for a result kept while it was down. */
    private fun reloadWhenUp() {
        Thread({
            awaitApi()
            runOnUiThread { if (!isDestroyed) loadPage() }
        }, "reload").start()
    }

    /** Up to 10 seconds for the restarted service to bind its own server (not just any listener on the port). */
    private fun awaitApi() {
        val until = System.currentTimeMillis() + 10_000
        while (!NotesService.serving && System.currentTimeMillis() < until) Thread.sleep(200)
    }

    private fun outcomeOf(kind: String, ok: Boolean, message: String, cancelled: Boolean = false): JSONObject =
        JSONObject().put("kind", kind).put("ok", ok).put("cancelled", cancelled).put("message", message)

    private fun backupResult(result: JSONObject) {
        if (!pageLoaded) {
            pendingResult = result
            return
        }
        // The error page shown while the service is down has no handler; the result then waits for the next load.
        web?.evaluateJavascript("typeof window.onBackupResult==='function'&&(window.onBackupResult($result),true)") { if (it != "true") pendingResult = result }
    }

    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request == BACKUP || request == RESTORE) {
            val passphrase = operation
            val uri = data?.data
            if (result != RESULT_OK || uri == null || passphrase == null) {
                operation = null
                // A passphrase lost with the process asks for a new try; a cancel just clears the busy state.
                val message = if (result == RESULT_OK && passphrase == null) "Enter the passphrase again, then choose the file." else ""
                return backupResult(outcomeOf(if (request == BACKUP) "backup" else "restore", false, message, cancelled = message.isEmpty()))
            }
            return runBackup(request, uri, passphrase)
        }
        if (request == PICK_FILE) {
            val uri = data?.data
            filePick?.onReceiveValue(if (result == RESULT_OK && uri != null) arrayOf(uri) else null)
            filePick = null
            return
        }
        if (request != EXPORT) return
        try {
            val target = data?.data
            if (result == RESULT_OK && target != null) {
                openFileInput("pending-export.json").use { input ->
                    (contentResolver.openOutputStream(target) ?: throw IOException()).use { out -> input.copyTo(out, 8192) }
                }
                Toast.makeText(this, "Notes exported", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Could not save export", Toast.LENGTH_LONG).show()
        } finally {
            deleteFile("pending-export.json")
        }
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() {
        // API 33+ goes through backCallback instead (enableOnBackInvokedCallback in the manifest).
        val view = web
        if (Build.VERSION.SDK_INT < 33 && view != null && view.canGoBack()) view.goBack() else super.onBackPressed()
    }

    private fun updateBackCallback(view: WebView) {
        if (Build.VERSION.SDK_INT < 33) return
        val callback = backCallback as OnBackInvokedCallback?
            ?: OnBackInvokedCallback { if (view.canGoBack()) view.goBack() }.also { backCallback = it }
        if (view.canGoBack()) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        } else {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
        }
    }

    companion object {
        private fun missingHealthPermissions(context: Context): List<String> =
            if (Build.VERSION.SDK_INT < 34) emptyList() else HcType.entries.map { it.permission }.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

        /** For POST /api/health/connect/permission: brings the Activity up to ask; false when nothing is missing. */
        fun requestHealthConnect(context: Context): Boolean {
            if (missingHealthPermissions(context).isEmpty()) return false
            val open = Intent(context, NotesActivity::class.java)
                .setAction(ACTION_REQUEST_HEALTH)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            context.startActivity(open)
            return true
        }

        /**
         * The passphrase from the page while the file picker is open and its operation runs; static so
         * the Activity being recreated meanwhile does not lose it. Null when nothing runs.
         */
        @Volatile private var operation: String? = null

        /** True while a backup or restore thread runs. */
        @Volatile private var working = false

        /** The live Activity, for a result that ends after the one that started it was recreated. Main thread only. */
        private var current: NotesActivity? = null

        /** A backup or restore result for the next page load: after a restore, or with no Activity to take it. Main thread only. */
        private var pendingResult: JSONObject? = null

        /** For POST /api/weather/permission: brings the Activity up to ask; false when already granted. */
        fun requestLocation(context: Context): Boolean {
            if (context.checkSelfPermission(COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) return false
            val open = Intent(context, NotesActivity::class.java)
                .setAction(ACTION_REQUEST_LOCATION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            context.startActivity(open)
            return true
        }
    }

    override fun onDestroy() {
        if (current === this) current = null
        // Closed with the file picker open (the task swiped away): no result will come back.
        if (isFinishing && !working) operation = null
        web?.destroy()
        super.onDestroy()
    }

    private fun isDark(): Boolean =
        Build.VERSION.SDK_INT >= 29 &&
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun background(): Int = if (isDark()) DARK_BACKGROUND else LIGHT_BACKGROUND

    @Suppress("DEPRECATION")
    private fun styleSystemBars() {
        val light = !isDark()
        // Shows below the WebView while it is lifted above the keyboard.
        window.setBackgroundDrawable(ColorDrawable(background()))
        if (Build.VERSION.SDK_INT < 35) {
            // Android 15 draws edge-to-edge and ignores bar colors; content behind the bars shows instead.
            window.statusBarColor = background()
            window.navigationBarColor = background()
        }
        // Installs the decor view first: PhoneWindow.getInsetsController() needs it.
        val decor = window.decorView
        if (Build.VERSION.SDK_INT >= 30) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (light) mask else 0, mask)
        } else {
            decor.systemUiVisibility =
                if (light) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
        }
    }

    /** A permission screen that follows the theme and stays clear of the system bars. */
    private fun messageView(text: String): TextView {
        val message = TextView(this)
        message.text = text
        message.setBackgroundColor(background())
        message.setPadding(32, 48, 32, 32)
        message.setOnApplyWindowInsetsListener { v, insets ->
            val bars = systemBars(insets)
            v.setPadding(32 + bars[0], 48 + bars[1], 32 + bars[2], 32 + bars[3])
            insets
        }
        return message
    }

    /** Left, top, right, bottom system bar insets in physical pixels. */
    @Suppress("DEPRECATION")
    private fun systemBars(insets: WindowInsets): IntArray =
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            intArrayOf(bars.left, bars.top, bars.right, bars.bottom)
        } else {
            intArrayOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        }

    private fun keyboardHeight(insets: WindowInsets): Int =
        if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.ime()).bottom else 0

    private fun withoutKeyboard(insets: WindowInsets): WindowInsets =
        if (Build.VERSION.SDK_INT >= 30 && keyboardHeight(insets) > 0) {
            WindowInsets.Builder(insets).setInsets(WindowInsets.Type.ime(), Insets.NONE).build()
        } else {
            insets
        }

    /**
     * Sets --safe-top (status bar) and --safe-bottom (navigation bar) in CSS px, from the insets
     * dispatched to the WebView: the bars it actually sits behind (all of them on Android 15).
     * While the keyboard is open the WebView ends above it, so --safe-bottom drops to 0.
     */
    @Suppress("DEPRECATION")
    private fun safeAreaScript(): String {
        val insets = lastInsets ?: window.decorView.rootWindowInsets
        var top = 0
        var bottom = 0
        if (insets != null) {
            if (Build.VERSION.SDK_INT >= 30) {
                top = insets.getInsets(WindowInsets.Type.statusBars()).top
                bottom = maxOf(insets.getInsets(WindowInsets.Type.navigationBars()).bottom - keyboardHeight(insets), 0)
            } else {
                top = insets.systemWindowInsetTop
                bottom = insets.systemWindowInsetBottom
            }
        }
        val density = resources.displayMetrics.density
        val safeTop = top / density
        val safeBottom = bottom / density
        return "document.documentElement.style.setProperty('--safe-top','${safeTop}px');" +
            "document.documentElement.style.setProperty('--safe-bottom','${safeBottom}px');"
    }
}
