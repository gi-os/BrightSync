package com.gios.lightsync.sync

import com.gios.lightsync.Prefs
import java.net.URLDecoder

/**
 * Everything the phone needs, read off a screen in one go.
 *
 * Setup used to be six values thumbed into a phone with a keyboard the size of a stamp, two of
 * them long random strings, and a typo in any of them produces a 401 hours later rather than an
 * error at the time. The server already knows five of the six, so it draws them as a QR code at
 * `/enroll/<token>` and this parses what the camera reads.
 *
 * The wire format is a URI rather than JSON, and the keys are single letters, because every
 * character is another module in the code and a dense QR is one that will not scan across a
 * room:
 *
 * ```
 * brightsync://setup?v=1&s=<server>&t=<token>&p=<passphrase>&i=<immich>&k=<key>&a=<album>
 * ```
 *
 * Every field is optional except `v`. A photos-only code carries no server or token; a
 * backups-only code carries no Immich. What is absent is left alone rather than cleared, so
 * scanning an Immich-only code on a working phone adds photographs without unpicking the rest.
 */
data class Enrollment(
    val server: String? = null,
    val token: String? = null,
    val passphrase: String? = null,
    val immich: String? = null,
    val immichKey: String? = null,
    val album: String? = null,
) {

    val hasBlobs: Boolean get() = !server.isNullOrEmpty() && !token.isNullOrEmpty()

    val hasPhotos: Boolean get() = !immich.isNullOrEmpty()

    /** True when the phone still has to be told the one secret the server does not hold. */
    val needsPassphrase: Boolean get() = hasBlobs && passphrase.isNullOrEmpty()

    /** What the confirmation screen says it is about to do, in the order it will do it. */
    fun summary(): List<String> = buildList {
        if (hasBlobs) add("Backups → $server")
        if (!passphrase.isNullOrEmpty()) add("Passphrase from the code")
        if (hasPhotos) add("Photographs → $immich")
        if (!album.isNullOrEmpty()) add("Album “$album”")
    }

    fun applyTo(prefs: Prefs) {
        server?.takeIf { it.isNotEmpty() }?.let { prefs.server = it }
        token?.takeIf { it.isNotEmpty() }?.let { prefs.token = it }
        passphrase?.takeIf { it.isNotEmpty() }?.let { prefs.passphrase = it }
        immich?.takeIf { it.isNotEmpty() }?.let { prefs.immich = it }
        immichKey?.takeIf { it.isNotEmpty() }?.let { prefs.immichKey = it }
        // The album is the one field where empty is a real answer — it means "no album" — so it
        // is written whenever the code carried the key at all.
        album?.let { prefs.immichAlbum = it }
    }

    companion object {
        const val SCHEME = "brightsync"
        const val PREFIX = "$SCHEME://setup?"

        /**
         * Parse a scanned string, or null if it is not one of ours.
         *
         * Hand-rolled rather than `Uri.parse`, so this is a plain JVM function with a test that
         * runs in a second instead of on a phone. The failure it exists to prevent is silent:
         * a `+` decoded as a space, or a value cut in half by an unescaped `&`, configures the
         * phone with a credential one character out and surfaces days later as a 401.
         */
        fun parse(scanned: String): Enrollment? {
            val trimmed = scanned.trim()
            if (!trimmed.startsWith(PREFIX, ignoreCase = true)) return null
            val fields = trimmed.removePrefix(PREFIX)
                .split('&')
                .filter { it.isNotEmpty() }
                .mapNotNull { pair ->
                    val at = pair.indexOf('=')
                    if (at <= 0) return@mapNotNull null
                    val key = pair.substring(0, at)
                    // `+` is a space only in form encoding, and the server percent-escapes
                    // everything, so decoding as UTF-8 with no plus handling is what round trips.
                    val value = runCatching {
                        URLDecoder.decode(pair.substring(at + 1).replace("+", "%2B"), "UTF-8")
                    }.getOrNull() ?: return@mapNotNull null
                    key to value
                }
                .toMap()

            // An unknown version is a newer server talking to an older phone. Refusing is right:
            // half-applying a format this build does not understand is how a working phone ends
            // up pointed at nothing.
            if (fields["v"] != "1") return null

            return Enrollment(
                server = fields["s"],
                token = fields["t"],
                passphrase = fields["p"],
                immich = fields["i"],
                immichKey = fields["k"],
                album = fields["a"],
            )
        }
    }
}
