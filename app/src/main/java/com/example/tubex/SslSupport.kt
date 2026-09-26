package com.example.tubex

import android.content.Context
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object SslSupport {

    data class Trust(
        val sslSocketFactory: SSLSocketFactory,
        val trustManager: X509TrustManager,
    )

    fun build(context: Context): Trust? {
        return try {
            val certificateFactory = CertificateFactory.getInstance("X.509")
            val resource = context.resources.openRawResource(R.raw.poseidon_ca)
            val bundled: List<Certificate> = resource.use {
                certificateFactory.generateCertificates(it).toList()
            }
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
            keyStore.load(null)
            val systemTrustManager = TrustManagerFactory
                .getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(null as KeyStore?) }
                .trustManagers[0] as X509TrustManager
            var index = 0
            for (issuer in systemTrustManager.acceptedIssuers) {
                keyStore.setCertificateEntry("system$index", issuer)
                index++
            }
            for (certificate in bundled) {
                keyStore.setCertificateEntry("tubex$index", certificate)
                index++
            }
            val trustManagerFactory = TrustManagerFactory
                .getInstance(TrustManagerFactory.getDefaultAlgorithm())
            trustManagerFactory.init(keyStore)
            val trustManager = trustManagerFactory.trustManagers[0] as X509TrustManager
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustManagerFactory.trustManagers, SecureRandom())
            Trust(sslContext.socketFactory, trustManager)
        } catch (_: Exception) {
            null
        }
    }

    fun installDefault(context: Context) {
        build(context)?.let { trust ->
            HttpsURLConnection.setDefaultSSLSocketFactory(trust.sslSocketFactory)
        }
    }
}