package com.memorylink.photoframe

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A single image reference the server hands to the frame. */
data class ImageRef(val id: Long, val url: String) {
    companion object {
        fun fromJson(o: JSONObject) = ImageRef(o.optLong("id"), o.getString("url"))
    }
}

/** Parsed response of GET /api/frame/current. */
data class FrameState(
    val mode: String,
    val version: Long,
    val intervalSeconds: Int,
    val single: ImageRef?,
    val images: List<ImageRef>,
) {
    companion object {
        fun fromJson(o: JSONObject): FrameState {
            val single = if (!o.isNull("image")) ImageRef.fromJson(o.getJSONObject("image")) else null
            val arr = o.getJSONArray("images")
            val images = (0 until arr.length()).map { ImageRef.fromJson(arr.getJSONObject(it)) }
            return FrameState(
                mode = o.optString("mode", "slideshow"),
                version = o.optLong("version", 0),
                intervalSeconds = o.optInt("interval_seconds", 30),
                single = single,
                images = images,
            )
        }
    }
}

/** Minimal HTTP client for the MemoryLink server. No external dependencies. */
class FrameClient(private val baseUrl: String) {

    fun fetchState(): FrameState {
        val conn = open("/api/frame/current")
        return try {
            check(conn)
            val body = conn.inputStream.readBytes().toString(Charsets.UTF_8)
            FrameState.fromJson(JSONObject(body))
        } finally {
            conn.disconnect()
        }
    }

    fun downloadImage(path: String): ByteArray {
        val conn = open(path)
        return try {
            check(conn)
            conn.inputStream.readBytes()
        } finally {
            conn.disconnect()
        }
    }

    private fun open(path: String): HttpURLConnection {
        val url = URL(baseUrl.trimEnd('/') + path)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 20000
        conn.requestMethod = "GET"
        return conn
    }

    private fun check(conn: HttpURLConnection) {
        val code = conn.responseCode
        if (code !in 200..299) throw java.io.IOException("HTTP $code")
    }
}
