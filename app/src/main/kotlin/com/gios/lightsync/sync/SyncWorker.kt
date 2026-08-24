package com.gios.lightsync.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.gios.lightsync.Prefs
import java.util.concurrent.TimeUnit

/**
 * The daily run: every app's blob, then a batch of the roll.
 *
 * A failure here is almost always "not on the home wifi", which is not worth retrying
 * aggressively — hence [Result.success] on a network error and a plain wait for tomorrow. Real
 * errors are recorded for the settings screen rather than thrown away, because a backup that has
 * been quietly failing for a month is worse than one that never ran.
 *
 * Photos are a second pass rather than part of the first, and they run even if an app's backup
 * failed. The two have nothing to do with each other: one app refusing to answer a binder call
 * should not hold up a week of photographs, and Immich being down should not cost the blobs.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        val photosOnly = inputData.getBoolean(PHOTOS_ONLY, false)

        val apps = if (photosOnly || !prefs.wantsBlobs || !prefs.ready || !prefs.autoDaily) {
            null
        } else {
            runCatching { Vault(applicationContext).backupAll() }
        }

        val photos = if (prefs.wantsPhotos && prefs.photosReady && prefs.photosAuto &&
            Roll.granted(applicationContext)
        ) {
            runCatching { Photos(applicationContext).run() }
        } else {
            null
        }

        // More frames waiting than one pass takes: come back in a few minutes rather than
        // tomorrow. A week away shooting is hundreds of files, and a job that ran once a day
        // would take a fortnight to catch up on it.
        photos?.getOrNull()?.let { run ->
            if (run.remaining > 0) catchUp(applicationContext)
        }

        val failure = apps?.exceptionOrNull() ?: photos?.exceptionOrNull()
        prefs.setLastError(failure?.let { it.message ?: it.javaClass.simpleName })
        return Result.success()
    }

    companion object {
        private const val NAME = "lightsync-daily"
        private const val CATCH_UP = "lightsync-photos-catchup"
        private const val PHOTOS_ONLY = "photosOnly"

        private fun onWifi() = Constraints.Builder()
            // Unmetered rather than merely connected: BasilNet is only reachable on the home
            // network anyway, so trying on cellular burns battery to fail. It matters more for
            // photographs, where the attempt would be metered megabytes.
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.DAYS)
                .setConstraints(onWifi())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /**
         * One more batch of photographs, shortly.
         *
         * `KEEP` rather than `REPLACE`, so a pass that finishes while a catch-up is already
         * queued does not keep pushing the queued one further out — which is the shape of bug
         * that leaves a backlog permanently ten minutes away from starting.
         */
        fun catchUp(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(onWifi())
                .setInitialDelay(10, TimeUnit.MINUTES)
                .setInputData(workDataOf(PHOTOS_ONLY to true))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                CATCH_UP,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
