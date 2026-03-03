package com.btmicswitch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives BOOT_COMPLETED to allow the app to restore state after reboot
 * if the service was running before device restart.
 * (Currently just a stub — future: restore from SharedPreferences if needed)
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Optionally auto-start if pref is saved
            val prefs = context.getSharedPreferences("btmicswitch", Context.MODE_PRIVATE)
            val wasActive = prefs.getBoolean("was_active_before_reboot", false)
            if (wasActive) {
                // Let user manually restart for now — uncomment below to auto-start
                // val svcIntent = Intent(context, BluetoothScoService::class.java).apply {
                //     action = BluetoothScoService.ACTION_START
                // }
                // ContextCompat.startForegroundService(context, svcIntent)
            }
        }
    }
}
