package com.gios.lightsync.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * The multipart arithmetic, which is the one part of the Immich path that fails silently.
 *
 * `setFixedLengthStreamingMode` promises the server an exact byte count, so a head or tail that
 * is one CRLF out either truncates the file or hangs the request until it times out. Neither
 * failure names itself: Immich answers 400 with a validation blob, or nothing answers at all.
 * The upload is untestable here — it needs a server — but this is the half that is arithmetic.
 */
class MultipartTest {

    private val file = "not really a jpeg, but it is bytes".toByteArray()

    private fun parts(size: Long = file.size.toLong()) = Multipart(
        fields = listOf("deviceId" to "brightsync", "filename" to "IMG_0001.jpg"),
        fileField = "assetData",
        fileName = "IMG_0001.jpg",
        fileMime = "image/jpeg",
        fileSize = size,
        boundary = "----test",
    )

    @Test
    fun `declared length is the length actually written`() {
        val parts = parts()
        val out = ByteArrayOutputStream()
        parts.writeTo(out) { ByteArrayInputStream(file) }
        assertEquals(parts.contentLength, out.size().toLong())
    }

    @Test
    fun `body carries every field, the file part and a closing boundary`() {
        val out = ByteArrayOutputStream()
        parts().writeTo(out) { ByteArrayInputStream(file) }
        val body = out.toByteArray().decodeToString()

        assertTrue(body.contains("name=\"deviceId\"\r\n\r\nbrightsync\r\n"))
        assertTrue(body.contains("name=\"filename\"\r\n\r\nIMG_0001.jpg\r\n"))
        assertTrue(body.contains("name=\"assetData\"; filename=\"IMG_0001.jpg\""))
        assertTrue(body.contains("Content-Type: image/jpeg\r\n\r\n"))
        assertTrue(body.endsWith("\r\n------test--\r\n"))
        assertTrue(body.contains(file.decodeToString()))
    }

    /**
     * A frame deleted or rewritten between the MediaStore query and the upload is the ordinary
     * case, not a corner: Roll writes a full-resolution file after the row exists. Failing here
     * names it; letting the stream come up short would hand Immich a truncated asset with a
     * checksum header that no longer matches it.
     */
    @Test(expected = IOException::class)
    fun `a file that changed size fails by name`() {
        val out = ByteArrayOutputStream()
        parts(size = file.size + 10L).writeTo(out) { ByteArrayInputStream(file) }
    }

    @Test
    fun `timestamps go up as ISO-8601 in UTC, not as an epoch`() {
        assertEquals("1970-01-01T00:00:00.000Z", Immich.iso(0L))
        assertEquals("2026-08-24T16:35:23.247Z", Immich.iso(1787589323247L))
    }
}
