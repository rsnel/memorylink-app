package com.memorylink.photoframe

import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.util.LruCache
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView

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
        var slideStart = SystemClock.elapsedRealtime()
        var images: List<ImageRef> = emptyList()

        while (running) {
            try {
                val state = client!!.fetchState()
                if (state.mode == "single" && state.single != null) {
                    showImage(state.single.url)
                } else {
                    if (state.version != lastVersion) {
                        images = state.images
                        lastVersion = state.version
                        slideStart = SystemClock.elapsedRealtime()
                    }
                    if (images.isEmpty()) {
                        showPlaceholder()
                    } else {
                        val interval = state.intervalSeconds.coerceAtLeast(5)
                        val idx = (((SystemClock.elapsedRealtime() - slideStart) /
                            (interval * 1000L)) % images.size).toInt()
                        showImage(images[idx].url)
                    }
                }
                clearStatus()
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

    private fun showImage(url: String) {
        if (url == currentUrl) return
        try {
            val bmp = bitmapCache.get(url) ?: client!!.downloadImage(url).also {
                bitmapCache.put(url, it)
            }
            currentUrl = url
            runOnUiThread { frameView.setImageBitmap(bmp) }
        } catch (e: Exception) {
            setStatus("Could not load image: ${e.message}")
        }
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
    }
}
