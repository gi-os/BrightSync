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

    // ---------------------------------------------------------------- photographs
    //
    // The roll does not go into a blob. It goes to Immich on the same box, in the clear, because
    // a photo library the server cannot decode is not a photo library — see `sync/Immich.kt`.
    // That makes these four values a different kind of setting from the three above: the API key
    // is a credential the server *can* use, so it grants only upload and album rights, and
    // rotating it in Immich is enough to cut the phone off.

    var immich: String
        get() = sp.getString("immich", "").orEmpty()
        set(v) = sp.edit().putString("immich", v.trim().trimEnd('/')).apply()

    var immichKey: String
        get() = sp.getString("immichKey", "").orEmpty()
        set(v) = sp.edit().putString("immichKey", v.trim()).apply()

    /** Album every uploaded frame is added to. Blank turns albums off. */
    var immichAlbum: String
        get() = sp.getString("immichAlbum", "Light Phone III").orEmpty()
        set(v) = sp.edit().putString("immichAlbum", v.trim()).apply()

    var photosAuto: Boolean
        get() = sp.getBoolean("photosAuto", true)
        set(v) = sp.edit().putBoolean("photosAuto", v).apply()

    /**
     * `DATE_MODIFIED` of the last frame a pass got through, in seconds.
     *
     * Only ever a hint about where to start reading the roll. Reset it to zero and the next few
     * runs re-hash the whole roll and upload nothing, because Immich answers on checksums.
     */
    var photoMark: Long
        get() = sp.getLong("photoMark", 0L)
        set(v) = sp.edit().putLong("photoMark", v).apply()

    val photosReady: Boolean get() = immich.isNotEmpty() && immichKey.isNotEmpty()

    fun photoRun(): Long = sp.getLong("photoRun", 0L)

    fun setPhotoRun(at: Long) = sp.edit().putLong("photoRun", at).apply()

    /** Cumulative, for the one line the Apps screen shows. Not authoritative — Immich is. */
    fun photoCount(): Int = sp.getInt("photoCount", 0)

    fun addPhotoCount(n: Int) = sp.edit().putInt("photoCount", photoCount() + n).apply()

    fun lastError(): String? = sp.getString("error", null)

    fun setLastError(message: String?) = sp.edit().putString("error", message).apply()
}
