package com.gios.lightsync

import android.content.Context

/**
 * Where BasilNet is, what gets you in, and what unlocks the blobs.
 *
 * The passphrase is kept here in plain SharedPreferences, which is worth being honest about: it
 * is readable by anything with root or a debug bridge on an unlocked phone. Wrapping it in an
 * AndroidKeyStore key would stop that, and would also make the backups unrecoverable after a
 * factory reset — the key would be gone and the ciphertext on BasilNet would be scrap. Since
 * surviving a lost phone is the entire point, the passphrase has to be something you can type
 * again, which means it is only as good as your choice of it.
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("lightsync", Context.MODE_PRIVATE)

    var server: String
        get() = sp.getString("server", "").orEmpty()
        set(v) = sp.edit().putString("server", v.trim().trimEnd('/')).apply()

    var token: String
        get() = sp.getString("token", "").orEmpty()
        set(v) = sp.edit().putString("token", v.trim()).apply()

    var passphrase: String
        get() = sp.getString("pass", "").orEmpty()
        set(v) = sp.edit().putString("pass", v).apply()

    var autoDaily: Boolean
        get() = sp.getBoolean("auto", true)
        set(v) = sp.edit().putBoolean("auto", v).apply()

    val ready: Boolean get() = server.isNotEmpty() && token.isNotEmpty() && passphrase.isNotEmpty()

    fun lastRun(pkg: String): Long = sp.getLong("last:$pkg", 0L)

    fun setLastRun(pkg: String, at: Long) = sp.edit().putLong("last:$pkg", at).apply()

    fun lastError(): String? = sp.getString("error", null)

    fun setLastError(message: String?) = sp.edit().putString("error", message).apply()
}
