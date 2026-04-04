package com.example.smsforwarder.net

import android.content.Context
import android.util.Log
import com.example.smsforwarder.R
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.HttpUrl.Companion.toHttpUrl

data class HttpRequest(
    val url: String,
    val method: String,
    val contentType: String,
    val body: String,
)

interface HttpSender {
    fun send(request: HttpRequest): Int
}

open class EventHttpClient(
    context: Context,
    private val trustAnchorResourceId: Int = R.raw.nixpkgs_cacert,
    private val trustAnchorOpener: (() -> InputStream)? = null,
    private val onDohFailure: ((String) -> Unit)? = null,
    private val dohEndpoints: List<DohEndpoint> = DEFAULT_DOH_ENDPOINTS,
    private val dohDnsFactory: ((DohEndpoint) -> Dns)? = null,
) : HttpSender {
    private val appContext = context.applicationContext
    private val trustManager by lazy { buildTrustManager() }
    private val sslSocketFactory by lazy { buildSslContext(trustManager).socketFactory }
    private val client by lazy { buildClient() }

    override fun send(request: HttpRequest): Int {
        val normalizedMethod = request.method.uppercase()
        val requestBuilder = Request.Builder()
            .url(request.url)
            .header("Content-Type", request.contentType)

        val requestBody = when (normalizedMethod) {
            "GET", "HEAD" -> null
            else -> request.body.toRequestBody(request.contentType.toMediaTypeOrNull())
        }
        requestBuilder.method(normalizedMethod, requestBody)

        return client.newCall(requestBuilder.build()).execute().use { response ->
            response.code
        }
    }

    private fun buildClient(): OkHttpClient {
        val dns = ChainedDohDns(
            providers = dohEndpoints.map { endpoint ->
                DohProvider(endpoint.name, dohDnsFactory?.invoke(endpoint) ?: buildDohDns(endpoint))
            },
            onFailure = ::logDohFailure,
        )
        return OkHttpClient.Builder()
            .sslSocketFactory(sslSocketFactory, trustManager)
            .dns(dns)
            .build()
    }

    private fun buildDohDns(endpoint: DohEndpoint): Dns {
        val bootstrapAddresses = endpoint.bootstrapAddresses.map(InetAddress::getByName)
        val bootstrapClient = OkHttpClient.Builder()
            .sslSocketFactory(sslSocketFactory, trustManager)
            .dns(BootstrapDns(endpoint.url.toHttpUrl().host, bootstrapAddresses))
            .build()

        return DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url(endpoint.url.toHttpUrl())
            .includeIPv6(true)
            .bootstrapDnsHosts(*bootstrapAddresses.toTypedArray())
            .build()
    }

    private fun logDohFailure(message: String) {
        Log.w(TAG, message)
        runCatching { onDohFailure?.invoke(message) }
    }

    private fun buildTrustManager(): X509TrustManager {
        val keyStore = buildKeyStore()
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagerFactory.init(keyStore)
        return trustManagerFactory.trustManagers.filterIsInstance<X509TrustManager>().single()
    }

    private fun buildSslContext(trustManager: X509TrustManager): SSLContext {
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), SecureRandom())
        }
    }

    private fun buildKeyStore(): KeyStore {
        val certificateFactory = CertificateFactory.getInstance("X.509")
        val certificates = openTrustAnchorStream().use { input -> loadCertificates(input, certificateFactory) }
        return KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            certificates.forEachIndexed { index, certificate ->
                setCertificateEntry("ca-$index", certificate)
            }
        }
    }

    private fun openTrustAnchorStream(): InputStream {
        return trustAnchorOpener?.invoke() ?: appContext.resources.openRawResource(trustAnchorResourceId)
    }

    private fun loadCertificates(input: InputStream, certificateFactory: CertificateFactory): Collection<Certificate> {
        val bundleBytes = input.readBytes()
        val bundleText = bundleBytes.toString(StandardCharsets.US_ASCII)
        val pemBlocks = PEM_CERTIFICATE_REGEX.findAll(bundleText).map { it.value }.toList()
        if (pemBlocks.isNotEmpty()) {
            return pemBlocks.map { pemBlock ->
                ByteArrayInputStream(pemBlock.toByteArray(StandardCharsets.US_ASCII)).use { pemInput ->
                    certificateFactory.generateCertificate(pemInput)
                }
            }
        }

        return certificateFactory.generateCertificates(BufferedInputStream(ByteArrayInputStream(bundleBytes)))
    }

    data class DohEndpoint(
        val name: String,
        val url: String,
        val bootstrapAddresses: List<String>,
    )

    private data class DohProvider(
        val name: String,
        val dns: Dns,
    )

    private class BootstrapDns(
        private val bootstrapHost: String,
        private val bootstrapAddresses: List<InetAddress>,
    ) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (hostname.equals(bootstrapHost, ignoreCase = true)) {
                return bootstrapAddresses
            }
            throw UnknownHostException("Bootstrap DNS only resolves $bootstrapHost")
        }
    }

    private class ChainedDohDns(
        private val providers: List<DohProvider>,
        private val onFailure: (String) -> Unit,
    ) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            literalIpAddress(hostname)?.let { return listOf(it) }

            var lastError: Exception? = null
            providers.forEach { provider ->
                try {
                    return provider.dns.lookup(hostname)
                } catch (error: Exception) {
                    onFailure("DoH lookup failed via ${provider.name} for $hostname: ${error.message ?: error::class.java.simpleName}")
                    lastError = error
                }
            }

            throw UnknownHostException("DoH lookup failed for $hostname").apply {
                lastError?.let(::initCause)
            }
        }

        private fun literalIpAddress(hostname: String): InetAddress? {
            if (!IPV4_LITERAL_REGEX.matches(hostname) && ':' !in hostname) {
                return null
            }
            return runCatching { InetAddress.getByName(hostname) }.getOrNull()
        }
    }

    companion object {
        private const val TAG = "EventHttpClient"
        private val IPV4_LITERAL_REGEX = Regex("\\d{1,3}(\\.\\d{1,3}){3}")
        private val PEM_CERTIFICATE_REGEX = Regex(
            "-----BEGIN CERTIFICATE-----\\s.*?-----END CERTIFICATE-----",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        private val DEFAULT_DOH_ENDPOINTS = listOf(
            DohEndpoint(
                name = "Cloudflare",
                url = "https://cloudflare-dns.com/dns-query",
                bootstrapAddresses = listOf(
                    "1.1.1.1",
                    "1.0.0.1",
                    "2606:4700:4700::1111",
                    "2606:4700:4700::1001",
                ),
            ),
            DohEndpoint(
                name = "Google",
                url = "https://dns.google/dns-query",
                bootstrapAddresses = listOf(
                    "8.8.8.8",
                    "8.8.4.4",
                    "2001:4860:4860::8888",
                    "2001:4860:4860::8844",
                ),
            ),
            DohEndpoint(
                name = "Quad9",
                url = "https://dns.quad9.net/dns-query",
                bootstrapAddresses = listOf(
                    "9.9.9.9",
                    "149.112.112.112",
                    "2620:fe::fe",
                    "2620:fe::9",
                ),
            ),
        )
    }
}
