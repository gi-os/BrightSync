package com.gios.lightsync.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gios.lightsync.Prefs
import java.util.concurrent.TimeUnit

/**
 * The daily run.
 *
 * A failure here is almost always "not on the home wifi", which is not a failure worth
 * retrying aggressively — hence [Result.success] on a network error and a plain wait for
 * tomorrow. Real errors are recorded for the settings screen instead of being thrown away,
 * because a backup that has been quietly failing for a month is worse than one that never ran.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        if (!prefs.ready || !prefs.autoDaily) return Result.success()
        return runCatching { Vault(applicationContext).backupAll() }
            .fold(
                onSuccess = {
                    prefs.setLastError(null)
                    Result.success()
                },
                onFailure = {
                    prefs.setLastError(it.message ?: it.javaClass.simpleName)
                    Result.success()
                },
            )
    }

    companion object {
        private const val NAME = "lightsync-daily"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        // Unmetered rather than merely connected: BasilNet is only reachable on
                        // the home network anyway, so trying on cellular burns battery to fail.
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
