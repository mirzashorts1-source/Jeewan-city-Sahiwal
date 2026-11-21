package com.example

import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Jeewan City", appName)
  }

  @Test
  fun `asset html exists and uses updated phone number`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val html = context.assets.open("index.html").bufferedReader().use { it.readText() }
    assertTrue(html.contains("03029815152"))
    assertTrue(html.contains("03016929697"))
    assertFalse(html.contains("tel:030103029815152"))
    assertFalse(html.contains("0301-6929697"))
    assertFalse(html.contains("🔲"))
    assertTrue(html.contains("id=\"statLiveOnlineCount\""))
    assertTrue(html.contains("id=\"statTotalDownloadsCount\""))
    assertTrue(html.contains("id=\"statTotalOpensCount\""))
    assertTrue(html.contains("jeewan_city_meta"))
    assertTrue(html.contains("jeewan_city_presence"))
    assertTrue(html.contains("Slide 1 of 5"))
    assertTrue(html.contains("5 • Developed By"))
    assertTrue(html.contains("id=\"shopDetailModal\""))
    assertTrue(html.contains("window.openShopDetail"))
    assertTrue(html.contains("window.openShopFullScreenImage"))
    assertTrue(html.contains("id=\"feedbackModal\""))
    assertTrue(html.contains("id=\"adminFeedbackCard\""))
    assertTrue(html.contains("window.submitUserFeedback"))
    assertTrue(html.contains("window.toggleFeedbackReviewed"))
    assertTrue(html.contains("window.deleteUserFeedback"))
    assertTrue(html.contains("""<meta name="google-adsense-account" content="ca-pub-8997992294391174">"""))
    assertTrue(html.contains("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js?client=ca-pub-8997992294391174"))
    assertTrue(html.contains("id=\"onboardingModal\""))
    assertTrue(html.contains("window.openOnboardingCarousel"))
    assertTrue(html.contains("window.finishOnboarding"))
    assertTrue(html.contains("firebase-messaging.js"))
    assertTrue(html.contains("window.openPostFromNotification"))
    assertTrue(html.contains("window.refreshNotificationStatusUI"))
    assertTrue(html.contains("getJeewanMediaFolderMeta"))
    assertTrue(html.contains("saveMediaToFolder"))
    assertTrue(html.contains("JeewanCity_Photo_"))
    assertTrue(html.contains("JeewanCity_Video_"))
    assertTrue(html.contains("APP_LOAD_TIME_MS"))
    assertTrue(html.contains("LAST_NOTIFIED_TS_KEY"))
    assertTrue(html.contains("hasServerBaseline"))
    assertTrue(html.contains("VAPID_KEY"))
    assertTrue(html.contains("\"/firebase-messaging-sw.js\""))
    assertTrue(html.contains("\"fcm_tokens\""))
    assertTrue(html.contains("syncPushConfig"))
    assertTrue(html.contains("formatChatMessageHtml"))
    assertTrue(html.contains("openChatExternalLink"))
    assertTrue(html.contains("copyPostLink"))
    assertTrue(html.contains("forwardPostToChat"))
    assertTrue(html.contains("confirmForwardToChat"))
    assertTrue(html.contains("id=\"forwardPreviewBar\""))
    assertTrue(html.contains("openExternalUrl"))
    assertTrue(html.contains("syncNativeNotificationBridgeState"))
    assertTrue(html.contains("flushPendingNotificationActions"))
    assertTrue(html.contains("isAppInForeground"))
    assertTrue(html.contains("hasAlertServerBaseline"))
    assertTrue(html.contains("FACEBOOK_APP_ID = \"2652000941902639\""))
    assertTrue(html.contains("syncAuthenticatedUserProfileToFirestore"))
    assertTrue(html.contains("FacebookAuthProvider"))
    assertFalse(html.contains("b0ae412a0da51c5ffb8bca577060f57f"))
    assertFalse(html.contains("cdn.tailwindcss.com"))
    assertFalse(html.contains("fonts.googleapis.com"))
    assertTrue(html.contains("id=\"compiledTailwindOfflineCss\""))
    assertTrue(html.contains("system-ui, -apple-system"))

    val adsTxt = context.assets.open("ads.txt").bufferedReader().use { it.readText().trim() }
    assertEquals("google.com, pub-8997992294391174, DIRECT, f08c47fec0942fa0", adsTxt)

    val appAdsTxt = context.assets.open("app-ads.txt").bufferedReader().use { it.readText().trim() }
    assertEquals("google.com, pub-8997992294391174, DIRECT, f08c47fec0942fa0", appAdsTxt)

    val fcmSw = context.assets.open("firebase-messaging-sw.js").bufferedReader().use { it.readText() }
    assertTrue(fcmSw.contains("onBackgroundMessage"))
    assertTrue(fcmSw.contains("notificationclick"))
    assertTrue(fcmSw.contains("skipWaiting"))
    assertTrue(fcmSw.contains("clients.claim"))
    assertTrue(fcmSw.contains("icon:"))
    assertTrue(fcmSw.contains("badge:"))
    assertTrue(fcmSw.contains("vibrate:"))
    assertTrue(fcmSw.contains("394435713585"))
    assertTrue(fcmSw.contains("jeewan-city-shell-"))
    assertTrue(fcmSw.contains("jeewan-city-runtime-"))
    assertTrue(fcmSw.contains("addEventListener(\"fetch\""))
    assertTrue(fcmSw.contains("caches.open"))
  }

  @Test
  fun `test ads and admob sdk ads are completely removed while preserving adsense verification`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val html = context.assets.open("index.html").bufferedReader().use { it.readText() }
    assertFalse(html.contains("3940256099942544"))
    assertFalse(html.contains("maybeShowInterstitial"))
    assertTrue(html.contains("cached.ads.filter(ad => ad && !ad.isSample)"))
    assertTrue(html.contains("id=\"sponsoredAdManagerCard\""))
    assertTrue(html.contains("""<meta name="google-adsense-account" content="ca-pub-8997992294391174">"""))

    val admobAppIdRes = context.resources.getIdentifier("admob_app_id", "string", context.packageName)
    val admobBannerRes = context.resources.getIdentifier("admob_banner_ad_unit_id", "string", context.packageName)
    val admobInterstitialRes = context.resources.getIdentifier("admob_interstitial_ad_unit_id", "string", context.packageName)
    assertEquals(0, admobAppIdRes)
    assertEquals(0, admobBannerRes)
    assertEquals(0, admobInterstitialRes)

    @Suppress("DEPRECATION")
    val appInfo = context.packageManager.getApplicationInfo(
      context.packageName,
      PackageManager.GET_META_DATA
    )
    assertEquals(null, appInfo.metaData?.getString("com.google.android.gms.ads.APPLICATION_ID"))
  }

  @Test
  fun `adservices permissions are removed from merged manifest`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    @Suppress("DEPRECATION")
    val pkgInfo = context.packageManager.getPackageInfo(
      context.packageName,
      PackageManager.GET_PERMISSIONS
    )
    val requested = pkgInfo.requestedPermissions?.toSet().orEmpty()
    assertFalse(requested.contains("android.permission.ACCESS_ADSERVICES_AD_ID"))
    assertFalse(requested.contains("android.permission.ACCESS_ADSERVICES_ATTRIBUTION"))
    assertFalse(requested.contains("android.permission.ACCESS_ADSERVICES_TOPICS"))
  }

  @Test
  fun `main activity launches and handles notification bridge`() {
    val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
    assertFalse(activity.isFinishing)
    val bridge = activity.AndroidBridge(activity)
    val statusJson = bridge.getNotificationStatus()
    assertTrue(statusJson.contains("notificationsEnabled"))
    assertTrue(statusJson.contains("ignoringBatteryOptimizations"))
    bridge.syncFcmToken("test-token-123", "uid-abc")
    val prefs = activity.getSharedPreferences("jeewan_prefs", Context.MODE_PRIVATE)
    assertEquals("test-token-123", prefs.getString("fcm_token", ""))
    assertEquals("uid-abc", prefs.getString("fcm_uid", ""))

    bridge.syncPushConfig("uid-xyz", "token-999", true)
    assertEquals("token-999", prefs.getString("fcm_token", ""))
    assertEquals("uid-xyz", prefs.getString("fcm_uid", ""))
    assertTrue(prefs.getBoolean("push_muted", false))
    assertEquals("jeewan_city_chat", prefs.getString("fcm_topic", ""))
  }

  @Test
  fun `media download folder routing organizes into Jeewan City Sahiwal folders`() {
    val img = MainActivity.resolveMediaDestination("image/jpeg", "photo.jpg")
    assertEquals(Environment.DIRECTORY_PICTURES, img.publicDir)
    assertEquals("${Environment.DIRECTORY_PICTURES}/Jeewan City Sahiwal/Jeewan City Images", img.relativePath)
    assertTrue(img.isGalleryImage)

    val vid = MainActivity.resolveMediaDestination("video/mp4", "clip.mp4")
    assertEquals(Environment.DIRECTORY_MOVIES, vid.publicDir)
    assertEquals("${Environment.DIRECTORY_MOVIES}/Jeewan City Sahiwal/Jeewan City Videos", vid.relativePath)
    assertTrue(vid.isGalleryVideo)

    val aud = MainActivity.resolveMediaDestination("audio/mp4", "voice.m4a")
    assertEquals(Environment.DIRECTORY_DOWNLOADS, aud.publicDir)
    assertEquals("${Environment.DIRECTORY_DOWNLOADS}/Jeewan City Sahiwal/Voice Notes", aud.relativePath)

    val doc = MainActivity.resolveMediaDestination("application/pdf", "notice.pdf")
    assertEquals(Environment.DIRECTORY_DOWNLOADS, doc.publicDir)
    assertEquals("${Environment.DIRECTORY_DOWNLOADS}/Jeewan City Sahiwal/Documents", doc.relativePath)

    val sanitized = MainActivity.sanitizeSubFolder("../../Jeewan City Sahiwal/Documents/")
    assertEquals("Jeewan City Sahiwal/Documents", sanitized)
  }

  @Test
  fun `external url normalization upgrades www links and blocks unsafe schemes`() {
    assertEquals("https://www.google.com/maps", MainActivity.normalizeExternalUrl("www.google.com/maps"))
    assertEquals("https://jeewan-city.web.app", MainActivity.normalizeExternalUrl("https://jeewan-city.web.app"))
    assertEquals("tel:03029815152", MainActivity.normalizeExternalUrl("tel:03029815152"))
    assertEquals("mailto:info@jeewancity.pk", MainActivity.normalizeExternalUrl("mailto:info@jeewancity.pk"))
    assertEquals("", MainActivity.normalizeExternalUrl("javascript:alert(1)"))
    assertEquals("", MainActivity.normalizeExternalUrl("data:text/html,<script>"))
  }

  @Test
  fun `background notification checker prevents initial replay and notifies only for genuinely new posts`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit().clear().apply()
    prefs.edit()
      .putString("current_uid", "my-uid")
      .putString("current_user_name", "Bilal")
      .putBoolean("alert_notifs_enabled", true)
      .putBoolean("push_muted", false)
      .apply()

    val oldTs = System.currentTimeMillis() - 120_000L
    val initialJson = """
      [
        {
          "document": {
            "name": "projects/jeewan-city/databases/(default)/documents/jeewan_city_posts/old-post-1",
            "fields": {
              "authorId": {"stringValue": "neighbor-1"},
              "authorName": {"stringValue": "Ali"},
              "tag": {"stringValue": "General Chat"},
              "message": {"stringValue": "Old message"},
              "createdAtMs": {"integerValue": "$oldTs"}
            }
          }
        }
      ]
    """.trimIndent()

    // First run sets baseline without replaying old message
    val firstCount = JeewanFirestoreBackgroundChecker.processPostsQueryResponse(context, initialJson)
    assertEquals(0, firstCount)
    val baselineTs = prefs.getLong("last_notified_ts", 0L)
    assertTrue(baselineTs > 0L)

    // Self post and deleted post after baseline must not trigger notifications
    val t1 = baselineTs + 1_000L
    val selfAndDeletedJson = """
      [
        {
          "document": {
            "name": "projects/jeewan-city/databases/(default)/documents/jeewan_city_posts/self-post-1",
            "fields": {
              "authorId": {"stringValue": "my-uid"},
              "authorName": {"stringValue": "Bilal"},
              "tag": {"stringValue": "General Chat"},
              "message": {"stringValue": "My own msg"},
              "createdAtMs": {"integerValue": "$t1"}
            }
          }
        },
        {
          "document": {
            "name": "projects/jeewan-city/databases/(default)/documents/jeewan_city_posts/deleted-post-1",
            "fields": {
              "authorId": {"stringValue": "neighbor-1"},
              "authorName": {"stringValue": "Ali"},
              "deletedForEveryone": {"booleanValue": true},
              "message": {"stringValue": "Deleted"},
              "createdAtMs": {"integerValue": "${t1 + 500L}"}
            }
          }
        }
      ]
    """.trimIndent()
    assertEquals(0, JeewanFirestoreBackgroundChecker.processPostsQueryResponse(context, selfAndDeletedJson))

    // Genuinely new post from neighbor must trigger 1 notification and deduplicate thereafter
    val t2 = baselineTs + 5_000L
    val newPostJson = """
      [
        {
          "document": {
            "name": "projects/jeewan-city/databases/(default)/documents/jeewan_city_posts/new-post-1",
            "fields": {
              "authorId": {"stringValue": "neighbor-2"},
              "authorName": {"stringValue": "Usman"},
              "tag": {"stringValue": "Power Update"},
              "message": {"stringValue": "Feeder restored in Phase 2"},
              "createdAtMs": {"integerValue": "$t2"}
            }
          }
        }
      ]
    """.trimIndent()
    assertEquals(1, JeewanFirestoreBackgroundChecker.processPostsQueryResponse(context, newPostJson))
    assertEquals(0, JeewanFirestoreBackgroundChecker.processPostsQueryResponse(context, newPostJson))
    assertTrue(prefs.getStringSet("notified_post_ids", emptySet())!!.contains("new-post-1"))
  }

  @Test
  fun `background checker parses iso timestamps and bridges notification state`() {
    val parsedMicros = JeewanFirestoreBackgroundChecker.parseIsoTimestampMs("2026-11-21T09:40:00.123456Z")
    val parsedPlain = JeewanFirestoreBackgroundChecker.parseIsoTimestampMs("2026-11-21T09:40:00Z")
    assertTrue(parsedMicros > 0L)
    assertTrue(parsedPlain > 0L)
    assertEquals(123L, parsedMicros - parsedPlain)

    val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
    val bridge = activity.AndroidBridge(activity)
    assertTrue(bridge.isAppInForeground())

    val syncTs = System.currentTimeMillis()
    bridge.syncNotificationState(syncTs, "uid-101", "Hamza", "tok-1", true, false)
    val prefs = activity.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
    assertEquals("uid-101", prefs.getString("current_uid", ""))
    assertEquals("Hamza", prefs.getString("current_user_name", ""))
    assertEquals(syncTs, prefs.getLong("last_notified_ts", 0L))

    bridge.markPostNotified("post-xyz", syncTs + 2500L)
    assertTrue(prefs.getStringSet("notified_post_ids", emptySet())!!.contains("post-xyz"))
    assertEquals(syncTs + 2500L, prefs.getLong("last_notified_ts", 0L))
  }

  @Test
  fun `facebook client app id is configured without exposing app secret and oauth stays in webview`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    assertEquals("2652000941902639", context.getString(R.string.facebook_app_id))
    assertEquals("fb2652000941902639", context.getString(R.string.fb_login_protocol_scheme))

    assertTrue(MainActivity.isOAuthFlowUrl("https://m.facebook.com/login.php?next=https%3A%2F%2Fwww.facebook.com%2Fv19.0%2Fdialog%2Foauth"))
    assertTrue(MainActivity.isOAuthFlowUrl("https://www.facebook.com/v19.0/dialog/oauth?client_id=2652000941902639"))
    assertTrue(MainActivity.isOAuthFlowUrl("https://m.facebook.com/checkpoint/?next"))
    assertTrue(MainActivity.isOAuthFlowUrl("https://jeewan-city.firebaseapp.com/__/auth/handler"))
    assertTrue(MainActivity.isOAuthFlowUrl("https://accounts.google.com/o/oauth2/v2/auth"))
    assertFalse(MainActivity.isOAuthFlowUrl("https://www.facebook.com/jeewancitysahiwal"))
  }
}
