package com.gios.lightsync.sync

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.security.MessageDigest
import java.util.Base64

/** One frame or clip in the roll, as MediaStore describes it. */
data class Shot(
    val uri: Uri,
    val name: String,
    val mime: String,
    val size: Long,
    /** When it was taken, in millis. Falls back to the file's own timestamp. */
    val takenAt: Long,
    /** MediaStore's `DATE_MODIFIED`, in **seconds** — the watermark is kept in the same unit. */
    val modifiedAt: Long,
)

/**
 * Reading the camera roll, from outside the camera.
 *
 * This is the one payload in BrightSync that needs no cooperation from the app that produced it.
 * Roll writes photographs to `DCIM/Camera` through MediaStore, which is shared storage rather
 * than an app sandbox, so the agent can read them under its own `READ_MEDIA_IMAGES` grant. No
 * provider, no light-common release, no per-app opt-in — which is why photos never needed the
 * contribution model the rest of this app is built on, and why adding them touched no other
 * repository.
 *
 * It also means the roll is covered no matter what put a file there. BrightImport pulls frames
 * off a real camera over its wifi and lands them in DCIM; those go up too, without either app
 * knowing this exists.
 *
 * `DCIM` and not all of external storage. Screenshots, downloaded images and whatever an app
 * cached in `Pictures/` are not photographs, and a photo library that fills up with them is one
 * you stop opening.
 */
object Roll {

    /** MediaStore paths always end in a slash, and `DCIM/` matches `DCIM/Camera/` under it. */
    private const val ROLL_PATH = "DCIM/%"

    /**
     * Both halves, and images alone is not enough.
     *
     * Stills and clips live in two MediaStore tables and each has had its own permission since
     * API 33. Held to images only, every video in the roll is invisible to the query — not an
     * error, just absent — and the phone would quietly never back up a single clip.
     */
    val PERMISSIONS = arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
    )

    fun granted(context: Context): Boolean = PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Everything in the roll modified at or after [since], oldest first, capped at [limit].
     *
     * Oldest first on purpose: the watermark can only be advanced past a frame that is safely on
     * the server, so a run that dies halfway has to leave the *newest* work undone. Newest-first
     * would push the watermark forward over frames that never went up.
     *
     * `>=` rather than `>` because `DATE_MODIFIED` has one-second resolution and a burst of five
     * frames shares a value. Offering the last second again on every run costs one hash and one
     * line in a bulk-check; missing four frames out of five costs a photograph.
     */
    fun since(context: Context, since: Long, limit: Int): List<Shot> {
        val images = query(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, since, limit)
        val videos = query(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, since, limit)
        return (images + videos).sortedBy { it.modifiedAt }.take(limit)
    }

    /** How many are still waiting, for the one number the screen shows. */
    fun pending(context: Context, since: Long): Int =
        since(context, since, Int.MAX_VALUE).size

    private fun query(context: Context, collection: Uri, since: Long, limit: Int): List<Shot> {
        val columns = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_TAKEN,
        )
        val where = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
            "${MediaStore.MediaColumns.DATE_MODIFIED} >= ? AND " +
            "${MediaStore.MediaColumns.SIZE} > 0"
        val args = arrayOf(ROLL_PATH, since.toString())
        val order = "${MediaStore.MediaColumns.DATE_MODIFIED} ASC"

        val out = mutableListOf<Shot>()
        context.contentResolver.query(collection, columns, where, args, order)?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val name = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mime = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val size = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val modified = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val taken = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            while (cursor.moveToNext() && out.size < limit) {
                val modifiedAt = cursor.getLong(modified)
                val takenAt = cursor.getLong(taken)
                out += Shot(
                    uri = Uri.withAppendedPath(collection, cursor.getLong(id).toString()),
                    name = cursor.getString(name) ?: "unnamed",
                    mime = cursor.getString(mime) ?: "application/octet-stream",
                    size = cursor.getLong(size),
                    // DATE_TAKEN is millis and often absent — it comes from EXIF, and a frame
                    // written by an app that did not set it reads back as zero. The file's own
                    // second-resolution timestamp is a worse answer than EXIF and a much better
                    // one than 1970, which is where an unguarded value would file the photo.
                    takenAt = if (takenAt > 0) takenAt else modifiedAt * 1000,
                    modifiedAt = modifiedAt,
                )
            }
        }
        return out
    }

    /**
     * The SHA-1 Immich dedupes on, base64 as its API expects.
     *
     * SHA-1 is not a security choice here and is not being asked to be one: it is the digest
     * Immich stores for every asset, so it is the only hash that can answer "do you already have
     * this frame?" without uploading the frame.
     */
    fun checksum(resolver: ContentResolver, uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-1")
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        } ?: throw IllegalStateException("cannot read $uri")
        return Base64.getEncoder().encodeToString(digest.digest())
    }
}
