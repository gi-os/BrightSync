package com.gios.lightsync.sync

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The three calls LightSync makes. `HttpURLConnection` rather than a client library, because
 * this is three requests against a service on the same subnet and an HTTP dependency would be
 * larger than the app.
 */
class Server(private val base: String, private val token: String) {

    fun put(app: String, blob: ByteArray) {
        val conn = open("/b/$app", "PUT")
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(blob.size)
        conn.outputStream.use { it.write(blob) }
        conn.expect(200, 201)
    }

    fun latest(app: String): ByteArray {
        val conn = open("/b/$app/latest", "GET")
        conn.expect(200)
        return conn.inputStream.use { it.readBytes() }
    }

    /** Used by the settings screen to say whether BasilNet is actually answering. */
    fun reachable(): Boolean = runCatching {
        open("/", "GET").let { it.expect(200); it.inputStream.close() }
        true
    }.getOrDefault(false)

    private fun open(path: String, method: String): HttpURLConnection =
        (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("X-Token", token)
            // Short, because this only ever talks to a machine in the same building. A phone
            // that has left the house should fail fast and let WorkManager try again later.
            connectTimeout = 4_000
            readTimeout = 20_000
        }

    private fun HttpURLConnection.expect(vararg codes: Int) {
        val code = responseCode
        if (code !in codes) {
            val detail = runCatching { errorStream?.readBytes()?.decodeToString() }.getOrNull()
            throw IOException("$requestMethod $url -> $code ${detail.orEmpty()}".trim())
        }
    }
}
