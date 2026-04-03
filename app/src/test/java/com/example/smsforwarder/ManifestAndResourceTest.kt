package com.example.smsforwarder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ManifestAndResourceTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val packageManager: PackageManager = context.packageManager

    @Test
    fun manifestDeclaresRequiredPermissionsAndComponents() {
        val packageInfo = packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS or PackageManager.GET_RECEIVERS or PackageManager.GET_SERVICES)
        val permissions = packageInfo.requestedPermissions.toSet()

        assertTrue(permissions.contains(Manifest.permission.RECEIVE_SMS))
        assertTrue(permissions.contains(Manifest.permission.RECEIVE_BOOT_COMPLETED))
        assertTrue(permissions.contains(Manifest.permission.INTERNET))
        assertTrue(packageInfo.receivers.any { it.name.endsWith("SmsReceiver") })
        assertTrue(packageInfo.receivers.any { it.name.endsWith("BootReceiver") })
        assertTrue(packageInfo.services.any { it.name.endsWith("ForwardingCallScreeningService") })
    }

    @Test
    fun rawCaBundleResourceIsPackaged() {
        context.resources.openRawResource(R.raw.nixpkgs_cacert).use {
            assertNotNull(it.readBytes())
        }
    }
}
