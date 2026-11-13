package com.memorylink.photoframe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Bring the frame back up after the app has been replaced by an update. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val prefs = context.getSharedPreferences(FramePrefs.NAME, Context.MODE_PRIVATE)
        val updateId = prefs.getLong(FramePrefs.INSTALLING_UPDATE, -1L)
        if (updateId > 0) {
            val pending = goAsync()
            Thread {
                try {
                    val url = prefs.getString(FramePrefs.SERVER_URL, null)
                    if (!url.isNullOrBlank()) {
                        try {
                            FrameClient(url, FramePrefs.ensureFrameId(prefs))
                                .reportUpdateResult(updateId, "installed", null)
                        } catch (e: Exception) {
                            // Best effort; the website will keep the last state.
                        }
                    }
                    prefs.edit().remove(FramePrefs.INSTALLING_UPDATE).apply()
                } finally {
                    pending.finish()
                }
            }.start()
        }

        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }
}
