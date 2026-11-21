package com.example

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import org.json.JSONArray
import org.json.JSONObject

class JeewanNotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val postId = intent.getStringExtra(MainActivity.EXTRA_POST_ID).orEmpty().trim()
        val notifId = intent.getIntExtra(MainActivity.EXTRA_NOTIF_ID, -1)
        if (notifId != -1) {
            runCatching {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.cancel(notifId)
            }
        }

        when (intent.action) {
            MainActivity.ACTION_NOTIF_MARK_READ -> {
                if (postId.isNotEmpty()) {
                    recordReadPostId(context, postId)
                    MainActivity.dispatchMarkReadToActiveWebView(postId)
                }
            }
            MainActivity.ACTION_NOTIF_REPLY -> {
                val replyText = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(MainActivity.KEY_TEXT_REPLY)
                    ?.toString()
                    ?.trim()
                    .orEmpty()
                if (replyText.isNotEmpty()) {
                    if (postId.isNotEmpty()) {
                        recordReadPostId(context, postId)
                    }
                    val deliveredLive = MainActivity.dispatchReplyToActiveWebView(postId, replyText)
                    if (!deliveredLive) {
                        queuePendingReply(context, postId, replyText)
                    }
                }
            }
        }
    }

    private fun recordReadPostId(context: Context, postId: String) {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val existingRead = prefs.getStringSet("read_post_ids", emptySet()).orEmpty().toMutableSet()
        existingRead.add(postId)
        val pendingRead = prefs.getStringSet("pending_read_ids", emptySet()).orEmpty().toMutableSet()
        pendingRead.add(postId)
        prefs.edit()
            .putStringSet("read_post_ids", existingRead.toList().takeLast(200).toSet())
            .putStringSet("pending_read_ids", pendingRead.toList().takeLast(50).toSet())
            .apply()
    }

    private fun queuePendingReply(context: Context, postId: String, replyText: String) {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString("pending_notif_replies", "[]").orEmpty()
        val arr = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        arr.put(JSONObject().apply {
            put("postId", postId)
            put("text", replyText)
            put("createdAtMs", System.currentTimeMillis())
        })
        prefs.edit().putString("pending_notif_replies", arr.toString()).apply()
    }
}
