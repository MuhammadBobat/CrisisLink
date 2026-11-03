package org.crisislink.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Restarts the mesh after a reboot or app update, if the user left background operation on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("crisislink", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("background", true) || !prefs.getBoolean("onboarded", false)) return
        val needed = if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        } else emptyList()
        if (needed.any { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }) return
        // Some vendors still refuse background starts; failing quietly beats crashing the receiver.
        runCatching { MeshForegroundService.start(context) }
    }
}
