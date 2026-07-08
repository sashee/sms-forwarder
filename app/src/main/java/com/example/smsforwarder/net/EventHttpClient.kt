package com.example.smsforwarder.net

import android.content.Context
import android.util.Log
import com.example.smsforwarder.R
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
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
    private val onConnection: ((String) -> Unit)? = null,
    private val dohEndpoints: List<DohEndpoint> = DEFAULT_DOH_ENDPOINTS,
    private val dohDnsFactory: ((DohEndpoint) -> Dns)? = null,
    private val randomizeDohOrder: Boolean = true,
) : HttpSender {
    private val appContext = context.applicationContext
    private val trustManager by lazy { buildTrustManager() }
    private val sslSocketFactory by lazy { buildSslContext(trustManager).socketFactory }
    // Per-call trace populated on the calling thread (synchronous execute): which DoH provider/addresses
    // resolved the host and which peer the socket actually connected to (IPv4 vs IPv6).
    private val callTrace = ThreadLocal<CallTrace?>()
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

        val trace = CallTrace()
        callTrace.set(trace)
        try {
            return client.newCall(requestBuilder.build()).execute().use { response ->
                logConnection(request, trace)
                response.code
            }
        } finally {
            callTrace.remove()
        }
    }

    private fun buildClient(): OkHttpClient {
        val dns = ChainedDohDns(
            providers = dohEndpoints.map { endpoint ->
                DohProvider(endpoint.name, dohDnsFactory?.invoke(endpoint) ?: buildDohDns(endpoint))
            },
            onFailure = ::logDohFailure,
            onResolved = { providerName, addresses ->
                callTrace.get()?.let { it.provider = providerName; it.resolved = addresses }
            },
            randomize = randomizeDohOrder,
        )
        return OkHttpClient.Builder()
            .sslSocketFactory(sslSocketFactory, trustManager)
            .dns(dns)
            .eventListener(ConnectionTraceListener(callTrace))
            .build()
    }

    private fun logConnection(request: HttpRequest, trace: CallTrace) {
        val callback = onConnection ?: return
        val host = runCatching { request.url.toHttpUrl().host }.getOrNull() ?: request.url
        val peer = trace.peer?.address
        val family = when (peer) {
            is Inet6Address -> "IPv6"
            is Inet4Address -> "IPv4"
            else -> "unknown"
        }
        val endpoint = peer?.hostAddress?.let { "$it ($family)" } ?: "reused/unknown connection"
        val via = trace.provider?.let { name ->
            val resolved = trace.resolved?.joinToString(", ") { it.hostAddress ?: it.toString() }
            " — resolved by $name" + (resolved?.let { " -> [$it]" } ?: "")
        } ?: ""
        val message = "Connection to $host used $endpoint$via"
        Log.i(TAG, message)
        runCatching { callback(message) }
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
        private val onResolved: (String, List<InetAddress>) -> Unit,
        private val randomize: Boolean,
    ) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            literalIpAddress(hostname)?.let {
                onResolved("literal", listOf(it))
                return listOf(it)
            }

            var lastError: Exception? = null
            // Randomize order per lookup so no single resolver is always primary — spreads load and lets
            // us measure each provider's failure rate across positions from the logs.
            val ordered = if (randomize) providers.shuffled() else providers
            ordered.forEach { provider ->
                try {
                    val addresses = provider.dns.lookup(hostname)
                    onResolved(provider.name, addresses)
                    return addresses
                } catch (error: Exception) {
                    onFailure("DoH lookup failed via ${provider.name} for $hostname: ${describeThrowable(error)}")
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

    /** Mutable per-call trace: the resolving DoH provider + addresses, and the peer the socket connected to. */
    private class CallTrace {
        @Volatile var provider: String? = null
        @Volatile var resolved: List<InetAddress>? = null
        @Volatile var peer: InetSocketAddress? = null
    }

    /** Records the actually-connected peer (authoritative for IPv4 vs IPv6) into the current call's trace. */
    private class ConnectionTraceListener(private val callTrace: ThreadLocal<CallTrace?>) : EventListener() {
        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
            callTrace.get()?.peer = inetSocketAddress
        }
    }

    companion object {
        private const val TAG = "EventHttpClient"

        /**
         * Renders a throwable and its full cause chain, e.g.
         * `UnknownHostException: host <- ConnectException: Network unreachable`.
         * OkHttp's [DnsOverHttps] throws an [UnknownHostException] whose message is only the hostname and
         * tucks the real reason (connect refused, TLS failure, timeout, ...) into [Throwable.cause], so the
         * chain is what actually explains a DoH failure. Cycle-safe via the [HashSet] guard.
         */
        internal fun describeThrowable(error: Throwable): String =
            generateSequence(error) { it.cause }
                .takeWhile(HashSet<Throwable>()::add)
                .joinToString(" <- ") { "${it::class.java.simpleName}: ${it.message ?: "(no message)"}" }

        private val IPV4_LITERAL_REGEX = Regex("\\d{1,3}(\\.\\d{1,3}){3}")
        private val PEM_CERTIFICATE_REGEX = Regex(
            "-----BEGIN CERTIFICATE-----\\s.*?-----END CERTIFICATE-----",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        // List order is not significant: ChainedDohDns randomizes the try-order per lookup (see `randomize`).
        private val DEFAULT_DOH_ENDPOINTS = listOf(
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
