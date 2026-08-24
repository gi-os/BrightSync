package com.gios.lightsync.sync

import android.content.Context
import com.gios.lightsync.Prefs

/** What one pass over the roll did. */
data class PhotoRun(val uploaded: Int, val alreadyThere: Int, val remaining: Int)

/**
 * The roll, onto Immich, in batches that can be interrupted.
 *
 * This is the payload the first version of BrightSync deliberately left out: everything else is
 * kilobytes of preferences and a small database, and photographs are megabytes each with their
 * own answers to dedupe, resume and where they should end up. The `README` said so under "Not
 * doing" for two releases. Immich is the answer to all three — it hashes assets, it can be
 * asked what it already holds, and it is a photo library rather than a pile of blobs — so the
 * work here is only ever "which frames are new, and hand those over".
 *
 * ### Three rules this follows
 *
 * **Nothing is deleted, ever.** A pass only ever adds. Immich owns the library once a frame
 * arrives; the phone stays the phone, and clearing space on it is a decision made in Roll, not
 * a side effect of a backup.
 *
 * **The watermark is a hint, the checksum is the truth.** `DATE_MODIFIED` decides where to
 * *start* looking; whether a frame goes up is answered by Immich against its own SHA-1 index. So
 * a restored phone, a re-dated file, or a watermark reset costs a batch of hashing and uploads
 * nothing twice.
 *
 * **One batch at a time.** A roll shot over a week away from home is hundreds of files and
 * gigabytes. WorkManager will not hold a job open for that, and the phone should not be warm all
 * evening, so a run takes [BATCH] frames and leaves the rest for the next one — which is minutes
 * later while there is more waiting, not tomorrow. See [SyncWorker].
 */
class Photos(private val context: Context) {

    private val prefs = Prefs(context)

    fun client(): Immich? =
        if (prefs.photosReady) Immich(prefs.immich, prefs.immichKey) else null

    /**
     * Offer the next [batch] frames to Immich.
     *
     * Throws when the server cannot be reached at all, since that is the failure worth showing
     * on the front screen. A single frame that fails — a file deleted between the query and the
     * upload is the common one — stops the pass at that frame and leaves the watermark behind
     * it, so the next run picks it up rather than skipping past it silently.
     */
    fun run(batch: Int = BATCH): PhotoRun {
        val immich = client() ?: throw IllegalStateException("Immich isn't set up yet")
        if (!Roll.granted(context)) {
            throw IllegalStateException("BrightSync has no access to the roll — open the app once")
        }

        val mark = prefs.photoMark
        val shots = Roll.since(context, mark, batch)
        if (shots.isEmpty()) {
            prefs.setPhotoRun(System.currentTimeMillis())
            return PhotoRun(0, 0, 0)
        }

        // Hashing is the expensive half of a pass on a phone, and it is also the half that saves
        // the uploads, so it happens once per frame and the result is carried through.
        val hashed = shots.mapNotNull { shot ->
            runCatching { shot to Roll.checksum(context.contentResolver, shot.uri) }.getOrNull()
        }

        val wanted = mutableSetOf<String>()
        hashed.chunked(CHECK_BATCH).forEach { chunk ->
            wanted += immich.accepted(chunk.map { (shot, sum) -> shot.name to sum })
        }

        val albumId = runCatching { immich.album(prefs.immichAlbum) }.getOrNull()
        val inAlbum = mutableListOf<String>()
        var uploaded = 0
        var skipped = 0
        var reached = mark

        for ((shot, sum) in hashed) {
            if (shot.name !in wanted) {
                skipped++
                reached = shot.modifiedAt
                continue
            }
            val asset = immich.upload(
                name = shot.name,
                mime = shot.mime,
                size = shot.size,
                createdAt = shot.takenAt,
                modifiedAt = shot.modifiedAt * 1000,
                checksum = sum,
            ) { context.contentResolver.openInputStream(shot.uri) ?: error("cannot read ${shot.name}") }
            if (asset.created) uploaded++
            if (asset.id.isNotEmpty()) inAlbum += asset.id
            reached = shot.modifiedAt
        }

        // Best effort, and last. A frame that is on the server but missing from an album is a
        // tidiness problem; a pass that threw here would leave the watermark behind and re-hash
        // the same fifty files on the next run for the same reason.
        if (albumId != null) runCatching { immich.addToAlbum(albumId, inAlbum) }

        prefs.photoMark = reached
        prefs.setPhotoRun(System.currentTimeMillis())
        prefs.addPhotoCount(uploaded)
        return PhotoRun(uploaded, skipped, Roll.pending(context, reached))
    }

    companion object {
        /** Frames per pass. Fifty full-size files is a couple of minutes on the home wifi. */
        const val BATCH = 50

        /** Checksums per bulk-check request. Immich takes far more; this keeps the body small. */
        const val CHECK_BATCH = 100
    }
}
