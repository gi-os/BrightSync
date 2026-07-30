package PACKAGE.backup

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The whole of an app's contribution to LightSync.
 *
 * Android's sandbox is why this exists at all: no agent app can read another app's private
 * data, with or without permissions, so backup cannot be done *to* an app from outside. It has
 * to be offered. This class is that offer, and it is deliberately the only thing an app has to
 * carry — the server address, the schedule, the encryption, the retention and the restore UI all
 * live in LightSync, so none of them can force a release of anything else.
 *
 * Two ways to answer. Most apps declare [contents] and let the base class zip the obvious
 * things. An app whose on-disk form is *unrestorable elsewhere* — anything encrypted with an
 * AndroidKeyStore key, which by design cannot leave the device — must instead override [export]
 * and [restore] and produce something portable. Backing up ciphertext whose key dies with the
 * phone is worse than no backup, because it looks like one.
 *
 * ### The trust boundary, stated plainly
 *
 * Every app here is signed with its own keystore, so a `signature`-level permission cannot match
 * across them. Instead the caller must be LightSync by package *and* by signing certificate,
 * pinned below. That is weaker than a platform permission and stronger than a package-name
 * check alone: a hostile app can claim the name only by also holding the key. On a phone whose
 * only installer is you it is a reasonable trade, and it is written down rather than assumed.
 */
abstract class LightSyncBackup : ContentProvider() {

    /** What to include, for apps whose files are portable as they sit. */
    data class Contents(
        /** SharedPreferences names, without `.xml`. */
        val prefs: List<String> = emptyList(),
        /** Database file names, as passed to Room. Journals travel with them. */
        val databases: List<String> = emptyList(),
        /** Paths under `filesDir`, files or directories. */
        val files: List<String> = emptyList(),
        /**
         * Whether to end the app's process after a restore.
         *
         * On by default, and not paranoia: prefs and Room both cache in memory, so an app that
         * keeps running after its files were swapped underneath it will happily write the old
         * state back over the new one. Dying is the cheapest way to be sure.
         */
        val restartAfterRestore: Boolean = true,
    )

    /** A short name for the LightSync app list. Defaults to the app's own label. */
    open fun label(): String = context?.applicationInfo?.let {
        context?.packageManager?.getApplicationLabel(it)?.toString()
    } ?: "App"

    open fun contents(): Contents = Contents()

    /** Produce the payload. Override for a logical export; otherwise [contents] is zipped. */
    open fun export(out: FileOutputStream) {
        val ctx = context ?: return
        val c = contents()
        ZipOutputStream(out.buffered()).use { zip ->
            c.prefs.forEach { name ->
                add(zip, "prefs/$name.xml", File(ctx.dataDir, "shared_prefs/$name.xml"))
            }
            c.databases.forEach { name ->
                val db = ctx.getDatabasePath(name)
                add(zip, "db/$name", db)
                // Room's write-ahead log holds committed rows that are not in the .db yet, so a
                // backup without it can be minutes stale — or inconsistent.
                add(zip, "db/$name-wal", File(db.path + "-wal"))
                add(zip, "db/$name-shm", File(db.path + "-shm"))
            }
            c.files.forEach { rel -> addTree(zip, "files/$rel", File(ctx.filesDir, rel)) }
        }
    }

    /** Consume a payload produced by [export]. */
    open fun restore(input: FileInputStream) {
        val ctx = context ?: return
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry: ZipEntry = zip.nextEntry ?: break
                val target = destination(ctx, entry.name) ?: continue
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { zip.copyTo(it) }
            }
        }
    }

    private fun destination(ctx: Context, entry: String): File? = when {
        // Only the three shapes export writes. Anything else is a malformed or hostile archive,
        // and an unrecognised path is exactly how a zip escapes the directory it belongs in.
        entry.startsWith("prefs/") -> File(ctx.dataDir, "shared_prefs/" + entry.removePrefix("prefs/"))
        entry.startsWith("db/") -> ctx.getDatabasePath(entry.removePrefix("db/"))
        entry.startsWith("files/") -> File(ctx.filesDir, entry.removePrefix("files/"))
        else -> null
    }?.takeIf { !it.path.contains("..") }

    private fun add(zip: ZipOutputStream, name: String, file: File) {
        if (!file.isFile) return
        zip.putNextEntry(ZipEntry(name))
        FileInputStream(file).use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun addTree(zip: ZipOutputStream, name: String, file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { addTree(zip, "$name/${it.name}", it) }
        } else {
            add(zip, name, file)
        }
    }

    // ------------------------------------------------------------------ the plumbing

    final override fun onCreate(): Boolean = true

    final override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        requireAgent()
        if (uri.lastPathSegment != "export") return null
        val ctx = context ?: return null
        // A pipe would avoid the temp file, but it needs a thread writing into it and a reader
        // that never stalls; a file in cacheDir is dull and cannot deadlock.
        val staged = File(ctx.cacheDir, "lightsync-export.zip")
        FileOutputStream(staged).use { export(it) }
        return ParcelFileDescriptor.open(staged, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    final override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        requireAgent()
        return when (method) {
            "meta" -> Bundle().apply { putString("label", label()) }
            "import" -> {
                @Suppress("DEPRECATION")
                val fd = extras?.getParcelable<ParcelFileDescriptor>("fd")
                    ?: return Bundle().apply { putBoolean("ok", false) }
                fd.use { FileInputStream(it.fileDescriptor).use { input -> restore(input) } }
                val restart = contents().restartAfterRestore
                Bundle().apply { putBoolean("ok", true) }.also {
                    if (restart) Process.killProcess(Process.myPid())
                }
            }
            else -> null
        }
    }

    /**
     * LightSync, or nobody. Checked on every call rather than cached: the answer can change
     * under us if the agent is reinstalled with a different key, and that is the case worth
     * catching.
     */
    private fun requireAgent() {
        val ctx = context ?: throw SecurityException("no context")
        if (callingPackage != AGENT_PACKAGE) throw SecurityException("not LightSync")
        val digest = runCatching { certDigest(ctx, AGENT_PACKAGE) }.getOrNull()
        if (digest != AGENT_CERT_SHA256) throw SecurityException("LightSync signature mismatch")
    }

    private fun certDigest(ctx: Context, pkg: String): String {
        val pm = ctx.packageManager
        val certs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners ?: emptyArray()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures ?: emptyArray()
        }
        val sha = MessageDigest.getInstance("SHA-256")
        return certs.firstOrNull()?.let { sha.digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }
            ?: ""
    }

    final override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    final override fun getType(uri: Uri): String = "application/octet-stream"
    final override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    final override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    final override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0

    companion object {
        const val AGENT_PACKAGE = "com.gios.lightsync"

        /** LightSync's release signing certificate. See its `signing-fingerprint.txt`. */
        const val AGENT_CERT_SHA256 = "bd7da1be119bc3b801523994c3da43556689db4547feaeaadb484b9ace83890e"
    }
}
