package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.bridge.AndroidActionBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Arushi AI", appName)
    }

    @Test
    fun `test action bridge initialization and makeCall`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bridge = AndroidActionBridge(context)
        val result = bridge.makeCall("9876543210")
        assertNotNull(result)
        // Without CALL_PHONE permission granted, should launch dialer gracefully
        assertEquals(true, result.isSuccess)
    }

    @Test
    fun `test action bridge openUrl`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bridge = AndroidActionBridge(context)
        val result = bridge.openUrl("google.com")
        assertNotNull(result)
        assertEquals(true, result.isSuccess)
    }
}
