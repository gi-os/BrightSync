package com.gios.lightsync.sync

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.gios.lightsync.Prefs
import java.io.File
import java.io.FileInputStream

/**
 * One backup, or one restore, end to end.
 *
 * Everything that might ever change lives here rather than in the sixteen apps: where the
 * server is, how the payload is sealed, what counts as an error. An app only ever hands over
 * bytes.
 */
class Vault(private val context: Context) {

    private val prefs = Prefs(context)

    fun server(): Server? =
        if (prefs.ready) Server(prefs.server, prefs.token) else null

    /** Returns the number of apps backed up, or throws with the first failure. */
    fun backupAll(): Int {
        val server = server() ?: throw IllegalStateException("LightSync isn't set up yet")
        var done = 0
        val failures = mutableListOf<String>()
        Discovery.find(context).forEach { app ->
            runCatching { backup(app, server) }
                .onSuccess { done++ }
                .onFailure { failures += "${app.label}: ${it.message}" }
        }
        prefs.setLastError(failures.firstOrNull())
        if (done == 0 && failures.isNotEmpty()) throw IllegalStateException(failures.first())
        return done
    }

    fun backup(app: Backupable, server: Server = server()!!) {
        // Read whole rather than streamed: these payloads are kilobytes, sealing needs the
        // plaintext in one piece anyway, and the server caps the size it will accept.
        val plain = context.contentResolver.openFileDescriptor(app.exportUri, "r")?.use { fd ->
            FileInputStream(fd.fileDescriptor).use { it.readBytes() }
        } ?: throw IllegalStateException("${app.label} returned nothing to back up")

        server.put(app.pkg, Crypto.seal(plain, prefs.passphrase))
        prefs.setLastRun(app.pkg, System.currentTimeMillis())
    }

    /**
     * Pull the newest blob and hand it back to the app.
     *
     * The plaintext is staged in this app's cache for exactly as long as the handover takes, and
     * deleted after. There is no way to pass bytes of this size over a Binder call, and a file
     * plus a descriptor is the only route that doesn't involve a second server.
     */
    fun restore(app: Backupable) {
        val server = server() ?: throw IllegalStateException("LightSync isn't set up yet")
        val plain = Crypto.open(server.latest(app.pkg), prefs.passphrase)
        val staged = File(context.cacheDir, "restore-${app.pkg}")
        try {
            staged.writeBytes(plain)
            ParcelFileDescriptor.open(staged, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                val reply = context.contentResolver.call(
                    app.callUri,
                    "import",
                    null,
                    Bundle().apply { putParcelable("fd", fd) },
                )
                // A killed process is a *successful* restore for most apps — see the module's
                // restartAfterRestore — so a dead call is not treated as a failure.
                if (reply != null && !reply.getBoolean("ok", true)) {
                    throw IllegalStateException("${app.label} refused the restore")
                }
            }
        } finally {
            staged.delete()
        }
    }
}
