package com.example.smsforwarder

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MainActivityTest {
    @Test
    fun activityStarts() {
        val application = ApplicationProvider.getApplicationContext<SmsForwarderApp>()
        ShadowApplication.getInstance().grantPermissions(Manifest.permission.RECEIVE_SMS)

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        assertNotNull(activity.findViewById(R.id.buttonSave))
    }
}
