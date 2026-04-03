package com.example.smsforwarder.net

import android.content.Context
import com.example.smsforwarder.R
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

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
    private val context: Context,
    private val trustAnchorResourceId: Int = R.raw.nixpkgs_cacert,
    private val trustAnchorOpener: (() -> InputStream)? = null,
) : HttpSender {
    private val sslSocketFactory by lazy { buildSslContext().socketFactory }

    override fun send(request: HttpRequest): Int {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        if (connection is HttpsURLConnection) {
            connection.sslSocketFactory = sslSocketFactory
        }
        connection.requestMethod = request.method.uppercase()
        connection.setRequestProperty("Content-Type", request.contentType)
        connection.doInput = true
        if (request.method.uppercase() != "GET") {
            connection.doOutput = true
            connection.outputStream.use { output ->
                output.write(request.body.toByteArray())
            }
        }

        return try {
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    private fun buildSslContext(): SSLContext {
        val certificateFactory = CertificateFactory.getInstance("X.509")
        val certificates = openTrustAnchorStream().use { input -> loadCertificates(input, certificateFactory) }
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
        }

        certificates.forEachIndexed { index, certificate ->
            keyStore.setCertificateEntry("ca-$index", certificate)
        }

        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagerFactory.init(keyStore)

        return SSLContext.getInstance("TLS").apply {
            init(null, trustManagerFactory.trustManagers, SecureRandom())
        }
    }

    private fun openTrustAnchorStream(): InputStream {
        return trustAnchorOpener?.invoke() ?: context.resources.openRawResource(trustAnchorResourceId)
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

    companion object {
        private val PEM_CERTIFICATE_REGEX = Regex(
            "-----BEGIN CERTIFICATE-----\\s.*?-----END CERTIFICATE-----",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
    }
}
