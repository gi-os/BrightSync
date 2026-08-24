package com.gios.lightsync.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What someone types into the address box.
 *
 * All four of these were typed by hand on a phone at some point, and three of them produce a URL
 * that fails in a different way: no scheme throws inside `URL`, a trailing slash doubles it in
 * every path, and `/api` on the end turns every call into `/api/api/...` and answers 404 with no
 * hint about which half is wrong.
 */
class ImmichUrlTest {

    @Test
    fun `a bare host gets http, since Immich on a LAN has no certificate`() {
        assertEquals("http://192.168.68.59:2283", Immich.normalize("192.168.68.59:2283"))
    }

    @Test
    fun `https is left alone`() {
        assertEquals("https://photos.basilnet.com", Immich.normalize("https://photos.basilnet.com"))
    }

    @Test
    fun `a trailing slash and a helpful api are both removed`() {
        assertEquals("http://box:2283", Immich.normalize("http://box:2283/"))
        assertEquals("http://box:2283", Immich.normalize("http://box:2283/api"))
        assertEquals("http://box:2283", Immich.normalize("  http://box:2283/api/  "))
    }

    @Test
    fun `empty stays empty rather than becoming a scheme`() {
        assertEquals("", Immich.normalize("   "))
    }
}
