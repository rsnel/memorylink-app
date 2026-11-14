package com.memorylink.photoframe

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.animation.ValueAnimator
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.LruCache
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.ceil
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

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
    private lateinit var browseView: BrowseView
    private lateinit var browseLabel: TextView
    private lateinit var browseBack: ImageButton
    private lateinit var browseTagBar: HorizontalScrollView
    private lateinit var browseTagRow: LinearLayout

    private var clockEnabled = false
    @Volatile
    private var blurEnabled = false

    /** fill, fit, blur or pan (see DISPLAY_MODES). UI thread. */
    private var displayMode = "fit"
    private var panAnimator: ValueAnimator? = null

    /** One pan across the photo takes this long (the slideshow interval). */
    @Volatile
    private var panSeconds = 30

    /** The splash artwork in use; chosen from how the tablet is held. */
    private var splashLandscape = false
    private var splashShownAt = 0L
    private var backdropBitmap: Bitmap? = null
    private var appVersionCode = 0L
    private var appVersionName = "?"

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var bluetoothScanning = false
    private var bluetoothPermissionRequested = false
    private var bluetoothEnableAsked = false
    private val bluetoothResults = LinkedHashMap<String, BluetoothSeen>()
    private val bluetoothHandler = Handler(Looper.getMainLooper())
    private val bluetoothScanRunnable = Runnable { beginBluetoothScan() }
    private val bluetoothStopRunnable = Runnable { finishBluetoothScan() }
    private val bluetoothScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            recordBluetoothResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { recordBluetoothResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            bluetoothScanning = false
            Log.w("MemoryLink", "Bluetooth scan failed: $errorCode")
            bluetoothHandler.removeCallbacks(bluetoothStopRunnable)
            bluetoothHandler.postDelayed(bluetoothScanRunnable, BLUETOOTH_INTERVAL_MS)
        }
    }
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

    @Volatile
    private var currentUrl: String? = null

    /** The photo actually on screen (UI thread only). */
    private var screenUrl: String? = null

    // Downloaded originals are kept on disk so they survive restarts and
    // slideshow intervals do not refetch them from the server.
    private val imageDir by lazy { File(cacheDir, "images").apply { mkdirs() } }
    private val random = Random()
    private var permissionPromptedFor = 0L

    @Volatile
    private var enabledTransitions: List<String> = emptyList()

    private val prefs by lazy { getSharedPreferences(FramePrefs.NAME, MODE_PRIVATE) }

    /** Stable identity of this installation; generated once and kept over updates. */
    private val frameId: String by lazy { FramePrefs.ensureFrameId(prefs) }

    // ---- Browsing ("offline mode") ------------------------------------------
    // Swiping or pinching on the frame takes it out of the slideshow. The
    // slideshow comes back after BROWSE_TIMEOUT_MS without a touch, but that
    // time only runs while the server can be reached: away from home the frame
    // stays in browsing mode for as long as it is used.

    @Volatile
    private var browsing = false

    @Volatile
    private var browseDeadline = 0L

    /** Set when browsing ends, so the frame loop shows the slideshow again at once. */
    @Volatile
    private var resumeSlideshow = false

    /** Last successful exchange with the server (elapsedRealtime). */
    @Volatile
    private var lastContactAt = 0L

    // What the frame knows, kept on disk so it is also there without a server:
    // the slideshow's selection and every photo in gallery order.
    @Volatile
    private var knownPlaylist: List<ImageRef> = emptyList()

    @Volatile
    private var knownLibrary: List<ImageRef> = emptyList()

    // UI thread only.
    private var browseAll: List<ImageRef> = emptyList()
    private var browseItems: List<ImageRef> = emptyList()
    private var browseTag: String? = null
    private var browseIndex = -1
    private var openedFromGrid = false
    private var gridColumns = BrowseView.MIN_COLUMNS
    private var browseRequest = 0

    private val browseExecutor = Executors.newSingleThreadExecutor()
    private val browseHandler = Handler(Looper.getMainLooper())
    private val browseTimeoutCheck = Runnable { checkBrowseTimeout() }
    private val hideBrowseLabel = Runnable {
        browseLabel.animate().alpha(0f).setDuration(400)
            .withEndAction { browseLabel.visibility = View.GONE }
    }

    // Thumbnails for the grid, decoded small from the photos on disk.
    private val thumbCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(32L * 1024 * 1024).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val thumbLoading = HashSet<String>()
    private val thumbFailed = HashSet<String>()
    private val thumbExecutor = Executors.newFixedThreadPool(2)

    // Downloads of the same file must not overlap (they share a .part file).
    private val downloadLock = Any()
    private val prefetching = AtomicBoolean(false)

    @Volatile
    private var prefetchAgain = false

    @Volatile
    private var lastPrefetchAt = 0L

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
        browseView = findViewById(R.id.browseView)
        browseLabel = findViewById(R.id.browseLabel)
        browseBack = findViewById(R.id.browseBack)
        browseTagBar = findViewById(R.id.browseTagBar)
        browseTagRow = findViewById(R.id.browseTagRow)
        browseBack.setOnClickListener {
            touchBrowse()
            openGrid()
        }
        browseView.listener = browseListener
        browseView.thumbs = BrowseView.ThumbSource { ref, width, height -> thumbFor(ref, width, height) }
        pidView.text = "PID: ${Process.myPid()}"
        loadAppVersion()
        versionView.text = appVersionLabel() + "\nFrame ID: " + frameId
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        ownerView.text = if (dpm.isDeviceOwnerApp(packageName)) {
            "Device owner: yes — silent updates"
        } else {
            "Device owner: no"
        }
        updateClockText()
        clockHandler.post(clockTicker)

        // Show the artwork that matches how the tablet is held right now. The
        // app never asks for an orientation, so the splash doesn't rotate the
        // screen. Show it briefly, then fade it away over whatever is behind it.
        splashLandscape = isLandscapeNow()
        splashView.setImageResource(splashResource())
        splashShownAt = SystemClock.elapsedRealtime()
        splashView.postDelayed({ hideSplashWhenReady() }, SPLASH_MIN_MS)

        personalizeSplash()

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

        // Long-pressing the frame to reconfigure is handled by browseListener,
        // because the browse layer on top of the photo receives the touches.

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

    private fun isLandscapeNow(): Boolean {
        val metrics = resources.displayMetrics
        return metrics.widthPixels > metrics.heightPixels
    }

    private fun splashResource(): Int =
        if (splashLandscape) R.drawable.splash_landscape else R.drawable.splash_portrait

    /**
     * Fade the splash out. While the tablet is still looking for a network,
     * keep it up to SPLASH_WIFI_EXTRA_MS longer.
     */
    private fun hideSplashWhenReady() {
        if (splashView.visibility != View.VISIBLE) return
        val waited = SystemClock.elapsedRealtime() - splashShownAt
        if (!networkAvailable() && waited < SPLASH_MIN_MS + SPLASH_WIFI_EXTRA_MS) {
            splashView.postDelayed({ hideSplashWhenReady() }, 500L)
            return
        }
        splashView.animate()
            .alpha(0f)
            .setDuration(450)
            .withEndAction {
                splashView.visibility = View.GONE
                // Release the full-screen splash bitmap after the fade.
                splashView.setImageDrawable(null)
            }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Turned while the splash is up: switch to the matching artwork.
        val landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (splashView.visibility == View.VISIBLE && landscape != splashLandscape) {
            splashLandscape = landscape
            splashView.setImageResource(splashResource())
            personalizeSplash()
        }
        // The pan depends on the screen's shape.
        frameView.post { refreshPan() }
    }

    private fun personalizeSplash() {
        val url = prefs.getString(FramePrefs.LAST_SHOWN_URL, null) ?: return
        val file = imageFile(url)
        if (!file.isFile) return
        val landscape = splashLandscape
        Thread {
            val composed = composeSplash(file, landscape)
            if (composed != null) {
                runOnUiThread {
                    if (splashView.visibility == View.VISIBLE && landscape == splashLandscape) {
                        splashView.setImageBitmap(composed)
                    } else {
                        composed.recycle()
                    }
                }
            }
        }.start()
    }

    /**
     * The last photo, drawn into the picture frame on the splash artwork.
     *
     * The photo is laid out on a flat canvas with the opening's proportions
     * and that canvas is warped onto the opening (the frame stands at a slight
     * angle), then cut to the opening with a smooth edge. The picture is never
     * stretched: it fills the opening when only a little has to be trimmed for
     * that, and is otherwise shown whole on a soft blurred copy of itself.
     */
    private fun composeSplash(photoFile: File, landscape: Boolean): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        val artwork = BitmapFactory.decodeResource(
            resources,
            if (landscape) R.drawable.splash_landscape else R.drawable.splash_portrait,
            options,
        ) ?: return null
        val width = artwork.width
        val height = artwork.height
        val opening = if (landscape) SPLASH_OPENING_LANDSCAPE else SPLASH_OPENING_PORTRAIT
        val quad = FloatArray(8) { i -> opening[i] * (if (i % 2 == 0) width else height) }
        fun side(a: Int, b: Int) = Math.hypot(
            (quad[2 * a] - quad[2 * b]).toDouble(),
            (quad[2 * a + 1] - quad[2 * b + 1]).toDouble(),
        ).toFloat()
        val openWidth = (side(0, 1) + side(3, 2)) / 2f
        val openHeight = (side(0, 3) + side(1, 2)) / 2f

        // Flat canvas with the opening's proportions, oversampled a little so
        // the warp stays sharp.
        val canvasW = Math.round(openWidth * SPLASH_OVERSAMPLE)
        val canvasH = Math.round(openHeight * SPLASH_OVERSAMPLE)
        val photo = decodeSampled(photoFile, canvasW, canvasH) ?: run {
            artwork.recycle()
            return null
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val content = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
        val contentCanvas = Canvas(content)
        contentCanvas.drawColor(Color.BLACK)

        val photoAspect = photo.width.toFloat() / photo.height
        val openAspect = canvasW.toFloat() / canvasH
        // Share of the photo that survives when it is cropped to the opening.
        val kept = minOf(photoAspect / openAspect, openAspect / photoAspect)
        val fill = kept >= SPLASH_FILL_MIN_KEPT
        if (!fill) {
            val soft = blurredBackdrop(photo)
            contentCanvas.drawBitmap(soft, centered(soft, canvasW, canvasH, true), paint)
            soft.recycle()
            contentCanvas.drawColor(0x66000000)
        }
        contentCanvas.drawBitmap(photo, centered(photo, canvasW, canvasH, fill), paint)
        photo.recycle()

        val warp = Matrix()
        warp.setPolyToPoly(
            floatArrayOf(
                0f, 0f,
                canvasW.toFloat(), 0f,
                canvasW.toFloat(), canvasH.toFloat(),
                0f, canvasH.toFloat(),
            ),
            0, quad, 0, 4,
        )
        // Warp the photo onto a full-size layer, then paint that layer onto
        // the artwork through the opening's outline (anti-aliased).
        val layer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(layer).drawBitmap(content, warp, paint)
        content.recycle()
        val outline = Path().apply {
            moveTo(quad[0], quad[1])
            lineTo(quad[2], quad[3])
            lineTo(quad[4], quad[5])
            lineTo(quad[6], quad[7])
            close()
        }
        val through = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = android.graphics.BitmapShader(
                layer,
                android.graphics.Shader.TileMode.CLAMP,
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
        Canvas(artwork).drawPath(outline, through)
        layer.recycle()
        return artwork
    }

    /** Matrix that centres [bitmap] in a w x h box, covering it or fitting inside. */
    private fun centered(bitmap: Bitmap, w: Int, h: Int, cover: Boolean): Matrix {
        val sx = w.toFloat() / bitmap.width
        val sy = h.toFloat() / bitmap.height
        val scale = if (cover) maxOf(sx, sy) else minOf(sx, sy)
        return Matrix().apply {
            setScale(scale, scale)
            postTranslate(
                (w - bitmap.width * scale) / 2f,
                (h - bitmap.height * scale) / 2f,
            )
        }
    }

    /** Decode an image file at reduced size, but no smaller than w x h. */
    private fun decodeSampled(file: File, w: Int, h: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sample
        }
        return BitmapFactory.decodeFile(file.path, options)
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
        val visible = clockEnabled && frameView.visibility == View.VISIBLE &&
            !browseView.gridVisible
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

    /**
     * How photos that don't have the screen's shape are shown: zoomed in to
     * fill (fill), whole on black (fit), whole on a blurred copy (blur), or
     * zoomed in and slowly panned from one edge to the other (pan).
     */
    private fun setDisplayMode(mode: String) {
        if (mode != displayMode) {
            displayMode = mode
            val type = when (mode) {
                "fill" -> ImageView.ScaleType.CENTER_CROP
                "pan" -> ImageView.ScaleType.MATRIX
                else -> ImageView.ScaleType.FIT_CENTER
            }
            frameView.scaleType = type
            nextFrameView.scaleType = type
            refreshPan()
        }
        setBlurEnabled(mode == "blur")
    }

    /** Image matrix that covers [view] with its photo, [progress] of the way across. */
    private fun panMatrix(view: ImageView, progress: Float): Matrix? {
        val drawable = view.drawable ?: return null
        val bw = drawable.intrinsicWidth.toFloat()
        val bh = drawable.intrinsicHeight.toFloat()
        if (bw <= 0f || bh <= 0f) return null
        val vw = (if (view.width > 0) view.width else resources.displayMetrics.widthPixels).toFloat()
        val vh = (if (view.height > 0) view.height else resources.displayMetrics.heightPixels).toFloat()
        val scale = maxOf(vw / bw, vh / bh)
        // One of these is zero: the photo overflows the screen in one direction.
        val overflowX = vw - bw * scale
        val overflowY = vh - bh * scale
        return Matrix().apply {
            setScale(scale, scale)
            postTranslate(overflowX * progress, overflowY * progress)
        }
    }

    /** (Re)start the slow pan of the photo on screen, in pan mode. */
    private fun refreshPan() {
        panAnimator?.cancel()
        panAnimator = null
        if (displayMode != "pan") return
        val start = panMatrix(frameView, 0f) ?: return
        frameView.imageMatrix = start
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = panSeconds.coerceAtLeast(10) * 1000L
        animator.repeatMode = ValueAnimator.REVERSE
        animator.repeatCount = ValueAnimator.INFINITE
        animator.interpolator = AccelerateDecelerateInterpolator()
        animator.addUpdateListener { a ->
            panMatrix(frameView, a.animatedValue as Float)?.let { frameView.imageMatrix = it }
        }
        animator.start()
        panAnimator = animator
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
        endBrowse()
        browseView.visibility = View.GONE
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
        browseView.visibility = View.VISIBLE
        applyClockVisibility()
        updateBackdropVisibility()
        hideSystemBars(true)
        currentUrl = null
        loadLists()
        showLastPhoto()
        client = FrameClient(url, frameId)
        stopFrame()
        running = true
        frameThread = Thread({ frameLoop() }, "frame-loop").also { it.start() }
        startBluetoothScanning()
    }

    private fun stopFrame() {
        running = false
        frameThread?.interrupt()
        frameThread = null
        stopBluetoothScanning()
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
        var stateDisplayMode = "fit"
        var stateRestartId = 0L
        var lastRestartId = prefs.getLong(FramePrefs.HANDLED_RESTART, 0L)
        var pendingState: FrameState? = null
        var waitSeconds = LONGPOLL_SECONDS
        var failingSince = 0L
        var listsVersion = -1L
        var seenState = false
        var lastSingleId: Long? = null

        while (running) {
            if (!networkAvailable()) {
                failingSince = 0L
                setStatus("Waiting for network…")
                if (!sleepInterruptible(NETWORK_RETRY_MS)) break
                continue
            }

            try {
                val state = pendingState ?: client!!.fetchState()
                pendingState = null
                failingSince = 0L
                lastContactAt = SystemClock.elapsedRealtime()
                stateVersion = state.version
                stateUpdateId = state.update?.id ?: 0L
                stateUpdateStatus = state.update?.status ?: ""
                stateShowClock = state.showClock
                stateBlur = state.blurBackground
                stateDisplayMode = state.displayMode
                panSeconds = state.intervalSeconds
                stateRestartId = state.restartId
                if (state.restartId > lastRestartId) {
                    prefs.edit()
                        .putLong(FramePrefs.HANDLED_RESTART, state.restartId)
                        .commit()
                    lastRestartId = state.restartId
                    runOnUiThread {
                        setStatus("Restarting frame…")
                        recreate()
                    }
                    return
                }
                runOnUiThread {
                    setClockEnabled(stateShowClock)
                    setDisplayMode(stateDisplayMode)
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
                if (state.version != listsVersion) {
                    listsVersion = state.version
                    updateKnownLists(state)
                } else if (SystemClock.elapsedRealtime() - lastPrefetchAt > PREFETCH_RETRY_MS) {
                    startPrefetch(state.library != null)
                }

                // "Show on frame" from the web page wins over browsing.
                val singleId = if (state.mode == "single") state.single?.id else null
                if (seenState && singleId != null && singleId != lastSingleId && browsing) {
                    browsing = false
                    runOnUiThread { endBrowse() }
                }
                seenState = true
                lastSingleId = singleId
                if (resumeSlideshow) {
                    resumeSlideshow = false
                    nextSlideAt = 0L
                }

                val single = state.single
                if (browsing) {
                    // Leave the screen to the viewer; keep polling the server.
                } else if (state.mode == "single" && single != null) {
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
                        if (nextIndex >= images.size) nextIndex = 0
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
                // Check back often while browsing, so the slideshow returns
                // promptly when browsing ends.
                if (browsing) waitSeconds = minOf(waitSeconds, BROWSE_POLL_SECONDS)
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (!networkAvailable()) {
                    failingSince = 0L
                    setStatus("Waiting for network…")
                    if (!sleepInterruptible(NETWORK_RETRY_MS)) break
                } else {
                    if (failingSince == 0L) failingSince = SystemClock.elapsedRealtime()
                    val waited = SystemClock.elapsedRealtime() - failingSince
                    if (waited < SERVER_GRACE_MS) {
                        setStatus("Contacting server… (${(waited + 999) / 1000}s)")
                    } else {
                        setStatus(
                            "Server unreachable: ${e.message ?: "connection failed"} — retrying"
                        )
                    }
                    if (!sleepInterruptible(POLL_MS)) break
                }
                continue
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
                    stateDisplayMode,
                    stateRestartId,
                    waitSeconds,
                )
                lastContactAt = SystemClock.elapsedRealtime()
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (!networkAvailable()) {
                    failingSince = 0L
                    setStatus("Waiting for network…")
                    if (!sleepInterruptible(NETWORK_RETRY_MS)) break
                } else {
                    if (failingSince == 0L) failingSince = SystemClock.elapsedRealtime()
                    val waited = SystemClock.elapsedRealtime() - failingSince
                    if (waited < SERVER_GRACE_MS) {
                        setStatus("Contacting server… (${(waited + 999) / 1000}s)")
                    } else {
                        setStatus(
                            "Server unreachable: ${e.message ?: "connection failed"} — retrying"
                        )
                    }
                    if (!sleepInterruptible(POLL_MS)) break
                }
            }
        }
    }

    private fun networkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun sleepInterruptible(ms: Long): Boolean = try {
        Thread.sleep(ms)
        true
    } catch (e: InterruptedException) {
        false
    }

    private fun requiredBluetoothPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasBluetoothPermissions(): Boolean =
        requiredBluetoothPermissions().all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    private fun locationEnabled(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return true
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            false
        }
    }

    private fun requestBluetoothPermissions() {
        if (bluetoothPermissionRequested) return
        bluetoothPermissionRequested = true
        requestPermissions(requiredBluetoothPermissions(), BLUETOOTH_PERMISSION_REQUEST)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == BLUETOOTH_PERMISSION_REQUEST) {
            bluetoothPermissionRequested = false
            if (hasBluetoothPermissions()) {
                startBluetoothScanning()
            } else {
                setStatus("Bluetooth scanning needs permission")
            }
        }
    }

    private fun startBluetoothScanning() {
        if (!running) return
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions()
            return
        }
        if (!locationEnabled()) {
            setStatus("Enable location services for Bluetooth scanning")
            bluetoothHandler.removeCallbacks(bluetoothScanRunnable)
            bluetoothHandler.postDelayed(bluetoothScanRunnable, BLUETOOTH_ENABLE_RETRY_MS)
            return
        }
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
        if (adapter == null) {
            setStatus("Bluetooth is not available")
            return
        }
        bluetoothAdapter = adapter
        val enabled = try {
            adapter.isEnabled
        } catch (e: SecurityException) {
            false
        }
        if (!enabled) {
            if (!bluetoothEnableAsked) {
                bluetoothEnableAsked = true
                setStatus("Enable Bluetooth for nearby-device scanning")
                try {
                    startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                } catch (e: Exception) {
                    Log.w("MemoryLink", "Could not request Bluetooth enable", e)
                }
            }
            bluetoothHandler.removeCallbacks(bluetoothScanRunnable)
            bluetoothHandler.postDelayed(bluetoothScanRunnable, BLUETOOTH_ENABLE_RETRY_MS)
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            setStatus("Bluetooth LE is not supported")
            return
        }
        bleScanner = scanner
        bluetoothHandler.removeCallbacks(bluetoothScanRunnable)
        bluetoothHandler.post(bluetoothScanRunnable)
    }

    private fun beginBluetoothScan() {
        if (!running || bluetoothScanning) return
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions()
            return
        }
        if (!locationEnabled()) {
            bluetoothHandler.postDelayed(bluetoothScanRunnable, BLUETOOTH_ENABLE_RETRY_MS)
            return
        }
        val scanner = bleScanner ?: return
        val adapter = bluetoothAdapter ?: return
        val enabled = try {
            adapter.isEnabled
        } catch (e: SecurityException) {
            false
        }
        if (!enabled) {
            bluetoothHandler.postDelayed(bluetoothScanRunnable, BLUETOOTH_ENABLE_RETRY_MS)
            return
        }
        bluetoothResults.clear()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            bluetoothScanning = true
            scanner.startScan(null, settings, bluetoothScanCallback)
            bluetoothHandler.postDelayed(bluetoothStopRunnable, BLUETOOTH_SCAN_MS)
        } catch (e: SecurityException) {
            bluetoothScanning = false
            requestBluetoothPermissions()
        } catch (e: Exception) {
            bluetoothScanning = false
            Log.w("MemoryLink", "Could not start Bluetooth scan", e)
            bluetoothHandler.postDelayed(bluetoothScanRunnable, BLUETOOTH_INTERVAL_MS)
        }
    }

    private fun recordBluetoothResult(result: ScanResult?) {
        if (!bluetoothScanning || result == null) return
        val device = result.device ?: return
        val address = try {
            device.address
        } catch (e: SecurityException) {
            return
        }
        if (address.isNullOrBlank()) return
        val name = try {
            device.name
        } catch (e: SecurityException) {
            null
        }
        val previous = bluetoothResults[address]
        bluetoothResults[address] = BluetoothSeen(address, name ?: previous?.name, result.rssi)
    }

    private fun finishBluetoothScan() {
        if (!bluetoothScanning) return
        bluetoothScanning = false
        try {
            bleScanner?.stopScan(bluetoothScanCallback)
        } catch (e: Exception) {
            Log.w("MemoryLink", "Could not stop Bluetooth scan", e)
        }
        val devices = bluetoothResults.values.toList()
        bluetoothResults.clear()
        bluetoothHandler.postDelayed(bluetoothScanRunnable, BLUETOOTH_INTERVAL_MS)
        if (devices.isEmpty()) return
        val activeClient = client ?: return
        Thread {
            try {
                activeClient.reportBluetooth(devices)
            } catch (e: Exception) {
                Log.w("MemoryLink", "Could not report Bluetooth devices", e)
            }
        }.start()
    }

    private fun stopBluetoothScanning() {
        bluetoothHandler.removeCallbacksAndMessages(null)
        if (bluetoothScanning) {
            try {
                bleScanner?.stopScan(bluetoothScanCallback)
            } catch (e: Exception) {
                Log.w("MemoryLink", "Could not stop Bluetooth scan", e)
            }
        }
        bluetoothScanning = false
        bluetoothResults.clear()
        bleScanner = null
        bluetoothAdapter = null
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
            prefs.edit().putString(FramePrefs.LAST_SHOWN_URL, ref.url).apply()
            val backdrop = if (blurEnabled) blurredBackdrop(bmp) else null
            runOnUiThread { if (!browsing) transitionTo(bmp, backdrop, ref.url) }
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
        ensureFile(ref)
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
            val file = File(filesDir, UPDATE_FILE)
            client!!.downloadFile(update.url, file)
            if (!file.isFile || file.length() == 0L) {
                throw java.io.IOException("downloaded update is missing")
            }
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
            screenUrl = null
            refreshPan()
        }
    }

    private val browseListener = object : BrowseView.Listener {
        override fun onBrowseSwipe(step: Int) {
            if (!beginBrowse()) return
            val size = browseItems.size
            val index = if (browseIndex < 0) {
                if (step > 0) 0 else size - 1
            } else {
                ((browseIndex + step) % size + size) % size
            }
            showBrowsed(index, if (step > 0) Transition.SLIDE_LEFT else Transition.SLIDE_RIGHT)
        }

        override fun onBrowsePinchOut() {
            if (beginBrowse()) openGrid()
        }

        override fun onBrowseOpen(index: Int) {
            touchBrowse()
            closeGrid()
            openedFromGrid = true
            updateBackButton()
            showBrowsed(index, Transition.ZOOM_OUT)
        }

        override fun onBrowseLongPress() {
            endBrowse()
            stopFrame()
            showConfig()
        }

        override fun onBrowseActivity() {
            if (browsing) touchBrowse()
        }
    }

    private fun serverReachable(): Boolean =
        lastContactAt > 0L && SystemClock.elapsedRealtime() - lastContactAt < CONTACT_FRESH_MS

    private fun isCached(ref: ImageRef): Boolean {
        val file = imageFile(ref.url)
        return file.isFile && file.length() > 0
    }

    /** The photo's file on disk, downloaded first if needed. */
    private fun ensureFile(ref: ImageRef): File {
        val file = imageFile(ref.url)
        synchronized(downloadLock) {
            if (!(file.isFile && file.length() > 0)) {
                val c = client ?: throw java.io.IOException("no server configured")
                c.downloadFile(ref.url, file)
            }
        }
        return file
    }

    /**
     * What browsing walks through: the slideshow's selection, then every other
     * photo in gallery order. Without a server only photos on the tablet count.
     */
    private fun browseSequence(): List<ImageRef> {
        var refs = (knownPlaylist + knownLibrary).distinctBy { it.url }
        if (refs.isEmpty()) {
            // Never reached a server with this version: use what is on disk.
            refs = imageDir.listFiles()
                ?.filter { it.isFile && it.length() > 0 && !it.name.endsWith(".part") }
                ?.sortedBy { it.lastModified() }
                ?.map { ImageRef(-1L, "/images/" + it.name) }
                ?: emptyList()
        }
        return if (serverReachable()) refs else refs.filter { isCached(it) }
    }

    /** Enter browsing (if not browsing yet); false when there is nothing to show. */
    private fun beginBrowse(): Boolean {
        if (!browsing) {
            browseAll = browseSequence()
            browseTag = null
            browseItems = browseAll
            if (browseItems.isEmpty()) return false
            val url = screenUrl
            browseIndex = browseItems.indexOfFirst { it.url == url }
            openedFromGrid = false
            browsing = true
            statusView.visibility = View.GONE
            browseHandler.removeCallbacks(browseTimeoutCheck)
            browseHandler.postDelayed(browseTimeoutCheck, BROWSE_CHECK_MS)
        }
        touchBrowse()
        return true
    }

    private fun touchBrowse() {
        browseDeadline = SystemClock.elapsedRealtime() + BROWSE_TIMEOUT_MS
    }

    private fun checkBrowseTimeout() {
        if (!browsing) return
        val now = SystemClock.elapsedRealtime()
        // Without the server the clock stands still.
        if (!serverReachable()) browseDeadline = maxOf(browseDeadline, now + BROWSE_TIMEOUT_MS)
        if (now >= browseDeadline) {
            endBrowse()
        } else {
            browseHandler.postDelayed(browseTimeoutCheck, BROWSE_CHECK_MS)
        }
    }

    /** Leave browsing; the frame loop picks up the slideshow again. */
    private fun endBrowse() {
        browseHandler.removeCallbacks(browseTimeoutCheck)
        browseRequest++
        val wasBrowsing = browsing
        browsing = false
        openedFromGrid = false
        closeGrid()
        browseHandler.removeCallbacks(hideBrowseLabel)
        browseLabel.animate().cancel()
        browseLabel.visibility = View.GONE
        browseTag = null
        updateBackButton()
        if (wasBrowsing) resumeSlideshow = true
    }

    private fun openGrid() {
        openedFromGrid = false
        thumbFailed.clear() // retry photos that may have arrived since
        showTagBar(true)
        browseView.showGrid(browseItems, browseIndex, gridColumns)
        browseHandler.removeCallbacks(hideBrowseLabel)
        browseLabel.visibility = View.GONE
        updateBackButton()
        applyClockVisibility()
    }

    private fun closeGrid() {
        if (browseView.gridVisible) {
            gridColumns = browseView.columns
            browseView.hideGrid()
        }
        showTagBar(false)
        updateBackButton()
        applyClockVisibility()
    }

    /** The arrow back to the overview, on a photo that was opened from it. */
    private fun updateBackButton() {
        val show = browsing && openedFromGrid && !browseView.gridVisible
        browseBack.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun dp(value: Int): Int = Math.round(value * resources.displayMetrics.density)

    /** Tag chips above the grid; choosing one narrows the photos to that tag. */
    private fun showTagBar(visible: Boolean) {
        val tags = browseAll.flatMap { it.tags }.distinct().sorted()
        val show = visible && tags.isNotEmpty()
        browseTagBar.visibility = if (show) View.VISIBLE else View.GONE
        browseView.topInset = if (show) dp(TAG_BAR_DP).toFloat() else 0f
        browseTagRow.removeAllViews()
        if (!show) return
        browseTagRow.addView(tagChip(getString(R.string.browse_all_tags, browseAll.size), browseTag == null) {
            selectBrowseTag(null)
        })
        for (tag in tags) {
            val count = browseAll.count { tag in it.tags }
            browseTagRow.addView(tagChip("$tag ($count)", tag == browseTag) { selectBrowseTag(tag) })
        }
    }

    private fun tagChip(text: String, selected: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 18f
            setTextColor(if (selected) Color.BLACK else Color.WHITE)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(if (selected) Color.WHITE else 0x40ffffff)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(8) }
            setOnClickListener { onClick() }
        }

    private fun selectBrowseTag(tag: String?) {
        touchBrowse()
        val current = browseItems.getOrNull(browseIndex)?.url ?: screenUrl
        browseTag = tag
        browseItems = if (tag == null) browseAll else browseAll.filter { tag in it.tags }
        browseIndex = browseItems.indexOfFirst { it.url == current }
        showTagBar(true)
        browseView.showGrid(browseItems, browseIndex, browseView.columns)
    }

    /** Show browseItems[index] with the given transition. */
    private fun showBrowsed(index: Int, kind: Transition) {
        if (index !in browseItems.indices) return
        browseIndex = index
        val ref = browseItems[index]
        showBrowseLabel("${index + 1} / ${browseItems.size}")
        val request = ++browseRequest
        browseExecutor.execute {
            val bmp = try {
                bitmapCache.get(ref.url) ?: loadBitmap(ref).also { bitmapCache.put(ref.url, it) }
            } catch (e: Exception) {
                null
            }
            val backdrop = if (bmp != null && blurEnabled) blurredBackdrop(bmp) else null
            runOnUiThread {
                if (bmp != null && browsing && request == browseRequest) {
                    currentUrl = ref.url
                    prefs.edit().putString(FramePrefs.LAST_SHOWN_URL, ref.url).apply()
                    transitionTo(bmp, backdrop, ref.url, kind, BROWSE_TRANSITION_MS)
                }
            }
            if (bmp != null && ref.id >= 0 && serverReachable()) {
                try {
                    client?.reportShown(ref.id)
                } catch (e: Exception) {
                    // Best effort.
                }
            }
        }
    }

    private fun showBrowseLabel(text: String) {
        browseHandler.removeCallbacks(hideBrowseLabel)
        browseLabel.animate().cancel()
        browseLabel.text = text
        browseLabel.alpha = 1f
        browseLabel.visibility = View.VISIBLE
        browseHandler.postDelayed(hideBrowseLabel, BROWSE_LABEL_MS)
    }

    /** Grid thumbnail from the cache, or null while it is being decoded. UI thread. */
    private fun thumbFor(ref: ImageRef, width: Int, height: Int): Bitmap? {
        val key = "${ref.url}|$width"
        val cached = thumbCache.get(key)
        if (cached == null && key !in thumbFailed && thumbLoading.add(key)) {
            thumbExecutor.execute {
                val bmp = try {
                    loadThumb(ref, width, height)
                } catch (e: Exception) {
                    null
                }
                runOnUiThread {
                    thumbLoading.remove(key)
                    if (bmp == null) thumbFailed.add(key) else thumbCache.put(key, bmp)
                    browseView.invalidate()
                }
            }
        }
        return cached
    }

    /** Decode a photo just big enough to cover a width x height cell. */
    private fun loadThumb(ref: ImageRef, width: Int, height: Int): Bitmap? {
        val file = when {
            isCached(ref) -> imageFile(ref.url)
            serverReachable() -> ensureFile(ref)
            else -> return null
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val scale = maxOf(
            width.toFloat() / bounds.outWidth,
            height.toFloat() / bounds.outHeight,
        )
        var sample = 1
        while (sample * 2 <= 1f / scale) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val decoded = BitmapFactory.decodeFile(file.path, options) ?: return null
        val targetW = ceil(bounds.outWidth * scale).toInt().coerceAtLeast(1)
        val targetH = ceil(bounds.outHeight * scale).toInt().coerceAtLeast(1)
        if (decoded.width <= targetW * 5 / 4) return decoded
        val scaled = Bitmap.createScaledBitmap(decoded, targetW, targetH, true)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    /** Put the last photo on screen at start, before (or without) the server. */
    private fun showLastPhoto() {
        if (screenUrl != null) return
        val url = prefs.getString(FramePrefs.LAST_SHOWN_URL, null) ?: return
        val file = imageFile(url)
        if (!file.isFile) return
        Thread {
            val bmp = BitmapFactory.decodeFile(file.path) ?: return@Thread
            runOnUiThread {
                if (screenUrl == null && frameView.visibility == View.VISIBLE) {
                    screenUrl = url
                    frameView.setImageBitmap(bmp)
                    refreshPan()
                }
            }
        }.start()
    }

    private fun updateKnownLists(state: FrameState) {
        knownPlaylist = state.images
        knownLibrary = state.library ?: state.images
        saveLists()
        startPrefetch(state.library != null)
    }

    private fun refsToJson(refs: List<ImageRef>): JSONArray {
        val array = JSONArray()
        for (ref in refs) {
            array.put(
                JSONObject().put("id", ref.id).put("url", ref.url).put("tags", JSONArray(ref.tags))
            )
        }
        return array
    }

    private fun refsFromJson(array: JSONArray?): List<ImageRef> {
        if (array == null) return emptyList()
        return (0 until array.length()).map { ImageRef.fromJson(array.getJSONObject(it)) }
    }

    private fun saveLists() {
        try {
            val json = JSONObject()
                .put("playlist", refsToJson(knownPlaylist))
                .put("library", refsToJson(knownLibrary))
            val file = File(filesDir, LISTS_FILE)
            val tmp = File(filesDir, "$LISTS_FILE.tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (e: Exception) {
            Log.w("MemoryLink", "Could not save image lists", e)
        }
    }

    private fun loadLists() {
        try {
            val file = File(filesDir, LISTS_FILE)
            if (!file.isFile) return
            val json = JSONObject(file.readText())
            knownPlaylist = refsFromJson(json.optJSONArray("playlist"))
            knownLibrary = refsFromJson(json.optJSONArray("library"))
        } catch (e: Exception) {
            Log.w("MemoryLink", "Could not read image lists", e)
        }
    }

    /**
     * Download every known photo in the background, so the frame can show them
     * all when it is away from home. With [cleanUp], photos that are no longer
     * on the server are removed from the tablet afterwards.
     */
    private fun startPrefetch(cleanUp: Boolean) {
        lastPrefetchAt = SystemClock.elapsedRealtime()
        if (!prefetching.compareAndSet(false, true)) {
            prefetchAgain = true
            return
        }
        val thread = Thread({
            try {
                do {
                    prefetchAgain = false
                    prefetchAll(cleanUp)
                } while (prefetchAgain && running)
            } finally {
                prefetching.set(false)
            }
        }, "prefetch")
        thread.priority = Thread.MIN_PRIORITY
        thread.start()
    }

    private fun prefetchAll(cleanUp: Boolean) {
        val refs = (knownPlaylist + knownLibrary).distinctBy { it.url }
        for (ref in refs) {
            if (!running) return
            if (isCached(ref)) continue
            if (imageDir.usableSpace < MIN_FREE_BYTES) return
            try {
                ensureFile(ref)
            } catch (e: Exception) {
                return // server gone; try again later
            }
        }
        if (cleanUp) removeUnusedFiles(refs)
    }

    private fun removeUnusedFiles(refs: List<ImageRef>) {
        val keep = HashSet<String>()
        for (ref in refs) keep.add(imageFile(ref.url).name)
        prefs.getString(FramePrefs.LAST_SHOWN_URL, null)?.let { keep.add(imageFile(it).name) }
        val cutoff = System.currentTimeMillis() - 3_600_000L
        imageDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name !in keep && file.lastModified() < cutoff) file.delete()
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
    private fun transitionTo(
        bmp: Bitmap,
        backdrop: Bitmap?,
        url: String?,
        forced: Transition? = null,
        durationMs: Long = 0L,
    ) {
        screenUrl = url
        resetNextFrame()
        nextFrameView.setBackgroundColor(if (blurEnabled) Color.TRANSPARENT else Color.BLACK)
        if (blurEnabled) setBackdrop(backdrop) else setBackdrop(null)
        nextFrameView.setImageBitmap(bmp)
        if (displayMode == "pan") panMatrix(nextFrameView, 0f)?.let { nextFrameView.imageMatrix = it }
        nextFrameView.visibility = View.VISIBLE

        val kinds = transitionKinds()
        val kind = forced ?: kinds[random.nextInt(kinds.size)]
        val duration = if (durationMs > 0L) durationMs else 550L + random.nextInt(450)
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
        refreshPan()
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
            statusView.visibility = if (browsing) View.GONE else View.VISIBLE
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

    override fun onResume() {
        super.onResume()
        if (running && hasBluetoothPermissions()) {
            startBluetoothScanning()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        clockHandler.removeCallbacks(clockTicker)
        browseHandler.removeCallbacksAndMessages(null)
        panAnimator?.cancel()
        stopFrame()
        browseExecutor.shutdownNow()
        thumbExecutor.shutdownNow()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            // From the grid back to the photo.
            browseView.gridVisible -> {
                touchBrowse()
                closeGrid()
            }
            // From a photo opened in the grid back to the grid.
            browsing && openedFromGrid -> {
                touchBrowse()
                openGrid()
            }
            browsing -> endBrowse()
            else -> super.onBackPressed()
        }
    }

    companion object {
        /**
         * Corners of the picture opening in splash_landscape.jpg and
         * splash_portrait.jpg (top-left, top-right, bottom-right, bottom-left),
         * as x,y fractions of the artwork's size. Change these together with
         * the artwork.
         */
        private val SPLASH_OPENING_LANDSCAPE = floatArrayOf(
            0.410920f, 0.566489f,
            0.595905f, 0.561170f,
            0.609914f, 0.757979f,
            0.421695f, 0.771277f,
        )
        private val SPLASH_OPENING_PORTRAIT = floatArrayOf(
            0.311170f, 0.492098f,
            0.722074f, 0.491379f,
            0.740691f, 0.775144f,
            0.319149f, 0.777299f,
        )

        /** The splash stays at least this long ... */
        private const val SPLASH_MIN_MS = 3_200L

        /** ... and up to this much longer while the tablet looks for a network. */
        private const val SPLASH_WIFI_EXTRA_MS = 7_000L

        /** Height of the tag chips above the photo grid. */
        private const val TAG_BAR_DP = 64

        private const val SPLASH_OVERSAMPLE = 1.5f

        /** Crop the photo to fill the opening only if at least this much of it stays visible. */
        private const val SPLASH_FILL_MIN_KEPT = 0.75f

        private const val POLL_MS = 6000L
        private const val NETWORK_RETRY_MS = 2000L
        private const val SERVER_GRACE_MS = 20000L
        private const val LONGPOLL_SECONDS = 15
        private const val PLACEHOLDER = "\u0000placeholder"
        private const val UPDATE_FILE = "update.apk"
        private const val BLUETOOTH_PERMISSION_REQUEST = 1001
        private const val BLUETOOTH_SCAN_MS = 10_000L
        private const val BLUETOOTH_INTERVAL_MS = 120_000L
        private const val BLUETOOTH_ENABLE_RETRY_MS = 30_000L

        /** Browsing ends after this long without a touch, while the server is reachable. */
        private const val BROWSE_TIMEOUT_MS = 3 * 60_000L
        private const val BROWSE_CHECK_MS = 5_000L
        private const val BROWSE_POLL_SECONDS = 5
        private const val BROWSE_TRANSITION_MS = 350L
        private const val BROWSE_LABEL_MS = 2_000L
        private const val CONTACT_FRESH_MS = 30_000L
        private const val PREFETCH_RETRY_MS = 10 * 60_000L
        private const val MIN_FREE_BYTES = 200L * 1024 * 1024
        private const val LISTS_FILE = "image-lists.json"
    }
}
