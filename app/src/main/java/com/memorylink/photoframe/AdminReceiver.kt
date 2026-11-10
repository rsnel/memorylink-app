package com.memorylink.photoframe

import android.app.admin.DeviceAdminReceiver

/**
 * Empty admin receiver. It exists so the frame app can be provisioned as the
 * device owner with:
 *
 *   adb shell dpm set-device-owner \
 *       com.memorylink.photoframe/.AdminReceiver
 *
 * When the app is device owner, PackageInstaller installs updates without the
 * confirmation dialog.
 */
class AdminReceiver : DeviceAdminReceiver()
