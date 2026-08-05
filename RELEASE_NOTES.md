## LightSync v1.2 — A fleet tab, and the backup provider moves into light-common

### The fleet tab

A second tab listing every app that has offered itself for backup, and for each one: the version
it is running, which light-common it was built against, what its backup would actually contain,
roughly how big that is, and when it last went up.

The library column is the reason the screen exists. Nearly every bug that turned out to affect
more than one app was one app carrying an older copy of the shared wheel or the shared report
code, and the only way to find that out was to open twenty repositories one at a time. Now it is
a line on the phone, and anything behind the newest version anyone is carrying says so.

"Newest" is measured across the fleet rather than against this app. LightSync is usually not the
first thing rebuilt after a library release, so a screen that used the agent's own version as the
bar would declare everything current on exactly the day the agent fell behind.

Nothing here is fetched from GitHub. Every value comes from the app itself, over the same
provider LightSync already uses to back it up, so the screen is about the phone in your hand. An
app that is installed but does not answer is listed anyway, dimmed, with the reason — that row is
usually the one worth looking at.

Versions sort as numbers, not as text. `1.10.0` is newer than `1.9.0`, and there is a test for it,
because the failure here is not a crash: it is a wrong label on a screen you would then trust.

### The provider is shared code now

`module/LightSyncBackup.kt` is gone. It was a template you pasted into an app with the package
name swapped, sixteen times, and the copies had drifted exactly where you would expect — the
signature check was tightened once and stayed loose everywhere nobody re-pasted, and only two of
them ever answered with a label. It is a real class in light-common now, versioned with
everything else.

**The archive layout did not change**, so every blob already on BasilNet restores into a migrated
app. An app that declared a single flat file list needs no code change beyond the import.

What is new is that an app can describe itself in parts. LightNotebook backs up notes, settings,
captures and day data as four separate stores; LightTip separates the receipts it cannot recover
from the settings you could retype in seconds. And an app whose data is sealed with an
AndroidKeyStore key can now export something portable instead — that key cannot leave the phone
and does not survive a factory reset, so the old behaviour of copying the ciphertext produced a
backup that restored cleanly and decrypted to nothing.

### Smaller

The wheel comes from light-common, so this app's copy is deleted. Same feel — the smoothing, the
two-notch bump guard and the idle timeout are the same constants.

R8 is on, in full mode, for the first time in this app. Keep rules are written out rather than
inherited, and the one that matters is `SyncWorker`: WorkManager stores the worker's class name
in its own database and rebuilds it by name after a reboot, so a rename would stop the daily
backup silently rather than loudly. That is the failure worth watching for after this update — if
the Apps tab starts showing ages that only ever grow, that is the one.
