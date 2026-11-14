package com.memorylink.photoframe

import android.content.SharedPreferences
import java.util.UUID

/** Shared SharedPreferences keys used by the activity and update receivers. */
object FramePrefs {
    const val NAME = "memorylink"
    const val SERVER_URL = "server_url"
    const val FRAME_ID = "frame_id"
    const val HANDLED_UPDATE = "handled_update_id"
    const val INSTALLING_UPDATE = "installing_update_id"
    const val LAST_SHOWN_URL = "last_shown_url"
    const val HANDLED_RESTART = "handled_restart_id"

    /** auto, portrait or landscape: set from the server, kept for offline starts. */
    const val ORIENTATION_LOCK = "orientation_lock"

    /** The installation's stable UUID, generated on first use. */
    fun ensureFrameId(prefs: SharedPreferences): String {
        prefs.getString(FRAME_ID, null)?.let { return it }
        return UUID.randomUUID().toString().also { id ->
            prefs.edit().putString(FRAME_ID, id).commit()
        }
    }
}
