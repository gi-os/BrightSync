package com.gios.lightsync

import android.app.Application
import com.gios.lightsync.sync.SyncWorker

class SyncApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Re-enqueued on every start rather than once: KEEP semantics make that free, and it is
        // how a schedule change ever reaches a phone that is already set up.
        SyncWorker.schedule(this)
    }
}
