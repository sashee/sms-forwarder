package com.example.smsforwarder.net

import androidx.test.core.app.ApplicationProvider
import okhttp3.Dns
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.UnknownHostException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EventHttpClientTest {
    @Test
    fun sendsHttpRequestsWithConfiguredMethodBodyAndContentType() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(201))
            server.start()

            val client = clientForLocalhost()
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
            assertTrue(request.getHeader("Content-Type")!!.startsWith("text/plain"))
            assertEquals("hello", request.body.readUtf8())
        }
    }

    @Test
    fun sendsGetWithoutBody() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            server.start()

            val client = clientForLocalhost()
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

            val client = clientForLocalhost()

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

            val client = clientForLocalhost(
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

            val client = clientForLocalhost(
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

            val client = clientForLocalhost(
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

    @Test
    fun fallsBackToNextDohProviderAndLogsFailure() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(202))
            server.start()

            val dohFailures = mutableListOf<String>()
            val client = EventHttpClient(
                context = ApplicationProvider.getApplicationContext(),
                onDohFailure = dohFailures::add,
                dohEndpoints = listOf(
                    EventHttpClient.DohEndpoint("Primary", "https://primary.example/dns-query", listOf("1.1.1.1")),
                    EventHttpClient.DohEndpoint("Secondary", "https://secondary.example/dns-query", listOf("8.8.8.8")),
                ),
                dohDnsFactory = { endpoint ->
                    when (endpoint.name) {
                        "Primary" -> ThrowingDns("primary down")
                        else -> MappingDns(mapOf("sms.example" to listOf("127.0.0.1")))
                    }
                },
            )

            val code = client.send(
                HttpRequest(
                    url = "http://sms.example:${server.port}/sms",
                    method = "POST",
                    contentType = "text/plain",
                    body = "hello",
                ),
            )

            assertEquals(202, code)
            assertEquals("hello", server.takeRequest().body.readUtf8())
            assertEquals(1, dohFailures.size)
            assertTrue(dohFailures.single().contains("DoH lookup failed via Primary for sms.example: primary down"))
        }
    }

    @Test
    fun bypassesDohForLiteralIpAddresses() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200))
            server.start()

            val dns = CountingDns()
            val client = EventHttpClient(
                context = ApplicationProvider.getApplicationContext(),
                dohEndpoints = listOf(
                    EventHttpClient.DohEndpoint("Unused", "https://unused.example/dns-query", listOf("1.1.1.1")),
                ),
                dohDnsFactory = { dns },
            )

            val code = client.send(
                HttpRequest(
                    url = "http://127.0.0.1:${server.port}/sms",
                    method = "POST",
                    contentType = "text/plain",
                    body = "hello",
                ),
            )

            assertEquals(200, code)
            assertEquals(0, dns.lookupCount)
        }
    }

    @Test
    fun throwsWhenAllDohProvidersFail() {
        val dohFailures = mutableListOf<String>()
        val client = EventHttpClient(
            context = ApplicationProvider.getApplicationContext(),
            onDohFailure = dohFailures::add,
            dohEndpoints = listOf(
                EventHttpClient.DohEndpoint("Cloudflare", "https://cloudflare-dns.com/dns-query", listOf("1.1.1.1")),
                EventHttpClient.DohEndpoint("Google", "https://dns.google/dns-query", listOf("8.8.8.8")),
            ),
            dohDnsFactory = { endpoint -> ThrowingDns("${endpoint.name} unavailable") },
        )

        try {
            client.send(
                HttpRequest(
                    url = "http://unreachable.example/test",
                    method = "GET",
                    contentType = "text/plain",
                    body = "",
                ),
            )
        } catch (error: Exception) {
            assertTrue(error.message?.contains("DoH lookup failed for unreachable.example") == true)
            assertEquals(2, dohFailures.size)
            assertTrue(dohFailures.first().contains("Cloudflare unavailable"))
            assertTrue(dohFailures.last().contains("Google unavailable"))
            return
        }

        error("Expected request to fail after all DoH providers failed")
    }

    @Test
    fun supportsIpv6BootstrapAndResolvedAddresses() {
        val dns = MappingDns(mapOf("localhost" to listOf("::1", "127.0.0.1")))
        val results = dns.lookup("localhost")

        assertFalse(results.isEmpty())
        assertTrue(results.any { it.hostAddress?.contains(':') == true })
        assertTrue(results.any { it.hostAddress == "127.0.0.1" })
    }

    private class ThrowingDns(private val message: String) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            throw UnknownHostException(message)
        }
    }

    private class CountingDns : Dns {
        var lookupCount: Int = 0

        override fun lookup(hostname: String): List<InetAddress> {
            lookupCount += 1
            throw UnknownHostException(hostname)
        }
    }

    private class MappingDns(private val mappings: Map<String, List<String>>) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = mappings[hostname] ?: throw UnknownHostException(hostname)
            return addresses.map(InetAddress::getByName)
        }
    }

    private fun clientForLocalhost(
        trustAnchorOpener: (() -> ByteArrayInputStream)? = null,
    ): EventHttpClient {
        return EventHttpClient(
            context = ApplicationProvider.getApplicationContext(),
            trustAnchorOpener = trustAnchorOpener,
            dohEndpoints = listOf(
                EventHttpClient.DohEndpoint("Local", "https://local.test/dns-query", listOf("127.0.0.1", "::1")),
            ),
            dohDnsFactory = {
                MappingDns(mapOf("localhost" to listOf("127.0.0.1", "::1")))
            },
        )
    }
}
