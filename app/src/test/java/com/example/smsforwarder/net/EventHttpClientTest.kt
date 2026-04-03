package com.example.smsforwarder.net

import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EventHttpClientTest {
    @Test
    fun sendsHttpRequestsWithConfiguredMethodBodyAndContentType() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(201))
            server.start()

            val client = EventHttpClient(ApplicationProvider.getApplicationContext())
            val code = client.send(
                HttpRequest(
                    url = server.url("/sms").toString(),
                    method = "POST",
                    contentType = "text/plain",
                    body = "hello",
                ),
            )

            val request = server.takeRequest()
            assertEquals(201, code)
            assertEquals("POST", request.method)
            assertEquals("text/plain", request.getHeader("Content-Type"))
            assertEquals("hello", request.body.readUtf8())
        }
    }

    @Test
    fun sendsGetWithoutBody() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            server.start()

            val client = EventHttpClient(ApplicationProvider.getApplicationContext())
            val code = client.send(
                HttpRequest(
                    url = server.url("/heartbeat").toString(),
                    method = "GET",
                    contentType = "text/plain",
                    body = "ignored",
                ),
            )

            val request = server.takeRequest()
            assertEquals(204, code)
            assertEquals("GET", request.method)
            assertEquals("", request.body.readUtf8())
        }
    }

    @Test
    fun returnsHttpFailureResponseCode() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.start()

            val client = EventHttpClient(ApplicationProvider.getApplicationContext())

            assertEquals(
                503,
                client.send(
                    HttpRequest(
                        url = server.url("/failure").toString(),
                        method = "POST",
                        contentType = "text/plain",
                        body = "hello",
                    ),
                ),
            )
        }
    }

    @Test
    fun supportsHttpsWithInjectedTrustAnchor() {
        val root = HeldCertificate.Builder().certificateAuthority(0).commonName("Test Root").build()
        val serverCert = HeldCertificate.Builder()
            .signedBy(root)
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()

        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(serverCert)
            .build()

        MockWebServer().use { server ->
            server.useHttps(serverCertificates.sslSocketFactory(), false)
            server.enqueue(MockResponse().setResponseCode(200))
            server.start()

            val client = EventHttpClient(
                context = ApplicationProvider.getApplicationContext(),
                trustAnchorOpener = { ByteArrayInputStream(root.certificatePem().toByteArray()) },
            )

            assertEquals(
                200,
                client.send(
                    HttpRequest(
                        url = server.url("/secure").toString().replace("http://", "https://"),
                        method = "POST",
                        contentType = "text/plain",
                        body = "secure",
                    ),
                ),
            )
        }
    }

    @Test
    fun supportsHttpsWhenTrustBundleContainsMultiplePemCertificates() {
        val unusedRoot = HeldCertificate.Builder().certificateAuthority(0).commonName("Unused Root").build()
        val trustedRoot = HeldCertificate.Builder().certificateAuthority(0).commonName("Trusted Root").build()
        val serverCert = HeldCertificate.Builder()
            .signedBy(trustedRoot)
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()

        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(serverCert)
            .build()

        MockWebServer().use { server ->
            server.useHttps(serverCertificates.sslSocketFactory(), false)
            server.enqueue(MockResponse().setResponseCode(200))
            server.start()

            val client = EventHttpClient(
                context = ApplicationProvider.getApplicationContext(),
                trustAnchorOpener = {
                    ByteArrayInputStream(
                        (unusedRoot.certificatePem() + trustedRoot.certificatePem()).toByteArray(),
                    )
                },
            )

            assertEquals(
                200,
                client.send(
                    HttpRequest(
                        url = server.url("/secure").toString().replace("http://", "https://"),
                        method = "GET",
                        contentType = "text/plain",
                        body = "",
                    ),
                ),
            )
        }
    }

    @Test
    fun rejectsHttpsServerOutsideConfiguredTrustAnchor() {
        val trustedRoot = HeldCertificate.Builder().certificateAuthority(0).commonName("Trusted Root").build()
        val untrustedRoot = HeldCertificate.Builder().certificateAuthority(0).commonName("Untrusted Root").build()
        val serverCert = HeldCertificate.Builder()
            .signedBy(untrustedRoot)
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()

        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(serverCert)
            .build()

        MockWebServer().use { server ->
            server.useHttps(serverCertificates.sslSocketFactory(), false)
            server.enqueue(MockResponse().setResponseCode(200))
            server.start()

            val client = EventHttpClient(
                context = ApplicationProvider.getApplicationContext(),
                trustAnchorOpener = { ByteArrayInputStream(trustedRoot.certificatePem().toByteArray()) },
            )

            try {
                client.send(
                    HttpRequest(
                        url = server.url("/secure").toString().replace("http://", "https://"),
                        method = "GET",
                        contentType = "text/plain",
                        body = "",
                    ),
                )
            } catch (error: Exception) {
                assertTrue(error.message?.isNotBlank() == true)
                return
            }

            error("Expected HTTPS request to fail for untrusted certificate")
        }
    }
}
