package com.gios.lightsync.sync

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The photo half of BrightSync: Immich on BasilNet, spoken to directly from the phone.
 *
 * Everything else here is sealed before it leaves and lands as a blob the server cannot read.
 * Photographs cannot work that way, and there is no point pretending otherwise: Immich decodes
 * every file it is given to build thumbnails, read EXIF, cluster faces and index places. A
 * library of ciphertext is a library of nothing. So the roll is an explicit exception to the
 * rule the rest of this app is built on — written down here rather than discovered later — and
 * what the exception buys is a real photo library instead of a megabyte-per-frame blob that only
 * this app could ever open.
 *
 * The upload path is Immich's own, not a private one: `POST /api/assets` with an `x-api-key`,
 * the same call the Immich mobile app and CLI make. That matters for the same reason the blob
 * format is documented — an ordinary Immich client can read this library back, and `immich-go`
 * can move it somewhere else.
 *
 * ### Dedupe happens before the bytes move
 *
 * The roll is re-read from scratch on every run, so nearly everything the phone offers is
 * already on the server. `POST /api/assets/bulk-upload-check` answers accept-or-reject for a
 * batch of SHA-1s in one round trip, which turns a thousand-frame roll into one small request
 * and a handful of uploads. Immich would reject the duplicates anyway — it hashes what it
 * receives — but only after the phone had pushed five megabytes across the wifi to be told so.
 */
class Immich(base: String, private val key: String) {

    /** `http://192.168.68.59:2283`, with or without a trailing slash or an `/api` on the end. */
    private val root = base.trim().trimEnd('/').removeSuffix("/api")

    /** Cheap, unauthenticated, and the only call the setup screen needs. */
    fun reachable(): Boolean = runCatching {
        open("/api/server/ping", "GET").let { conn ->
            conn.expect(200)
            JSONObject(conn.body()).optString("res") == "pong"
        }
    }.getOrDefault(false)

    /**
     * Which of these the server does not already hold.
     *
     * The ids handed in come back on the answers, file names here, and only the accepted ones
     * are returned. An unrecognised reply counts as "already there": erring towards a skipped
     * frame rather than a duplicate is the right way round, since the next run offers it again.
     */
    fun accepted(items: List<Pair<String, String>>): Set<String> {
        if (items.isEmpty()) return emptySet()
        val body = JSONObject().put(
            "assets",
            JSONArray().apply {
                items.forEach { (id, checksum) ->
                    put(JSONObject().put("id", id).put("checksum", checksum))
                }
            },
        )
        val conn = open("/api/assets/bulk-upload-check", "POST")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        conn.expect(200)

        val results = JSONObject(conn.body()).optJSONArray("results") ?: return emptySet()
        return (0 until results.length())
            .map { results.getJSONObject(it) }
            .filter { it.optString("action") == "accept" }
            .map { it.optString("id") }
            .toSet()
    }

    /**
     * Push one file, and say whether it was new.
     *
     * A duplicate is not an error and not nothing: Immich answers with the id of the asset it
     * already holds, which is exactly what the album call needs. Sending the same frame to a
     * newly created album is how a re-created album gets its contents back.
     *
     * Streamed rather than buffered: a 48-megapixel frame or a minute of 4K is tens of
     * megabytes, and this runs on a phone with the rest of LightOS in memory.
     * `setFixedLengthStreamingMode` also gives the request a real `Content-Length`, so a file
     * over Immich's limit is refused before it is sent rather than after.
     */
    fun upload(
        name: String,
        mime: String,
        size: Long,
        createdAt: Long,
        modifiedAt: Long,
        checksum: String,
        openFile: () -> InputStream,
    ): Uploaded {
        val parts = Multipart(
            fields = listOf(
                "deviceAssetId" to "$name-$size",
                "deviceId" to DEVICE,
                "fileCreatedAt" to iso(createdAt),
                "fileModifiedAt" to iso(modifiedAt),
                "filename" to name,
            ),
            fileField = "assetData",
            fileName = name,
            fileMime = mime,
            fileSize = size,
        )

        val conn = open("/api/assets", "POST")
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=${parts.boundary}")
        // Immich hashes what it receives and compares it with this, so a truncated upload is
        // refused rather than stored as a broken asset. It is a check on the bytes, not an
        // identifier: a file the server can already match on `deviceAssetId` is answered as a
        // duplicate before the header is ever looked at.
        conn.setRequestProperty("x-immich-checksum", checksum)
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(parts.contentLength)
        conn.readTimeout = 120_000
        conn.outputStream.use { out -> parts.writeTo(out, openFile) }
        conn.expect(200, 201)

        val reply = JSONObject(conn.body())
        return Uploaded(
            id = reply.optString("id"),
            created = reply.optString("status") != "duplicate",
        )
    }

    /** The album id for [name], created the first time. Null when albums are switched off. */
    fun album(name: String): String? {
        if (name.isBlank()) return null
        val existing = JSONArray(open("/api/albums", "GET").also { it.expect(200) }.body())
        for (i in 0 until existing.length()) {
            val album = existing.getJSONObject(i)
            if (album.optString("albumName") == name) return album.optString("id")
        }
        val conn = open("/api/albums", "POST")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.outputStream.use { it.write(JSONObject().put("albumName", name).toString().toByteArray()) }
        conn.expect(200, 201)
        return JSONObject(conn.body()).optString("id")
    }

    /** Re-adding an asset already in the album is not an error, so this keeps no bookkeeping. */
    fun addToAlbum(albumId: String, assetIds: List<String>) {
        if (assetIds.isEmpty()) return
        val conn = open("/api/albums/$albumId/assets", "PUT")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.outputStream.use { it.write(JSONObject().put("ids", JSONArray(assetIds)).toString().toByteArray()) }
        conn.expect(200)
        conn.body()
    }

    private fun open(path: String, method: String): HttpURLConnection =
        (URL(root + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("x-api-key", key)
            setRequestProperty("Accept", "application/json")
            connectTimeout = 4_000
            readTimeout = 30_000
        }

    private fun HttpURLConnection.body(): String =
        inputStream.use { it.readBytes().decodeToString() }

    private fun HttpURLConnection.expect(vararg codes: Int) {
        val code = responseCode
        if (code !in codes) {
            val detail = runCatching { errorStream?.readBytes()?.decodeToString() }.getOrNull()
            // Trimmed, because an Immich validation error is a paragraph of JSON and this
            // string ends up on a 240-pixel-wide screen.
            throw IOException("$requestMethod $url -> $code ${detail.orEmpty().take(200)}".trim())
        }
    }

    companion object {
        /** Immich groups uploads by the device that sent them; the phone is one device. */
        const val DEVICE = "brightsync"

        /** Immich wants ISO-8601, and rejects a bare epoch. */
        fun iso(millis: Long): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(millis))
    }
}

/** One asset on the server, and whether this phone is what put it there. */
data class Uploaded(val id: String, val created: Boolean)

/**
 * One multipart body, measured before it is written.
 *
 * Separate from the connection so the arithmetic is testable without a socket: an off-by-one in
 * the framing produces a 400 from Immich with nothing useful in it, and that is the kind of
 * mistake that is obvious in a unit test and invisible in a log.
 */
class Multipart(
    fields: List<Pair<String, String>>,
    fileField: String,
    private val fileName: String,
    fileMime: String,
    private val fileSize: Long,
    val boundary: String = "----brightsync" + java.lang.Long.toHexString(System.nanoTime()),
) {

    private val head: ByteArray = buildString {
        fields.forEach { (name, value) ->
            append("--").append(boundary).append(CRLF)
            append("Content-Disposition: form-data; name=\"").append(name).append('"').append(CRLF)
            append(CRLF).append(value).append(CRLF)
        }
        append("--").append(boundary).append(CRLF)
        append("Content-Disposition: form-data; name=\"").append(fileField)
            .append("\"; filename=\"").append(fileName).append('"').append(CRLF)
        append("Content-Type: ").append(fileMime).append(CRLF).append(CRLF)
    }.toByteArray()

    private val tail: ByteArray = "$CRLF--$boundary--$CRLF".toByteArray()

    val contentLength: Long get() = head.size + fileSize + tail.size

    fun writeTo(out: OutputStream, file: () -> InputStream) {
        out.write(head)
        var sent = 0L
        file().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                out.write(buffer, 0, read)
                sent += read
            }
        }
        // A file that changed size between the MediaStore row and this read would leave the
        // connection waiting for bytes that never come, and that surfaces as a timeout minutes
        // later. Say what actually happened instead.
        if (sent != fileSize) {
            throw IOException("$fileName changed while uploading ($sent of $fileSize bytes)")
        }
        out.write(tail)
        out.flush()
    }

    companion object {
        const val CRLF = "\r\n"
    }
}
