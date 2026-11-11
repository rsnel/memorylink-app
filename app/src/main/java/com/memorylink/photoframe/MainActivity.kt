package com.memorylink.photoframe

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.LruCache
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random

/**
 * The photoframe. First launch (or long-press on the frame) shows a single
 * field: the HTTPS URL of the server. After that it polls the server and
 * shows whatever it is told to show, fullscreen.
 */
class MainActivity : Activity() {

    private lateinit var frameView: ImageView
    private lateinit var nextFrameView: ImageView
    private lateinit var backdropView: ImageView
    private lateinit var backdropDim: View
    private lateinit var configView: View
    private lateinit var urlInput: EditText
    private lateinit var connectBtn: Button
    private lateinit var statusView: TextView
    private lateinit var splashView: ImageView
    private lateinit var pidView: TextView
    private lateinit var versionView: TextView
    private lateinit var ownerView: TextView
    private lateinit var clockView: View
    private lateinit var clockTime: TextView
    private lateinit var clockDate: TextView

    private var clockEnabled = false
    @Volatile
    private var blurEnabled = false
    private var backdropBitmap: Bitmap? = null
    private var appVersionCode = 0L
    private var appVersionName = "?"
    private val clockHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("EEE d MMM", Locale.getDefault())
    private val clockTicker = object : Runnable {
        override fun run() {
            updateClockText()
            clockHandler.postDelayed(this, 15000L)
        }
    }

    private var client: FrameClient? = null
    private var frameThread: Thread? = null

    @Volatile
    private var running = false

    // Keep at most two decoded frames. The frame only ever displays one image
    // at a time, and this device's heap is too small to hold the whole library
    // (a full 1080x1920 bitmap alone is ~8 MB).
    private val bitmapCache = LruCache<String, Bitmap>(2)
    private var currentUrl: String? = null

    // Downloaded originals are kept on disk so they survive restarts and
    // slideshow intervals do not refetch them from the server.
    private val imageDir by lazy { File(cacheDir, "images").apply { mkdirs() } }
    private val random = Random()
    private var permissionPromptedFor = 0L

    @Volatile
    private var enabledTransitions: List<String> = emptyList()

    private val prefs by lazy { getSharedPreferences(FramePrefs.NAME, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        frameView = findViewById(R.id.frameView)
        nextFrameView = findViewById(R.id.frameViewNext)
        backdropView = findViewById(R.id.backdropView)
        backdropDim = findViewById(R.id.backdropDim)
        configView = findViewById(R.id.configView)
        urlInput = findViewById(R.id.urlInput)
        connectBtn = findViewById(R.id.connectBtn)
        statusView = findViewById(R.id.statusView)
        splashView = findViewById(R.id.splashView)
        pidView = findViewById(R.id.pidView)
        versionView = findViewById(R.id.versionView)
        ownerView = findViewById(R.id.ownerView)
        clockView = findViewById(R.id.clockView)
        clockTime = findViewById(R.id.clockTime)
        clockDate = findViewById(R.id.clockDate)
        pidView.text = "PID: ${Process.myPid()}"
        loadAppVersion()
        versionView.text = appVersionLabel()
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        ownerView.text = if (dpm.isDeviceOwnerApp(packageName)) {
            "Device owner: yes — silent updates"
        } else {
            "Device owner: no"
        }
        updateClockText()
        clockHandler.post(clockTicker)

        // Show the splash briefly, then fade it away over whatever is behind it.
        splashView.postDelayed({
            splashView.animate()
                .alpha(0f)
                .setDuration(450)
                .withEndAction {
                    splashView.visibility = View.GONE
                    // Release the full-screen splash bitmap after the fade.
                    splashView.setImageDrawable(null)
                }
        }, 3200L)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        connectBtn.setOnClickListener {
            val url = urlInput.text.toString().trim().trimEnd('/')
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                setStatus("URL must start with http:// or https://")
                return@setOnClickListener
            }
            prefs.edit().putString(FramePrefs.SERVER_URL, url).apply()
            startFrame(url)
        }

        findViewById<Button>(R.id.settingsBtn).setOnClickListener {
            openSystemSettings()
        }
        findViewById<Button>(R.id.appsBtn).setOnClickListener {
            showAppLauncher()
        }
        findViewById<Button>(R.id.keyboardBtn).setOnClickListener {
            showKeyboardPicker()
        }
        findViewById<Button>(R.id.launcherBtn).setOnClickListener {
            setAsLauncher()
        }
        findViewById<Button>(R.id.resumeBtn).setOnClickListener {
            val saved = prefs.getString(FramePrefs.SERVER_URL, null)
            if (saved.isNullOrBlank()) {
                setStatus("Enter the server URL first")
            } else {
                startFrame(saved)
            }
        }

        // Long-press the frame to reconfigure (change the server URL).
        frameView.setOnLongClickListener {
            stopFrame()
            showConfig()
            true
        }

        val saved = prefs.getString(FramePrefs.SERVER_URL, null)
        if (saved.isNullOrBlank()) showConfig() else startFrame(saved)
    }

    private fun appVersionLabel() = "Version $appVersionName ($appVersionCode)"

    @Suppress("DEPRECATION")
    private fun loadAppVersion() {
        val info = packageManager.getPackageInfo(packageName, 0)
        appVersionName = info.versionName ?: "?"
        appVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
    }

    @Suppress("DEPRECATION")
    private fun installedVersionCode(): Long {
        val info = packageManager.getPackageInfo(packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
    }

    @Suppress("DEPRECATION")
    private fun apkVersionCode(file: File): Long? {
        val info = packageManager.getPackageArchiveInfo(file.path, 0) ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
    }

    private fun openSystemSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        } catch (e: Exception) {
            setStatus("Could not open Settings: ${e.message}")
        }
    }

    private fun showAppLauncher() {
        val launcherIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcherIntent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy {
                it.loadLabel(packageManager).toString().lowercase(Locale.getDefault())
            }
        if (apps.isEmpty()) {
            setStatus("No launchable apps found")
            return
        }
        val labels = apps.map { it.loadLabel(packageManager).toString() }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Open app")
            .setItems(labels) { _, which ->
                val app = apps[which]
                val launch = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(
                        ComponentName(app.activityInfo.packageName, app.activityInfo.name)
                    )
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    startActivity(launch)
                } catch (e: Exception) {
                    setStatus("Could not open app: ${e.message}")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showKeyboardPicker() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            urlInput.requestFocus()
            imm.showInputMethodPicker()
        } catch (e: Exception) {
            setStatus("Could not show keyboards: ${e.message}")
        }
    }

    private fun setAsLauncher() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (dpm.isDeviceOwnerApp(packageName)) {
            val filter = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            try {
                dpm.addPersistentPreferredActivity(
                    ComponentName(this, AdminReceiver::class.java),
                    filter,
                    ComponentName(this, MainActivity::class.java),
                )
                setStatus("MemoryLink is now the Home app")
            } catch (e: Exception) {
                setStatus("Could not set Home app: ${e.message}")
            }
        } else {
            try {
                startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                } catch (e2: Exception) {
                    setStatus("Could not open Home settings: ${e.message}")
                }
            }
        }
    }

    private fun updateClockText() {
        val now = Date()
        clockTime.text = timeFormat.format(now)
        clockDate.text = dateFormat.format(now)
    }

    private fun setClockEnabled(enabled: Boolean) {
        if (clockEnabled == enabled) return
        clockEnabled = enabled
        applyClockVisibility()
    }

    private fun applyClockVisibility() {
        val visible = clockEnabled && frameView.visibility == View.VISIBLE
        clockView.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun updateBackdropVisibility() {
        val visible = blurEnabled && backdropBitmap != null &&
            frameView.visibility == View.VISIBLE
        backdropView.visibility = if (visible) View.VISIBLE else View.GONE
        backdropDim.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun setBackdrop(bitmap: Bitmap?) {
        backdropBitmap = bitmap
        if (bitmap != null) {
            backdropView.setImageBitmap(bitmap)
        } else {
            backdropView.setImageDrawable(null)
        }
        updateBackdropVisibility()
    }

    private fun setBlurEnabled(enabled: Boolean) {
        if (blurEnabled == enabled) {
            updateBackdropVisibility()
            return
        }
        blurEnabled = enabled
        if (!enabled) {
            backdropBitmap = null
            backdropView.setImageDrawable(null)
            updateBackdropVisibility()
            return
        }
        val url = currentUrl
        val source = url?.let { bitmapCache.get(it) }
        if (source == null) {
            updateBackdropVisibility()
            return
        }
        Thread {
            val blurred = blurredBackdrop(source)
            runOnUiThread {
                if (blurEnabled && currentUrl == url) {
                    setBackdrop(blurred)
                }
            }
        }.start()
    }

    /** Tiny, heavily downscaled image used as a soft fill behind the photo. */
    private fun blurredBackdrop(source: Bitmap): Bitmap {
        val maxDim = 64
        val scale = maxDim.toFloat() / maxOf(source.width, source.height)
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(source, width, height, true)
        var src = IntArray(width * height)
        small.getPixels(src, 0, width, 0, 0, width, height)
        var dst = IntArray(src.size)
        repeat(2) {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    var a = 0
                    var r = 0
                    var g = 0
                    var b = 0
                    var n = 0
                    for (dy in -2..2) {
                        val yy = (y + dy).coerceIn(0, height - 1)
                        for (dx in -2..2) {
                            val xx = (x + dx).coerceIn(0, width - 1)
                            val color = src[yy * width + xx]
                            a += Color.alpha(color)
                            r += Color.red(color)
                            g += Color.green(color)
                            b += Color.blue(color)
                            n++
                        }
                    }
                    dst[y * width + x] = Color.argb(a / n, r / n, g / n, b / n)
                }
            }
            val tmp = src
            src = dst
            dst = tmp
        }
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(src, 0, width, 0, 0, width, height)
        return out
    }

    private fun showConfig() {
        resetNextFrame()
        frameView.visibility = View.GONE
        applyClockVisibility()
        updateBackdropVisibility()
        configView.visibility = View.VISIBLE
        statusView.visibility = View.GONE
        hideSystemBars(false)
        val saved = prefs.getString(FramePrefs.SERVER_URL, null)
        if (!saved.isNullOrBlank()) urlInput.setText(saved)
    }

    private fun startFrame(url: String) {
        configView.visibility = View.GONE
        statusView.visibility = View.GONE
        resetNextFrame()
        frameView.visibility = View.VISIBLE
        applyClockVisibility()
        updateBackdropVisibility()
        hideSystemBars(true)
        currentUrl = null
        client = FrameClient(url)
        stopFrame()
        running = true
        frameThread = Thread({ frameLoop() }, "frame-loop").also { it.start() }
    }

    private fun stopFrame() {
        running = false
        frameThread?.interrupt()
        frameThread = null
    }

    private fun frameLoop() {
        var lastVersion = -1L
        var nextSlideAt = 0L
        var images: List<ImageRef> = emptyList()
        var lastUrl: String? = null
        var nextIndex = 0
        var stateVersion = -1L
        var stateUpdateId = 0L
        var stateUpdateStatus = ""
        var stateShowClock = false
        var stateBlur = false
        var pendingState: FrameState? = null
        var waitSeconds = LONGPOLL_SECONDS

        while (running) {
            try {
                val state = pendingState ?: client!!.fetchState()
                pendingState = null
                stateVersion = state.version
                stateUpdateId = state.update?.id ?: 0L
                stateUpdateStatus = state.update?.status ?: ""
                stateShowClock = state.showClock
                stateBlur = state.blurBackground
                runOnUiThread {
                    setClockEnabled(stateShowClock)
                    setBlurEnabled(stateBlur)
                }
                clearStatus()
                try {
                    client!!.reportHeartbeat(appVersionCode, appVersionName)
                } catch (e: Exception) {
                    // Best effort; the web UI will show the frame as offline
                    // if heartbeats stop arriving.
                }
                enabledTransitions = state.transitions
                state.update?.let { maybeInstallUpdate(it) }
                val single = state.single
                if (state.mode == "single" && single != null) {
                    if (showImage(single)) lastUrl = single.url
                } else {
                    if (state.version != lastVersion) {
                        images = state.images
                        lastVersion = state.version
                        lastUrl = null
                        nextIndex = 0
                        nextSlideAt = 0L
                    }
                    if (images.isEmpty()) {
                        showPlaceholder()
                    } else if (SystemClock.elapsedRealtime() >= nextSlideAt) {
                        val next = if (state.random) {
                            randomImage(images, lastUrl)
                        } else {
                            images[nextIndex]
                        }
                        if (showImage(next)) {
                            lastUrl = next.url
                            if (!state.random) nextIndex = (nextIndex + 1) % images.size
                            val interval = state.intervalSeconds.coerceAtLeast(5)
                            nextSlideAt = SystemClock.elapsedRealtime() + interval * 1000L
                        }
                    }
                }

                // Don't let the long poll delay the next slide: cap it at the
                // time remaining until the slideshow should advance.
                waitSeconds = LONGPOLL_SECONDS
                if (state.mode != "single" && images.isNotEmpty() && nextSlideAt > 0L) {
                    val remainingMs = nextSlideAt - SystemClock.elapsedRealtime()
                    waitSeconds = if (remainingMs <= 0L) {
                        // The slide was due but could not be shown; retry soon.
                        (POLL_MS / 1000L).toInt()
                    } else {
                        ((remainingMs + 999L) / 1000L).toInt()
                            .coerceIn(1, LONGPOLL_SECONDS)
                    }
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                setStatus("Server unreachable: ${e.message}")
            }

            try {
                // Long poll: return as soon as the server has new state, so
                // commands like "Show on frame" apply immediately.
                pendingState = client!!.waitForChange(
                    stateVersion,
                    stateUpdateId,
                    stateUpdateStatus,
                    stateShowClock,
                    stateBlur,
                    waitSeconds,
                )
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                setStatus("Server unreachable: ${e.message}")
                try {
                    Thread.sleep(POLL_MS)
                } catch (e2: InterruptedException) {
                    break
                }
            }
        }
    }

    /**
     * Pick a random image from the current set. Every image has the same
     * chance, except the one already on screen, so the slideshow does not
     * show the same picture twice in a row.
     */
    private fun randomImage(images: List<ImageRef>, avoidUrl: String?): ImageRef {
        if (images.size == 1) return images[0]
        val candidates = if (avoidUrl == null) images else images.filter { it.url != avoidUrl }
        return if (candidates.isEmpty()) images[random.nextInt(images.size)]
        else candidates[random.nextInt(candidates.size)]
    }

    private fun showImage(ref: ImageRef): Boolean {
        if (ref.url == currentUrl) return true
        try {
            val bmp = bitmapCache.get(ref.url) ?: loadBitmap(ref).also {
                bitmapCache.put(ref.url, it)
            }
            currentUrl = ref.url
            val backdrop = if (blurEnabled) blurredBackdrop(bmp) else null
            runOnUiThread { transitionTo(bmp, backdrop) }
            try {
                client!!.reportShown(ref.id)
            } catch (e: Exception) {
                // Reporting is best effort; keep showing the picture.
            }
            return true
        } catch (e: Exception) {
            setStatus("Could not load image: ${e.message}")
            return false
        }
    }

    private fun loadBitmap(ref: ImageRef): Bitmap {
        val file = imageFile(ref.url)
        if (file.isFile && file.length() > 0) {
            BitmapFactory.decodeFile(file.path)?.let { return it }
            file.delete() // corrupt cached copy: fetch it again
        }
        client!!.downloadFile(ref.url, file)
        return BitmapFactory.decodeFile(file.path)
            ?: throw java.io.IOException("undecodable image")
    }

    private fun imageFile(url: String): File {
        val name = url.substringBefore('?').substringAfterLast('/')
        val safe = name.isNotEmpty() && name != "." && name != ".." &&
            name.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
        return File(imageDir, if (safe) name else "img-" + url.hashCode())
    }

    /**
     * Download a pushed APK and hand it to the system installer. Android shows
     * a confirmation dialog; the updated app is started by UpdateReceiver.
     */
    private fun maybeInstallUpdate(update: UpdateRef) {
        if (update.status != "pending") return
        if (update.id <= prefs.getLong(FramePrefs.HANDLED_UPDATE, 0L)) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                promptInstallPermission(update.id)
                return
            }
        } else if (!unknownSourcesAllowed()) {
            promptInstallPermission(update.id)
            return
        }

        try {
            setStatus("Downloading update…")
            val file = File(cacheDir, UPDATE_FILE)
            client!!.downloadFile(update.url, file)
            val expected = update.sha256
            if (!expected.isNullOrBlank() && !sha256(file).equals(expected, ignoreCase = true)) {
                file.delete()
                throw java.io.IOException("checksum mismatch")
            }

            val newCode = apkVersionCode(file)
            if (newCode == null) {
                file.delete()
                throw java.io.IOException("could not read APK version")
            }
            val currentCode = installedVersionCode()
            if (newCode <= currentCode) {
                file.delete()
                val message = "APK versionCode $newCode is not newer than installed $currentCode"
                setStatus("Update failed: $message")
                try {
                    client!!.reportUpdateResult(update.id, "failed", message)
                } catch (ignored: Exception) {
                    // Best effort.
                }
                return
            }

            // The update may have been canceled/replaced while downloading.
            val latest = client!!.fetchUpdate()
            if (latest == null || latest.id != update.id || latest.status != "pending") {
                file.delete()
                setStatus("Update is no longer pending")
                return
            }

            prefs.edit()
                .putLong(FramePrefs.HANDLED_UPDATE, update.id)
                .putLong(FramePrefs.INSTALLING_UPDATE, update.id)
                .commit()
            try {
                client!!.reportUpdateResult(update.id, "installing", null)
            } catch (e: Exception) {
                // Result reporting is best effort.
            }
            try {
                installApk(file, update.id)
            } catch (e: Exception) {
                prefs.edit().remove(FramePrefs.INSTALLING_UPDATE).apply()
                val detail = "Install error: ${e.message ?: e.javaClass.simpleName}"
                try {
                    client!!.reportUpdateResult(update.id, "failed", detail)
                } catch (ignored: Exception) {
                    // Best effort.
                }
                throw e
            }
            setStatus("Update ready — confirm the install on the frame")
        } catch (e: Exception) {
            setStatus("Update failed: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun unknownSourcesAllowed(): Boolean =
        Settings.Secure.getInt(
            contentResolver,
            Settings.Secure.INSTALL_NON_MARKET_APPS,
            0,
        ) == 1

    private fun promptInstallPermission(updateId: Long) {
        if (permissionPromptedFor == updateId) return
        permissionPromptedFor = updateId
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setStatus("Allow installs for MemoryLink; the update will continue")
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } else {
            setStatus("Enable Unknown sources in Settings; the update will continue")
            startActivity(
                Intent(Settings.ACTION_SECURITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun installApk(file: File, updateId: Long) {
        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(packageName)
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            session.openWrite("memorylink.apk", 0, file.length()).use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(this, InstallResultReceiver::class.java)
                .putExtra(InstallResultReceiver.EXTRA_UPDATE_ID, updateId)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(this, sessionId, intent, flags)
            session.commit(pending.intentSender)
        } finally {
            session.close()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return BigInteger(1, digest.digest()).toString(16).padStart(64, '0')
    }

    private fun showPlaceholder() {
        if (currentUrl == PLACEHOLDER) return
        currentUrl = PLACEHOLDER
        runOnUiThread {
            resetNextFrame()
            setBackdrop(null)
            frameView.setImageBitmap(null)
        }
    }

    private enum class Transition {
        DISSOLVE, SLIDE_LEFT, SLIDE_RIGHT, SLIDE_UP, SLIDE_DOWN, ZOOM_IN, ZOOM_OUT,
    }

    /** Effects enabled in the web UI; an empty selection means all of them. */
    private fun transitionKinds(): List<Transition> {
        val names = enabledTransitions
        if (names.isEmpty()) return Transition.values().toList()
        val allowed = names.toSet()
        return Transition.values().filter { it.name in allowed }
            .ifEmpty { Transition.values().toList() }
    }

    /** Animate the new bitmap in over the old one with a random effect. */
    private fun transitionTo(bmp: Bitmap, backdrop: Bitmap?) {
        resetNextFrame()
        nextFrameView.setBackgroundColor(if (blurEnabled) Color.TRANSPARENT else Color.BLACK)
        if (blurEnabled) setBackdrop(backdrop) else setBackdrop(null)
        nextFrameView.setImageBitmap(bmp)
        nextFrameView.visibility = View.VISIBLE

        val kinds = transitionKinds()
        val kind = kinds[random.nextInt(kinds.size)]
        val duration = 550L + random.nextInt(450)
        val width = (if (frameView.width > 0) frameView.width
            else resources.displayMetrics.widthPixels).toFloat()
        val height = (if (frameView.height > 0) frameView.height
            else resources.displayMetrics.heightPixels).toFloat()

        val animator = nextFrameView.animate().setDuration(duration)
        when (kind) {
            Transition.DISSOLVE -> {
                nextFrameView.alpha = 0f
                animator.alpha(1f).setInterpolator(AccelerateDecelerateInterpolator())
            }
            Transition.SLIDE_LEFT -> {
                nextFrameView.translationX = width
                animator.translationX(0f).setInterpolator(DecelerateInterpolator())
            }
            Transition.SLIDE_RIGHT -> {
                nextFrameView.translationX = -width
                animator.translationX(0f).setInterpolator(DecelerateInterpolator())
            }
            Transition.SLIDE_UP -> {
                nextFrameView.translationY = height
                animator.translationY(0f).setInterpolator(DecelerateInterpolator())
            }
            Transition.SLIDE_DOWN -> {
                nextFrameView.translationY = -height
                animator.translationY(0f).setInterpolator(DecelerateInterpolator())
            }
            Transition.ZOOM_IN -> {
                nextFrameView.scaleX = 1.18f
                nextFrameView.scaleY = 1.18f
                nextFrameView.alpha = 0f
                animator.scaleX(1f).scaleY(1f).alpha(1f)
                    .setInterpolator(DecelerateInterpolator())
            }
            Transition.ZOOM_OUT -> {
                nextFrameView.scaleX = 0.82f
                nextFrameView.scaleY = 0.82f
                nextFrameView.alpha = 0f
                animator.scaleX(1f).scaleY(1f).alpha(1f)
                    .setInterpolator(DecelerateInterpolator())
            }
        }
        animator.withEndAction { finishTransition(bmp) }
    }

    private fun finishTransition(bmp: Bitmap) {
        frameView.setImageBitmap(bmp)
        resetNextFrame()
    }

    private fun resetNextFrame() {
        nextFrameView.animate().cancel()
        nextFrameView.alpha = 1f
        nextFrameView.translationX = 0f
        nextFrameView.translationY = 0f
        nextFrameView.scaleX = 1f
        nextFrameView.scaleY = 1f
        nextFrameView.setImageDrawable(null)
        nextFrameView.visibility = View.GONE
    }

    private fun setStatus(msg: String) {
        runOnUiThread {
            statusView.text = msg
            statusView.visibility = View.VISIBLE
        }
    }

    private fun clearStatus() {
        runOnUiThread { statusView.visibility = View.GONE }
    }

    private fun hideSystemBars(immersive: Boolean) {
        val decor = window.decorView
        decor.systemUiVisibility = if (immersive) {
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                .or(View.SYSTEM_UI_FLAG_FULLSCREEN)
                .or(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
                .or(View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
                .or(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
                .or(View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        } else {
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && frameView.visibility == View.VISIBLE) hideSystemBars(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        clockHandler.removeCallbacks(clockTicker)
        stopFrame()
    }

    companion object {
        private const val POLL_MS = 6000L
        private const val LONGPOLL_SECONDS = 15
        private const val PLACEHOLDER = "\u0000placeholder"
        private const val UPDATE_FILE = "update.apk"
    }
}
