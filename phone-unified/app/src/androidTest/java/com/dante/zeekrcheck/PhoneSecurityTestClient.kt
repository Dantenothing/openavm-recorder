package com.dante.zeekrcheck

import com.dante.zeekrbridge.server.PhoneTlsIdentity
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.net.Proxy
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import org.json.JSONObject
import org.junit.Assert.assertEquals

/** Only test code knows the local certificate in advance: simulates independently confirmed identity. */
object PhoneSecurityTestClient {
    const val NAME = "openavm-phone.invalid"
    fun trusted(context: android.content.Context, badPin: Boolean = false, address: String = "127.0.0.1"): OkHttpClient {
        val certificate = PhoneTlsIdentity.load(context).certificate
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("phone", certificate) }
        val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            .trustManagers.filterIsInstance<X509TrustManager>().single()
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        val pin = if (badPin) "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=" else CertificatePinner.pin(certificate)
        return OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, tm)
            .certificatePinner(CertificatePinner.Builder().add(NAME, pin).build())
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    require(hostname == NAME || hostname == "wrong.invalid")
                    return listOf(InetAddress.getByName(address))
                }
            })
            .proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
            .callTimeout(20, TimeUnit.SECONDS).build()
    }
    fun request(client: OkHttpClient, method: String, path: String, body: ByteArray? = null,
        token: String? = null, expected: Int = 200, host: String = NAME): JSONObject {
        val request = Request.Builder().url("https://$host:8767$path").header("X-OpenAVM-Transfer", "1")
        token?.let { request.header("Authorization", "Bearer $it") }
        request.method(method, body?.toRequestBody("application/octet-stream".toMediaType()))
        client.newCall(request.build()).execute().use { response ->
            assertEquals("TLS $method $path", expected, response.code)
            return JSONObject(response.body!!.string())
        }
    }
}
