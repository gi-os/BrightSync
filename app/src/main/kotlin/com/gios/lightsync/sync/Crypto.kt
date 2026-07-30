package com.gios.lightsync.sync

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Sealing a payload before it leaves the phone.
 *
 * The server is a file store that should be able to hold your TOTP secrets without being
 * trusted with them, so encryption happens here and the key never exists there. Format, which
 * is written down because a backup you cannot open by hand in ten years is a liability:
 *
 * ```
 * "LSY1" | salt (16) | iv (12) | AES-256-GCM ciphertext+tag
 * key = PBKDF2-HMAC-SHA256(passphrase, salt, 200_000, 256)
 * ```
 *
 * GCM because it authenticates as well as encrypts: a corrupted or tampered blob fails to open
 * rather than restoring garbage into an app. PBKDF2 rather than Argon2 only because it is in the
 * platform, and 200k iterations costs a phone about a second — paid once per backup, which is
 * nothing, and it is the only thing standing between a short passphrase and the ciphertext.
 */
object Crypto {

    private const val MAGIC = "LSY1"
    private const val SALT = 16
    private const val IV = 12
    private const val ITERATIONS = 200_000

    fun seal(plain: ByteArray, passphrase: String): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT).also(random::nextBytes)
        val iv = ByteArray(IV).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key(passphrase, salt), GCMParameterSpec(128, iv))
        }
        val body = cipher.doFinal(plain)
        return MAGIC.toByteArray() + salt + iv + body
    }

    fun open(blob: ByteArray, passphrase: String): ByteArray {
        val head = MAGIC.length
        require(blob.size > head + SALT + IV) { "blob too short" }
        require(String(blob, 0, head) == MAGIC) { "not a LightSync blob" }
        val salt = blob.copyOfRange(head, head + SALT)
        val iv = blob.copyOfRange(head + SALT, head + SALT + IV)
        val body = blob.copyOfRange(head + SALT + IV, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(passphrase, salt), GCMParameterSpec(128, iv))
        }
        return cipher.doFinal(body)
    }

    private fun key(passphrase: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }
}
