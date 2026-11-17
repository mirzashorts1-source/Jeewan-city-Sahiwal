package com.example

import android.content.Context
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
    assertFalse(html.contains("tel:030103029815152"))
  }

  @Test
  fun `main activity launches without crashing`() {
    val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
    assertFalse(activity.isFinishing)
  }
}
