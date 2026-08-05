package com.gios.lightsync.sync

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import com.gios.light.common.LightCommon
import com.gios.light.common.sync.SyncMeta

/**
 * Everything one app will tell us about itself.
 *
 * All of it optional, and that is the point. The apps most worth looking at on this screen are
 * the ones that have not been updated in a while, so an app built before `meta` learned to
 * answer a field has to show a dash in that column rather than disappear from the list.
 */
data class FleetApp(
    val pkg: String,
    val authority: String,
    val label: String,
    /** The app's own `versionName`, e.g. `"1.41.59"`. */
    val appVersion: String?,
    /** Which light-common the app was built against. Null means older than 1.2.0. */
    val commonVersion: String?,
    /** Store names, in the order the app declared them. */
    val stores: List<String>,
    /** Rough payload size in bytes, before compression. */
    val sizeHint: Long,
    /** The app's triage label in light-reports, if it files issues at all. */
    val reportLabel: String?,
    /** When LightSync last put this app on BasilNet. 0 for never. */
    val lastRun: Long,
    /** Set when the app is installed but would not answer — see [Fleet.survey]. */
    val unreachable: Boolean = false,
)

/**
 * The state of the whole family, asked one app at a time.
 *
 * There is no registry and no manifest of Light apps anywhere; the phone is the registry. Every
 * app publishing a `*.lightsync.backup` provider is here, which means this screen is right about
 * app seventeen without anyone editing it, and — more usefully — it is right about an app that
 * was uninstalled, which a hand-kept list would never be.
 */
object Fleet {

    /**
     * Ask every discovered app for its details.
     *
     * Each app is a separate binder round trip into a process that may have to be started to
     * answer, so this belongs on IO and not on the frame. Failures are per app: one that throws
     * comes back as [FleetApp.unreachable] rather than taking the survey down with it, because
     * an app that cannot answer is exactly the row worth seeing.
     */
    fun survey(context: Context, lastRun: (String) -> Long): List<FleetApp> =
        Discovery.find(context).map { app ->
            val bundle = runCatching {
                context.contentResolver.call(
                    Uri.parse("content://${app.authority}"),
                    SyncMeta.METHOD_META,
                    null,
                    null,
                )
            }.getOrNull()

            FleetApp(
                pkg = app.pkg,
                authority = app.authority,
                label = bundle?.getString(SyncMeta.LABEL) ?: app.label,
                appVersion = bundle?.getString(SyncMeta.APP_VERSION) ?: installedVersion(context, app.pkg),
                commonVersion = bundle?.getString(SyncMeta.COMMON_VERSION),
                stores = bundle?.getStringArray(SyncMeta.STORES)?.toList().orEmpty(),
                sizeHint = bundle?.getLong(SyncMeta.SIZE_HINT) ?: 0L,
                reportLabel = bundle?.getString(SyncMeta.REPORT_LABEL),
                lastRun = lastRun(app.pkg),
                unreachable = bundle == null,
            )
        }

    /**
     * The newest light-common anyone is carrying, including this app.
     *
     * Taken from the fleet rather than hardcoded so the comparison stays honest: the agent is
     * usually not the first thing rebuilt after a library release, and a screen that measured
     * everyone against the agent's own version would call the whole fleet up to date on the day
     * the agent fell behind.
     */
    fun newestCommon(apps: List<FleetApp>): String =
        (apps.mapNotNull { it.commonVersion } + LightCommon.VERSION).maxWithOrNull(::compareVersions)
            ?: LightCommon.VERSION

    /**
     * Dotted-number order, not string order.
     *
     * `"1.10.0" < "1.9.0"` alphabetically, and light-common is one release away from that being
     * a real answer on this screen. A missing part counts as zero, so `1.2` and `1.2.0` are the
     * same version rather than one being behind the other, and a part carrying a suffix sorts
     * below the bare number, so `1.3.0-rc1` never claims to be newer than `1.3.0`.
     *
     * Covered by `FleetVersionTest`, which is worth having for eight lines because every case
     * here is one that only shows up months later and shows up as a wrong label rather than as
     * a crash.
     */
    fun compareVersions(a: String, b: String): Int {
        val x = a.split('.')
        val y = b.split('.')
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrNull(i)
            val q = y.getOrNull(i)
            number(p).compareTo(number(q)).let { if (it != 0) return it }
            release(p).compareTo(release(q)).let { if (it != 0) return it }
        }
        return 0
    }

    private fun number(part: String?): Int = part?.takeWhile(Char::isDigit)?.toIntOrNull() ?: 0

    /** 0 for a pre-release part such as `0-rc1`, 1 for a bare number. Lower sorts first. */
    private fun release(part: String?): Int =
        if (part != null && part.dropWhile(Char::isDigit).isNotEmpty()) 0 else 1

    /** Falls back to the package manager when an app is too old to report its own version. */
    private fun installedVersion(context: Context, pkg: String): String? = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(pkg, 0).versionName
    }.getOrNull()

    /** True when the package is installed at all. Used to explain an unreachable row. */
    fun installed(context: Context, pkg: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(pkg, PackageManager.GET_META_DATA)
        true
    }.getOrDefault(false)
}
