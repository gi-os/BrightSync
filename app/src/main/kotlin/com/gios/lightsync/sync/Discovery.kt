package com.gios.lightsync.sync

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri

/** An app that has offered itself for backup. */
data class Backupable(val pkg: String, val authority: String, val label: String) {
    val exportUri: Uri get() = Uri.parse("content://$authority/export")
    val callUri: Uri get() = Uri.parse("content://$authority")
}

/**
 * Finding the apps, by asking the system rather than by keeping a list.
 *
 * The suffix *is* the registration: any installed app publishing a `*.lightsync.backup`
 * provider is in, which means adding app seventeen never touches this app. A hardcoded list
 * would have been fewer lines and would have made every rollout a two-release affair.
 */
object Discovery {

    private const val SUFFIX = ".lightsync.backup"

    fun find(context: Context): List<Backupable> {
        val pm = context.packageManager
        val providers = runCatching {
            @Suppress("DEPRECATION")
            pm.queryContentProviders(null, 0, PackageManager.GET_META_DATA)
        }.getOrNull().orEmpty()

        return providers
            .filter { it.authority?.endsWith(SUFFIX) == true && it.packageName != context.packageName }
            .map { info ->
                val fallback = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(info.packageName, 0)).toString()
                }.getOrDefault(info.packageName)
                Backupable(info.packageName, info.authority!!, label(context, info.authority!!) ?: fallback)
            }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    /** The app's own name for itself, if it answers. Cheap, and it beats a package name. */
    private fun label(context: Context, authority: String): String? = runCatching {
        context.contentResolver
            .call(Uri.parse("content://$authority"), "meta", null, null)
            ?.getString("label")
    }.getOrNull()
}
