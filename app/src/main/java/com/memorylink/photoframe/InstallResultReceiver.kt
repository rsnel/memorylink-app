package com.memorylink.photoframe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast

/** Receives the result of a PackageInstaller session and reports it. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        val updateId = intent.getLongExtra(EXTRA_UPDATE_ID, -1L)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        // The installer asks us to show its confirmation dialog before it can
        // continue. This is the activity we must launch; it is NOT a failure.
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirmation == null) {
                report(
                    context,
                    updateId,
                    "failed",
                    "installer requested user action without a confirmation intent",
                )
                return
            }
            try {
                Log.i("MemoryLink", "launching install confirmation update=$updateId")
                context.startActivity(
                    confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                report(
                    context,
                    updateId,
                    "failed",
                    "could not launch installer: ${e.message ?: e.javaClass.simpleName}",
                )
            }
            return
        }

        val result = when (status) {
            PackageInstaller.STATUS_SUCCESS -> "installed"
            PackageInstaller.STATUS_FAILURE_ABORTED -> "canceled"
            else -> "failed"
        }
        val statusName = when (status) {
            PackageInstaller.STATUS_SUCCESS -> "SUCCESS"
            PackageInstaller.STATUS_FAILURE -> "FAILURE"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "BLOCKED"
            PackageInstaller.STATUS_FAILURE_ABORTED -> "ABORTED"
            PackageInstaller.STATUS_FAILURE_INVALID -> "INVALID"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "CONFLICT"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "STORAGE"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "INCOMPATIBLE"
            else -> "UNKNOWN($status)"
        }
        val detail = "PackageInstaller $statusName" +
            if (!message.isNullOrBlank()) ": $message" else ""
        Log.w("MemoryLink", "install result update=$updateId status=$statusName message=$message")
        report(context, updateId, result, detail)
    }

    private fun report(context: Context, updateId: Long, result: String, detail: String) {
        if (result == "failed") {
            Toast.makeText(context, detail, Toast.LENGTH_LONG).show()
        }
        if (updateId <= 0) return

        val pending = goAsync()
        Thread {
            try {
                val prefs = context.getSharedPreferences(FramePrefs.NAME, Context.MODE_PRIVATE)
                val url = prefs.getString(FramePrefs.SERVER_URL, null)
                if (!url.isNullOrBlank()) {
                    try {
                        FrameClient(url).reportUpdateResult(updateId, result, detail)
                    } catch (e: Exception) {
                        // Best effort; the website will keep the last known state.
                    }
                }
                prefs.edit().remove(FramePrefs.INSTALLING_UPDATE).apply()
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val EXTRA_UPDATE_ID = "update_id"
    }
}
