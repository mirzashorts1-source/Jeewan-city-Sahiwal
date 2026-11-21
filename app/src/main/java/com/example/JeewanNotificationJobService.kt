package com.example

import android.Manifest
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class JeewanNotificationJobService : JobService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        val appCtx = applicationContext
        activeJob?.cancel()
        activeJob = serviceScope.launch {
            var needsReschedule = false
            try {
                JeewanFirestoreBackgroundChecker.checkForNewMessagesAndAlerts(appCtx)
            } catch (_: Exception) {
                needsReschedule = true
            } finally {
                if (!MainActivity.isActivityInForeground) {
                    val prefs = appCtx.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                    val muted = prefs.getBoolean("push_muted", false)
                    val alertsEnabled = prefs.getBoolean("alert_notifs_enabled", true)
                    if (!muted && alertsEnabled) {
                        JeewanNotificationScheduler.scheduleQuickCatchUp(appCtx, 90_000L)
                    }
                }
                jobFinished(params, needsReschedule)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        activeJob?.cancel()
        return true
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}

object JeewanNotificationScheduler {
    private const val PERIODIC_JOB_ID = 7101
    private const val CATCHUP_JOB_ID = 7102

    fun schedulePeriodic(context: Context) {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean("push_muted", false) || !prefs.getBoolean("alert_notifs_enabled", true)) {
            return
        }
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
        val existing = scheduler.getPendingJob(PERIODIC_JOB_ID)
        if (existing != null) return

        val component = ComponentName(context, JeewanNotificationJobService::class.java)
        val info = JobInfo.Builder(PERIODIC_JOB_ID, component)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(15L * 60L * 1000L)
            .setPersisted(true)
            .build()
        runCatching { scheduler.schedule(info) }
    }

    fun scheduleQuickCatchUp(context: Context, delayMs: Long = 60_000L) {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean("push_muted", false) || !prefs.getBoolean("alert_notifs_enabled", true)) {
            return
        }
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
        val component = ComponentName(context, JeewanNotificationJobService::class.java)
        val clampedDelay = delayMs.coerceAtLeast(30_000L)
        val info = JobInfo.Builder(CATCHUP_JOB_ID, component)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(clampedDelay)
            .setOverrideDeadline(clampedDelay + 120_000L)
            .build()
        runCatching { scheduler.schedule(info) }
    }

    fun cancelAll(context: Context) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
        runCatching {
            scheduler.cancel(PERIODIC_JOB_ID)
            scheduler.cancel(CATCHUP_JOB_ID)
        }
    }
}

object JeewanFirestoreBackgroundChecker {
    private const val PROJECT_ID = "jeewan-city"
    private const val API_KEY = "AIzaSyD7S2ekPA2B6uMoqg621qaITRW9KqhTz1s"
    private const val RUN_QUERY_URL =
        "https://firestore.googleapis.com/v1/projects/$PROJECT_ID/databases/(default)/documents:runQuery?key=$API_KEY"
    private const val ALERT_DOC_URL =
        "https://firestore.googleapis.com/v1/projects/$PROJECT_ID/databases/(default)/documents/jeewan_city_alerts/current?key=$API_KEY"
    private const val MAX_STORED_IDS = 120

    internal data class ParsedPost(
        val id: String,
        val authorId: String,
        val authorName: String,
        val tag: String,
        val message: String,
        val mediaType: String,
        val deletedForEveryone: Boolean,
        val timestampMs: Long
    )

    fun checkForNewMessagesAndAlerts(context: Context): Int {
        if (MainActivity.isActivityInForeground) {
            return 0
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return 0
        }
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean("push_muted", false) || !prefs.getBoolean("alert_notifs_enabled", true)) {
            return 0
        }

        val idToken = prefs.getString("firebase_id_token", "").orEmpty().trim()
        var notifiedCount = 0

        val queryPayload = JSONObject().apply {
            put("structuredQuery", JSONObject().apply {
                put("from", JSONArray().put(JSONObject().put("collectionId", "jeewan_city_posts")))
                put("orderBy", JSONArray().put(JSONObject().apply {
                    put("field", JSONObject().put("fieldPath", "createdAt"))
                    put("direction", "DESCENDING")
                }))
                put("limit", 12)
            })
        }.toString()

        val postsJson = executeHttpRequest(RUN_QUERY_URL, "POST", queryPayload, idToken)
        if (postsJson != null) {
            notifiedCount += processPostsQueryResponse(context, postsJson)
        }

        val alertJson = executeHttpRequest(ALERT_DOC_URL, "GET", null, idToken)
        if (alertJson != null) {
            if (processAlertDocResponse(context, alertJson)) {
                notifiedCount++
            }
        }
        return notifiedCount
    }

    internal fun processPostsQueryResponse(context: Context, responseJson: String): Int {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean("push_muted", false) || !prefs.getBoolean("alert_notifs_enabled", true)) {
            return 0
        }

        val array = runCatching { JSONArray(responseJson) }.getOrNull() ?: return 0
        val parsedPosts = mutableListOf<ParsedPost>()

        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: continue
            val doc = row.optJSONObject("document") ?: continue
            val docName = doc.optString("name", "")
            val docId = docName.substringAfterLast('/').trim()
            if (docId.isEmpty()) continue

            val fields = doc.optJSONObject("fields") ?: JSONObject()
            val deletedForEveryone = readBooleanField(fields, "deletedForEveryone")
            val authorId = readStringField(fields, "authorId")
            val authorName = readStringField(fields, "authorName")
                .ifEmpty { readStringField(fields, "senderName") }
                .ifEmpty { "Neighbor" }
            val tag = readStringField(fields, "tag").ifEmpty { "General Chat" }
            val message = readStringField(fields, "message")
            val mediaType = readStringField(fields, "mediaType")

            val postTs = readTimestampMs(fields, doc.optString("createTime", ""))
            parsedPosts.add(
                ParsedPost(
                    id = docId,
                    authorId = authorId,
                    authorName = authorName,
                    tag = tag,
                    message = message,
                    mediaType = mediaType,
                    deletedForEveryone = deletedForEveryone,
                    timestampMs = postTs
                )
            )
        }

        if (parsedPosts.isEmpty()) return 0

        val lastNotifiedTs = prefs.getLong("last_notified_ts", 0L)
        val existingIds = prefs.getStringSet("notified_post_ids", emptySet()).orEmpty().toMutableSet()
        val readIds = prefs.getStringSet("read_post_ids", emptySet()).orEmpty()
        val maxPostTs = parsedPosts.maxOfOrNull { it.timestampMs } ?: 0L

        // First-run safety baseline: record existing history without replaying old posts as fake alerts.
        if (lastNotifiedTs <= 0L) {
            val baseline = maxOf(System.currentTimeMillis(), maxPostTs)
            parsedPosts.forEach { existingIds.add(it.id) }
            prefs.edit()
                .putLong("last_notified_ts", baseline)
                .putStringSet("notified_post_ids", trimStoredIds(existingIds))
                .apply()
            return 0
        }

        val myUid = prefs.getString("current_uid", "").orEmpty()
            .ifEmpty { prefs.getString("fcm_uid", "").orEmpty() }
            .trim()
        val myName = prefs.getString("current_user_name", "").orEmpty().trim()

        val unseenNew = parsedPosts
            .filter { p ->
                !p.deletedForEveryone &&
                    p.timestampMs > lastNotifiedTs &&
                    !existingIds.contains(p.id) &&
                    !readIds.contains(p.id) &&
                    !isSelfPost(p, myUid, myName)
            }
            .sortedBy { it.timestampMs }

        var newestTs = lastNotifiedTs
        var notified = 0
        val toDisplay = if (unseenNew.size > 5) unseenNew.takeLast(5) else unseenNew

        for (post in toDisplay) {
            val title = "${post.authorName} • ${post.tag}"
            val body = post.message.ifBlank {
                if (post.mediaType.isNotBlank()) "Shared a ${post.mediaType} update" else "New message in Jeewan City group"
            }
            MainActivity.showInteractiveMessageNotificationCompat(
                context = context,
                postId = post.id,
                title = title,
                body = body
            )
            notified++
        }

        for (post in parsedPosts) {
            if (post.timestampMs > newestTs) newestTs = post.timestampMs
            if (unseenNew.any { it.id == post.id } || post.timestampMs <= lastNotifiedTs) {
                existingIds.add(post.id)
            }
        }

        prefs.edit()
            .putLong("last_notified_ts", newestTs)
            .putStringSet("notified_post_ids", trimStoredIds(existingIds))
            .apply()

        return notified
    }

    internal fun processAlertDocResponse(context: Context, responseJson: String): Boolean {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean("push_muted", false) || !prefs.getBoolean("alert_notifs_enabled", true)) {
            return false
        }
        val doc = runCatching { JSONObject(responseJson) }.getOrNull() ?: return false
        val fields = doc.optJSONObject("fields") ?: return false

        val isActive = if (fields.has("active")) readBooleanField(fields, "active") else true
        if (!isActive) return false

        val phase = readStringField(fields, "phase").ifEmpty { "Jeewan City" }
        val date = readStringField(fields, "date")
        val startTime = readStringField(fields, "startTime")
        val endTime = readStringField(fields, "endTime")
        val details = readStringField(fields, "details")
        val updateTimeMs = readTimestampMs(fields, doc.optString("updateTime", ""))

        val alertSignature = "$phase|$date|$startTime|$endTime|$details"
        if (alertSignature.replace("|", "").isBlank()) return false

        val prevSignature = prefs.getString("last_power_alert_sig", "").orEmpty()
        val lastNotifiedTs = prefs.getLong("last_notified_ts", 0L)

        if (prevSignature.isEmpty()) {
            prefs.edit().putString("last_power_alert_sig", alertSignature).apply()
            // Only notify on first encounter if it is genuinely newer than our last known baseline
            if (lastNotifiedTs <= 0L || updateTimeMs <= lastNotifiedTs) {
                return false
            }
        } else if (prevSignature == alertSignature) {
            return false
        } else {
            prefs.edit().putString("last_power_alert_sig", alertSignature).apply()
            if (updateTimeMs > 0L && lastNotifiedTs > 0L && updateTimeMs <= lastNotifiedTs) {
                return false
            }
        }

        val title = "⚡ Power Alert • $phase"
        val body = buildString {
            if (date.isNotBlank()) append("$date: ")
            if (startTime.isNotBlank() || endTime.isNotBlank()) {
                append("$startTime - $endTime")
            }
            if (details.isNotBlank()) {
                if (isNotEmpty()) append(" • ")
                append(details)
            }
        }.ifBlank { "Scheduled electricity update in Jeewan City Sahiwal." }

        MainActivity.showInteractiveMessageNotificationCompat(
            context = context,
            postId = "power-alert-${alertSignature.hashCode()}",
            title = title,
            body = body
        )
        return true
    }

    private fun executeHttpRequest(
        urlStr: String,
        method: String,
        body: String?,
        idToken: String
    ): String? {
        val firstAttempt = performSingleHttpCall(urlStr, method, body, idToken)
        if (firstAttempt.first in 200..299) {
            return firstAttempt.second
        }
        // Retry without bearer token if an expired ID token caused 401/403 on public read collections
        if (idToken.isNotEmpty() && (firstAttempt.first == 401 || firstAttempt.first == 403)) {
            val fallback = performSingleHttpCall(urlStr, method, body, "")
            if (fallback.first in 200..299) {
                return fallback.second
            }
        }
        return null
    }

    private fun performSingleHttpCall(
        urlStr: String,
        method: String,
        body: String?,
        idToken: String
    ): Pair<Int, String?> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 12_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/json")
                if (idToken.isNotEmpty()) {
                    setRequestProperty("Authorization", "Bearer $idToken")
                }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                }
            }
            if (body != null) {
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(body)
                    writer.flush()
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }
            code to text
        } catch (_: Exception) {
            -1 to null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun isSelfPost(post: ParsedPost, myUid: String, myName: String): Boolean {
        if (myUid.isNotEmpty() && post.authorId.isNotEmpty()) {
            return post.authorId == myUid
        }
        if (myUid.isEmpty() && myName.isNotEmpty() && post.authorName.equals(myName, ignoreCase = true)) {
            return true
        }
        return false
    }

    private fun readStringField(fields: JSONObject, key: String): String {
        val obj = fields.optJSONObject(key) ?: return ""
        return obj.optString("stringValue", "").trim()
    }

    private fun readBooleanField(fields: JSONObject, key: String): Boolean {
        val obj = fields.optJSONObject(key) ?: return false
        return obj.optBoolean("booleanValue", false)
    }

    private fun readTimestampMs(fields: JSONObject, docCreateTime: String): Long {
        val createdAtObj = fields.optJSONObject("createdAt")
        val tsStr = createdAtObj?.optString("timestampValue", "").orEmpty().ifEmpty { docCreateTime }
        val fromIso = parseIsoTimestampMs(tsStr)
        if (fromIso > 0L) return fromIso

        for (numKey in listOf("createdAtMs", "clientTimestamp", "updatedAtMs")) {
            val numObj = fields.optJSONObject(numKey) ?: continue
            val intVal = numObj.optString("integerValue", "").toLongOrNull()
            if (intVal != null && intVal > 0L) return intVal
            if (numObj.has("doubleValue")) {
                val dblVal = numObj.optDouble("doubleValue", 0.0).toLong()
                if (dblVal > 0L) return dblVal
            }
        }
        return 0L
    }

    internal fun parseIsoTimestampMs(raw: String): Long {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return 0L
        // Normalize fractional seconds to 3-digit millis for SimpleDateFormat compatibility across APIs
        val normalized = Regex("""(\.\d{3})\d+""").replace(trimmed, "$1")
        val patterns = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        for (pattern in patterns) {
            val parsed = runCatching {
                val sdf = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                sdf.parse(normalized)?.time ?: 0L
            }.getOrDefault(0L)
            if (parsed > 0L) return parsed
        }
        return 0L
    }

    private fun trimStoredIds(ids: Set<String>): Set<String> {
        if (ids.size <= MAX_STORED_IDS) return ids.toSet()
        return ids.toList().takeLast(MAX_STORED_IDS).toSet()
    }
}
