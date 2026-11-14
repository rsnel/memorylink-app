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

    // Sleep schedule (HH:MM, empty = off) and the last Sleep/Wake command from
    // the server, kept so the frame also follows them away from home.
    const val SLEEP_AT = "sleep_at"
    const val WAKE_AT = "wake_at"
    const val SLEEP_CMD = "sleep_cmd"
    const val SLEEP_CMD_ID = "sleep_cmd_id"
    const val SLEEP_CMD_AT = "sleep_cmd_at"

    /** The installation's stable UUID, generated on first use. */
    fun ensureFrameId(prefs: SharedPreferences): String {
        prefs.getString(FRAME_ID, null)?.let { return it }
        return UUID.randomUUID().toString().also { id ->
            prefs.edit().putString(FRAME_ID, id).commit()
        }
    }
}
