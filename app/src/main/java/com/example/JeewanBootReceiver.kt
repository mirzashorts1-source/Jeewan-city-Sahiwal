package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class JeewanBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                MainActivity.createNotificationChannel(context)
                JeewanNotificationScheduler.schedulePeriodic(context)
            }
        }
    }
}
