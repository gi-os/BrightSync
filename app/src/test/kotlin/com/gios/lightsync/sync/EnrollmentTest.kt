package com.gios.lightsync.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scanned payload, which fails quietly when it fails at all.
 *
 * A mis-parsed QR does not crash: the phone accepts it, says it is set up, and talks to nothing
 * — or to the right server with a token one character short, which reads as a 401 the following
 * day. Every case here is one of those.
 */
class EnrollmentTest {

    @Test
    fun `a full code carries both halves`() {
        val e = Enrollment.parse(
            "brightsync://setup?v=1&s=http%3A%2F%2F192.168.68.59%3A8099&t=abc123" +
                "&i=http%3A%2F%2F192.168.68.59%3A2283&k=key&a=Light%20Phone%20III",
        )!!
        assertEquals("http://192.168.68.59:8099", e.server)
        assertEquals("abc123", e.token)
        assertEquals("http://192.168.68.59:2283", e.immich)
        assertEquals("Light Phone III", e.album)
        assertTrue(e.hasBlobs)
        assertTrue(e.hasPhotos)
        assertTrue(e.needsPassphrase)
    }

    @Test
    fun `a plus in a credential stays a plus`() {
        // Form decoding turns `+` into a space. Tokens and API keys are base64 often enough that
        // this is not a corner case, and the result is a credential that is silently wrong.
        val e = Enrollment.parse("brightsync://setup?v=1&s=http%3A%2F%2Fx&t=a%2Bb%2Fc%3D")!!
        assertEquals("a+b/c=", e.token)
    }

    @Test
    fun `an escaped ampersand does not split a value`() {
        val e = Enrollment.parse("brightsync://setup?v=1&i=http%3A%2F%2Fx&k=one%26two")!!
        assertEquals("one&two", e.immichKey)
    }

    @Test
    fun `a photos-only code says so`() {
        val e = Enrollment.parse("brightsync://setup?v=1&i=http%3A%2F%2Fx&k=k")!!
        assertFalse(e.hasBlobs)
        assertTrue(e.hasPhotos)
        assertFalse(e.needsPassphrase)
    }

    @Test
    fun `a passphrase in the code means nothing left to type`() {
        val e = Enrollment.parse("brightsync://setup?v=1&s=http%3A%2F%2Fx&t=t&p=correct%20horse")!!
        assertEquals("correct horse", e.passphrase)
        assertFalse(e.needsPassphrase)
    }

    @Test
    fun `a bare address is taken as an Immich to sign in to`() {
        val e = Enrollment.parse("http://192.168.68.59:2283")!!
        assertEquals("http://192.168.68.59:2283", e.immich)
        assertTrue(e.hasPhotos)
        assertFalse(e.hasBlobs)
        // The address someone copies out of the docs still means the same server.
        assertEquals("https://photos.basilnet.com", Enrollment.parse("https://photos.basilnet.com/api")!!.immich)
        assertEquals("https://photos.basilnet.com", Enrollment.parse("https://photos.basilnet.com/")!!.immich)
    }

    @Test
    fun `a link to something inside a server is not a server`() {
        // A shared album is a URL someone meant to open. Accepting it would point the phone at
        // the right host for the wrong reason, and hide it behind a setup that looked fine.
        assertNull(Enrollment.parse("https://photos.basilnet.com/share/abc123"))
        assertNull(Enrollment.parse("https://photos.basilnet.com/photos/1"))
    }

    @Test
    fun `anything else scanned is not ours`() {
        assertNull(Enrollment.parse("WIFI:S:home;T:WPA;P:hunter2;;"))
        assertNull(Enrollment.parse(""))
        // A version this build does not know: refuse rather than half-apply.
        assertNull(Enrollment.parse("brightsync://setup?v=2&s=http%3A%2F%2Fx&t=t"))
    }

    @Test
    fun `the summary lists what will change, and nothing else`() {
        val e = Enrollment.parse("brightsync://setup?v=1&i=http%3A%2F%2Fx&k=k&a=Roll")!!
        assertEquals(listOf("Photographs → http://x", "Album “Roll”"), e.summary())
    }
}
