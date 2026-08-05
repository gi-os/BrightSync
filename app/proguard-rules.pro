# LightSync — R8 keep rules.
#
# Compose, coroutines and WorkManager all ship consumer rules inside their AARs, and
# light-common ships its own for the report queue and the backup provider. What follows is only
# the part no library can know: where *this* app is reached by name rather than by a call.

# ---------------------------------------------------------------- WorkManager
#
# The daily backup is enqueued once and survives reboots, so WorkManager has the worker's class
# name written into its own database from an install that may be several versions old. It
# rebuilds the worker with Class.forName plus the (Context, WorkerParameters) constructor. Rename
# either and the job fails to instantiate on the next wake-up — silently, because WorkManager
# logs it and moves on, which for a backup means it simply stops happening.
-keep class com.gios.lightsync.sync.SyncWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ---------------------------------------------------------------- manifest components
#
# aapt2 keeps a class named in the manifest, but in full mode a class keep no longer implies
# keeping its members, and a component with no visible no-arg constructor cannot be created.
-keep class com.gios.lightsync.MainActivity { public <init>(); }
-keep class com.gios.lightsync.SyncApp { public <init>(); }

# ---------------------------------------------------------------- deliberately no rule
#
# The blob format is bytes, not fields: "LSY1" is a literal, and the salt, IV and ciphertext are
# read by offset. Nothing about it depends on a class name surviving, so it needs no rule and a
# reader of this file should not add one.
#
# The provider meta bundle is read by string key from SyncMeta, whose constants kotlinc inlines
# at the call site. The object itself may be removed.
#
# Discovery finds apps through PackageManager by authority suffix — a string comparison against
# data owned by the system, not by this APK.

# ---------------------------------------------------------------- diagnostics
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
