package com.gios.lightsync.sync

import com.gios.light.common.LightCommon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Version ordering, which is the only logic on the fleet screen that can be quietly wrong.
 *
 * A bad comparison here does not crash; it puts "(behind)" next to an app that is current, or
 * leaves it off one that is not, and either way the screen is worse than not having it. All of
 * this is plain arithmetic on strings, so it runs on the JVM with no device.
 */
class FleetVersionTest {

    private fun cmp(a: String, b: String) = Fleet.compareVersions(a, b).let {
        if (it > 0) 1 else if (it < 0) -1 else 0
    }

    @Test
    fun `numbers, not text`() {
        // The case the whole function exists for: alphabetically "1.10.0" sorts below "1.9.0".
        assertEquals(1, cmp("1.10.0", "1.9.0"))
        assertEquals(1, cmp("2.0.0", "1.99.99"))
        assertEquals(-1, cmp("1.2.0", "1.2.1"))
        assertEquals(0, cmp("1.2.1", "1.2.1"))
    }

    @Test
    fun `a missing part is a zero, not a lower version`() {
        // Otherwise an app reporting "1.2" would be labelled behind one reporting "1.2.0".
        assertEquals(0, cmp("1.2", "1.2.0"))
        assertEquals(1, cmp("1.2.1", "1.2"))
    }

    @Test
    fun `a pre-release sorts below the release it precedes`() {
        assertEquals(-1, cmp("1.3.0-rc1", "1.3.0"))
        assertEquals(1, cmp("1.3.0", "1.3.0-rc1"))
    }

    @Test
    fun `an unparseable version is the oldest thing there is`() {
        // An app that answers with junk should be flagged as behind, never as newest — that is
        // the direction where being wrong is harmless.
        assertTrue(cmp("", "1.0.0") < 0)
        assertTrue(cmp("unknown", "1.0.0") < 0)
    }

    @Test
    fun `newest wins across a mixed fleet`() {
        // Numbers far above anything light-common will ship, on purpose. newestCommon counts
        // this app's own compiled-in version too, so a fleet pinned just above the real one
        // passes until the library catches up and then fails with nobody having touched it —
        // which is what the 1.2.1 to 1.2.3 bump did to these two tests.
        val apps = listOf("9.2.0", "9.10.0", "9.2.1").map { fake(it) }
        assertEquals("9.10.0", Fleet.newestCommon(apps))
    }

    @Test
    fun `an app too old to answer does not become the newest`() {
        // Null commonVersion means the app predates 1.2.0. It must not drag the bar down, or
        // every other app would read as current against it.
        val apps = listOf(fake("9.2.1"), fake(null))
        assertEquals("9.2.1", Fleet.newestCommon(apps))
    }

    @Test
    fun `this app's own version is always in the running`() {
        // The other half of the same rule, and the half the pinned numbers above were testing
        // by accident: a fleet entirely behind this build reports this build.
        assertEquals(LightCommon.VERSION, Fleet.newestCommon(listOf(fake("0.0.1"))))
    }

    private fun fake(common: String?) = FleetApp(
        pkg = "x.$common",
        authority = "x.lightsync.backup",
        label = "X",
        appVersion = null,
        commonVersion = common,
        stores = emptyList(),
        sizeHint = 0L,
        reportLabel = null,
        lastRun = 0L,
    )
}
