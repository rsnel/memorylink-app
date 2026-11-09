package com.memorylink.photoframe

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.LruCache
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Random

/**
 * The photoframe. First launch (or long-press on the frame) shows a single
 * field: the HTTPS URL of the server. After that it polls the server and
 * shows whatever it is told to show, fullscreen.
 */
class MainActivity : Activity() {

    private lateinit var frameView: ImageView
    private lateinit var configView: View
    private lateinit var urlInput: EditText
    private lateinit var connectBtn: Button
    private lateinit var statusView: TextView

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

    private val prefs by lazy { getSharedPreferences("memorylink", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        frameView = findViewById(R.id.frameView)
        configView = findViewById(R.id.configView)
        urlInput = findViewById(R.id.urlInput)
        connectBtn = findViewById(R.id.connectBtn)
        statusView = findViewById(R.id.statusView)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        connectBtn.setOnClickListener {
            val url = urlInput.text.toString().trim().trimEnd('/')
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                setStatus("URL must start with http:// or https://")
                return@setOnClickListener
            }
            prefs.edit().putString("server_url", url).apply()
            startFrame(url)
        }

        // Long-press the frame to reconfigure (change the server URL).
        frameView.setOnLongClickListener {
            stopFrame()
            showConfig()
            true
        }

        val saved = prefs.getString("server_url", null)
        if (saved.isNullOrBlank()) showConfig() else startFrame(saved)
    }

    private fun showConfig() {
        frameView.visibility = View.GONE
        configView.visibility = View.VISIBLE
        statusView.visibility = View.GONE
        hideSystemBars(false)
        val saved = prefs.getString("server_url", null)
        if (!saved.isNullOrBlank()) urlInput.setText(saved)
    }

    private fun startFrame(url: String) {
        configView.visibility = View.GONE
        statusView.visibility = View.GONE
        frameView.visibility = View.VISIBLE
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

        while (running) {
            try {
                val state = client!!.fetchState()
                clearStatus()
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
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                setStatus("Server unreachable: ${e.message}")
            }

            try {
                Thread.sleep(POLL_MS)
            } catch (e: InterruptedException) {
                break
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
            runOnUiThread { frameView.setImageBitmap(bmp) }
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
        if (update.id <= prefs.getLong(PREF_HANDLED_UPDATE, 0L)) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            if (permissionPromptedFor == update.id) return
            permissionPromptedFor = update.id
            setStatus("Allow installs for MemoryLink; the update will continue")
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
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
            prefs.edit().putLong(PREF_HANDLED_UPDATE, update.id).commit()
            installApk(file)
            setStatus("Update ready — confirm the install on the frame")
        } catch (e: Exception) {
            setStatus("Update failed: ${e.message}")
        }
    }

    private fun installApk(file: File) {
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
        runOnUiThread { frameView.setImageBitmap(null) }
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
        stopFrame()
    }

    companion object {
        private const val POLL_MS = 6000L
        private const val PLACEHOLDER = "\u0000placeholder"
        private const val UPDATE_FILE = "update.apk"
        private const val PREF_HANDLED_UPDATE = "handled_update_id"
    }
}
