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
 * **A tap means all of it.** [runAll] keeps taking batches until the roll is on the server. The
 * batch is still fifty, because that is one bulk-check and a bounded amount of hashing to lose
 * if the wifi drops mid-pass — but it is a unit of work, not a limit on the answer. Asking a
 * phone with four hundred new frames to be tapped eight times was a bug wearing a design's
 * clothes.
 *
 * The background run is the one with a limit, and the limit is time rather than count:
 * WorkManager will stop a worker that runs long, so [runAll] takes a budget and stops cleanly
 * inside it, leaving the rest for a catch-up run a minute later. See [SyncWorker].
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

    /**
     * Keep going until the roll is up, or until [budgetMs] of wall clock has gone.
     *
     * [onProgress] is called after every pass so a screen can count up while it works; it runs
     * on whatever thread called this, which is an IO one.
     *
     * A pass that uploads nothing and clears nothing ends the loop rather than repeating it.
     * That is the shape of every runaway-loop bug this could have — a frame the server keeps
     * accepting and never storing, a watermark that will not advance — and spinning on it would
     * cost the battery of a phone in someone's pocket.
     */
    fun runAll(budgetMs: Long = Long.MAX_VALUE, onProgress: (PhotoRun) -> Unit = {}): PhotoRun {
        val started = System.currentTimeMillis()
        var uploaded = 0
        var skipped = 0
        var last: PhotoRun
        while (true) {
            val before = prefs.photoMark
            last = run()
            uploaded += last.uploaded
            skipped += last.alreadyThere
            onProgress(PhotoRun(uploaded, skipped, last.remaining))
            val stuck = last.uploaded == 0 && last.alreadyThere == 0 && prefs.photoMark == before
            if (last.remaining <= 0 || stuck) break
            if (System.currentTimeMillis() - started >= budgetMs) break
        }
        return PhotoRun(uploaded, skipped, last.remaining)
    }

    companion object {
        /** Frames per pass — the unit of work, not a cap. See [runAll]. */
        const val BATCH = 50

        /**
         * How long the daily run may spend on photographs before leaving the rest to a catch-up.
         *
         * WorkManager gives a worker ten minutes before it is stopped, and a worker killed
         * mid-upload loses the pass it was in. Nine leaves room to finish the frame in flight.
         */
        const val BACKGROUND_BUDGET_MS = 9L * 60 * 1000

        /** Checksums per bulk-check request. Immich takes far more; this keeps the body small. */
        const val CHECK_BATCH = 100
    }
}
