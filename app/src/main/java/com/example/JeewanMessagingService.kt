package com.example

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class JeewanMessagingService : FirebaseMessagingService() {

    @Suppress("DEPRECATION")
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val clean = token.trim()
        if (clean.isEmpty()) return
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString("native_fcm_token", clean)
            .putString("fcm_token", clean)
            .apply()

        val isMuted = prefs.getBoolean("push_muted", false)
        if (!isMuted && FirebaseApp.getApps(applicationContext).isNotEmpty()) {
            runCatching {
                FirebaseMessaging.getInstance().subscribeToTopic(MainActivity.FCM_CHAT_TOPIC)
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean("push_muted", false)) {
            return
        }

        val data = remoteMessage.data
        val sentAtMs = (data["sentAt"] ?: data["timestamp"])?.toLongOrNull() ?: 0L
        val lastNotifiedTs = prefs.getLong("last_notified_ts", 0L)
        if (sentAtMs > 0L && sentAtMs <= lastNotifiedTs) {
            return
        }
        if (sentAtMs > lastNotifiedTs) {
            prefs.edit().putLong("last_notified_ts", sentAtMs).apply()
        }

        val postId = data["postId"] ?: data["messageId"] ?: ""
        val title = remoteMessage.notification?.title
            ?: data["title"]
            ?: "Jeewan City Sahiwal"
        val body = remoteMessage.notification?.body
            ?: data["body"]
            ?: "New message in Jeewan City group"

        MainActivity.showInteractiveMessageNotificationCompat(
            context = applicationContext,
            postId = postId,
            title = title,
            body = body
        )
    }
}
