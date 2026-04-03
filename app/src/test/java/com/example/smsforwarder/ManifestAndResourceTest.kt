package com.example.smsforwarder

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.example.smsforwarder.receiver.BootReceiver
import com.example.smsforwarder.receiver.CallStateReceiver
import com.example.smsforwarder.receiver.SmsReceiver
import com.example.smsforwarder.telecom.ForwardingCallScreeningService
import org.junit.Assert.assertEquals
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
        val service = packageInfo.services.single { it.name.endsWith("ForwardingCallScreeningService") }

        assertTrue(permissions.contains(Manifest.permission.RECEIVE_SMS))
        assertTrue(permissions.contains(Manifest.permission.READ_PHONE_STATE))
        assertTrue(permissions.contains(Manifest.permission.READ_CALL_LOG))
        assertTrue(permissions.contains(Manifest.permission.RECEIVE_BOOT_COMPLETED))
        assertTrue(permissions.contains(Manifest.permission.INTERNET))
        assertTrue(packageInfo.receivers.any { it.name.endsWith("SmsReceiver") })
        assertTrue(packageInfo.receivers.any { it.name.endsWith("CallStateReceiver") })
        assertTrue(packageInfo.receivers.any { it.name.endsWith("BootReceiver") })
        assertTrue(packageInfo.services.any { it.name.endsWith("ForwardingCallScreeningService") })
        assertEquals(Manifest.permission.BIND_SCREENING_SERVICE, service.permission)
    }

    @Test
    fun manifestRegistersExpectedIntentFiltersAndNetworkFlags() {
        val smsReceivers = packageManager.queryBroadcastReceivers(
            Intent("android.provider.Telephony.SMS_RECEIVED").setPackage(context.packageName),
            0,
        )
        val bootReceivers = packageManager.queryBroadcastReceivers(
            Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(context.packageName),
            0,
        )
        val phoneStateReceivers = packageManager.queryBroadcastReceivers(
            Intent("android.intent.action.PHONE_STATE").setPackage(context.packageName),
            0,
        )
        val lockedBootReceivers = packageManager.queryBroadcastReceivers(
            Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED).setPackage(context.packageName),
            0,
        )
        val serviceInfo = packageManager.getServiceInfo(
            ComponentName(context, ForwardingCallScreeningService::class.java),
            PackageManager.GET_META_DATA,
        )
        val applicationInfo = context.applicationInfo

        assertTrue(smsReceivers.any { it.activityInfo.name == SmsReceiver::class.java.name })
        assertTrue(phoneStateReceivers.any { it.activityInfo.name == CallStateReceiver::class.java.name })
        assertTrue(bootReceivers.any { it.activityInfo.name == BootReceiver::class.java.name })
        assertTrue(lockedBootReceivers.any { it.activityInfo.name == BootReceiver::class.java.name })
        assertEquals(Manifest.permission.BIND_SCREENING_SERVICE, serviceInfo.permission)
        assertTrue(applicationInfo.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC != 0)
        val networkSecurityConfigRes = ApplicationInfo::class.java.getDeclaredField("networkSecurityConfigRes").apply {
            isAccessible = true
        }.getInt(applicationInfo)
        assertEquals(R.xml.network_security_config, networkSecurityConfigRes)
    }

    @Test
    fun rawCaBundleResourceIsPackaged() {
        context.resources.openRawResource(R.raw.nixpkgs_cacert).use {
            assertNotNull(it.readBytes())
        }
    }
}
