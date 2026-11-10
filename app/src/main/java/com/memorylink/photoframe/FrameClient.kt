package com.memorylink.photoframe

import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A single image reference the server hands to the frame. */
data class ImageRef(val id: Long, val url: String) {
    companion object {
        fun fromJson(o: JSONObject) = ImageRef(o.optLong("id"), o.getString("url"))
    }
}

/** An APK the server has been told to push to the frame. */
data class UpdateRef(
    val id: Long,
    val url: String,
    val name: String?,
    val sha256: String?,
    val size: Long,
) {
    companion object {
        fun fromJson(o: JSONObject) = UpdateRef(
            id = o.optLong("id"),
            url = o.getString("url"),
            name = if (o.isNull("name")) null else o.optString("name"),
            sha256 = if (o.isNull("sha256")) null else o.optString("sha256"),
            size = o.optLong("size", -1L),
        )
    }
}

/** Parsed response of GET /api/frame/current. */
data class FrameState(
    val mode: String,
    val version: Long,
    val intervalSeconds: Int,
    val random: Boolean,
    val transitions: List<String>,
    val single: ImageRef?,
    val images: List<ImageRef>,
    val update: UpdateRef?,
) {
    companion object {
        fun fromJson(o: JSONObject): FrameState {
            val single = if (!o.isNull("image")) ImageRef.fromJson(o.getJSONObject("image")) else null
            val update = if (o.isNull("update")) null else UpdateRef.fromJson(o.getJSONObject("update"))
            val transitionsArr = o.optJSONArray("transitions")
            val transitions = if (transitionsArr == null) {
                emptyList()
            } else {
                (0 until transitionsArr.length()).map { transitionsArr.getString(it) }
            }
            val arr = o.getJSONArray("images")
            val images = (0 until arr.length()).map { ImageRef.fromJson(arr.getJSONObject(it)) }
            return FrameState(
                mode = o.optString("mode", "slideshow"),
                version = o.optLong("version", 0),
                intervalSeconds = o.optInt("interval_seconds", 30),
                random = o.optBoolean("random", false),
                transitions = transitions,
                single = single,
                images = images,
                update = update,
            )
        }
    }
}

/** Minimal HTTP client for the MemoryLink server. No external dependencies. */
class FrameClient(baseUrl: String) {

    // Credentials may be embedded in the configured URL; strip them from the
    // request URL and send them as a normal HTTP Basic Authorization header.
    private val base: String
    private val authHeader: String?

    init {
        val parsed = Uri.parse(baseUrl.trimEnd('/'))
        authHeader = parsed.userInfo?.let { info ->
            val userPass = if (info.contains(':')) info else "$info:"
            "Basic " + Base64.encodeToString(
                userPass.toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP,
            )
        }
        // Rebuild the base URL without the user info part.
        val hostAndPort = parsed.encodedAuthority.orEmpty().substringAfterLast('@')
        val clean = Uri.Builder()
            .scheme(parsed.scheme)
            .encodedAuthority(hostAndPort)
        parsed.encodedPath?.let { clean.encodedPath(it) }
        parsed.encodedQuery?.let { clean.encodedQuery(it) }
        base = clean.build().toString().trimEnd('/')
    }

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

    /**
     * Stream the file straight to [dest], avoiding a full copy in memory.
     * The file only appears once the download completed.
     */
    fun downloadFile(path: String, dest: File) {
        val conn = open(path)
        try {
            check(conn)
            dest.parentFile?.mkdirs()
            val tmp = File(dest.path + ".part")
            var stored = false
            try {
                conn.inputStream.use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
                dest.delete()
                if (!tmp.renameTo(dest)) {
                    throw java.io.IOException("could not store file")
                }
                stored = true
            } finally {
                if (!stored) tmp.delete()
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Best-effort notification of the image the frame is actually showing. */
    fun reportShown(imageId: Long) {
        val conn = open("/api/frame/shown")
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { out ->
                out.write("{\"image_id\":$imageId}".toByteArray(Charsets.UTF_8))
            }
            check(conn)
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(path: String): HttpURLConnection {
        val url = URL(base + path)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 20000
        conn.requestMethod = "GET"
        authHeader?.let { conn.setRequestProperty("Authorization", it) }
        return conn
    }

    private fun check(conn: HttpURLConnection) {
        val code = conn.responseCode
        if (code !in 200..299) throw java.io.IOException("HTTP $code")
    }
}
