## BrightSync v1.3 — The roll goes to Immich

Photographs were under "Not doing" for two releases. They are in now, and not as blobs.

### Setup is a QR code now

`/enroll/<token>` on the container renders the phone's whole configuration as a code: server,
token, Immich, album. Scan it and the phone is set up. The passphrase stays out of it unless you
set `ENROLL_PASSPHRASE` — it is the one value the server does not hold, and holding it would cost
the property the blob store exists for. The page says plainly which of the two it is showing.

A first launch with nothing configured now walks four steps instead of opening onto a wall of
empty fields, and **photos only** is one of the answers: Immich needs no blob store and no
passphrase, so that path is two taps and no typing. Backups only works the same way in reverse,
and neither half nags the front screen about a server you deliberately skipped.

Scanning is ZXing over a CameraX stream, ported from Roll along with its row-stride fix. The
camera is bound by one screen, for as long as that screen is up, with nothing attached that could
write a frame anywhere.

### Photographs on their own, set up with a password

An Immich API key is forty-three characters of base64, and typing it on this keyboard was the
worst thing this app asked of anyone — and the only step where one wrong character stays silent
until an upload fails hours later. So the phone now signs in the way Immich's own app does, asks
the server for a key of its own, and forgets the password. The key it keeps is scoped to
uploading, reading and one album, so a lost phone can add photographs and delete nothing.

Every Immich path now has a code behind it. `server/enroll_qr.py` prints one from any terminal
— it signs in, mints the scoped key and draws the QR, with no container running anywhere — and
the sign-in screen leads with **SCAN A CODE** rather than burying it. A QR holding nothing but an
address is accepted as well, with the phone asking for the password afterwards; a link to an
album or a shared photograph is not, because that is a URL someone meant to open.

That makes "photos only" a two-field setup with no LightSync container behind it: the blob store,
the token and the passphrase exist for app data, and a phone that only wants its roll on Immich
needs none of the three.

### Immich, on the same box

`server/immich` stands up Immich next to the LightSync container: the library and its Postgres on
the NVMe volume, and `/volume1/Photos` plus `/volume1/Lupo Family Photos` mounted read-only as
*external* libraries, so 400GB of camera folders — X-Pro3, Z6-III, GRIII, the Cybershots, the
film scans — are indexed where they already live and cannot be moved by anything Immich does.

The phone talks to it directly with an API key: `bulk-upload-check` to ask what is new,
`POST /assets` to hand over the frames that are, and an album so the roll is one tap away from
the timeline. Ordinary Immich calls, so an ordinary Immich client — or `immich-go` — reads the
library back.

### Photographs are not encrypted, and that is the point

Everything else here is sealed on the phone and lands as a blob BasilNet cannot read. That cannot
work for a photo library: Immich decodes every file it is given to build thumbnails, read EXIF,
cluster faces and index places. Sealing the roll would have produced a megabyte-per-frame archive
that only this app could open, which is a folder you hope you never need rather than a library.

So the roll is a stated exception, in the README and in `sync/Immich.kt`, and the API key it uses
grants upload and album rights rather than `all` — a lost phone can add photographs and delete
nothing.

### No app had to join

This is the one payload that needed no cooperation from the app that made it. Roll writes to
`DCIM/Camera` through MediaStore, which is shared storage, so the agent reads it under its own
grant. Nothing was added to light-common and no other repository changed. BrightImport's frames,
pulled off a real camera over its wifi, go up the same way without either app knowing.

### A tap uploads the whole roll

Fifty frames is a batch, not an answer. Tapping *Back up the roll* now keeps going until the roll
is on the server, counting up while it works — a phone with four hundred new frames asking to be
tapped eight times was a bug wearing a design's clothes.

The background run keeps a limit, because WorkManager stops a worker that runs long, but the
limit is time: nine minutes, then a catch-up queued a minute later if anything is left. A week
away shooting drains in a few of those rather than a fortnight of daily fifties. The watermark is the last frame that
got through, and it is only a hint about where to start reading — Immich answers on checksums, so
a reset watermark costs some hashing and re-uploads nothing. Nothing is ever deleted from the
phone by a pass.

`photo-bridge` is retired. It decrypted blobs on the server and pushed them into iCloud, which
meant photographs left the house twice to end up somewhere you needed an Apple device to read.

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
