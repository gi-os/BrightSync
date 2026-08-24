# BrightSync

Backups for the LightX apps, onto BasilNet. One app to set up, one container to run, and a file
per app to opt in.

## Install via BrightMarket

<p align="center">
  <img src="https://gi-os.github.io/brightmarket-index/assets/qr/BrightSync.png" alt="Scan to open BrightSync in BrightMarket" width="180" />
</p>

Scan the code above with **BrightMarket** installed to open BrightSync there and
install or update it directly. Don't have BrightMarket yet? Get it, and browse
every Bright app, at
**[gi-os.github.io/brightmarket-index/browse.html](https://gi-os.github.io/brightmarket-index/browse.html)**.

| | |
|---|---|
| **Where it goes** | BasilNet, over the LAN, as one encrypted blob per app — and the camera roll into Immich |
| **When** | Daily on wifi, or whenever you tap *Back up everything* |
| **What the server sees** | Ciphertext and a package name. Nothing else |
| **Restore** | Tap an app twice |

## Why it isn't one app

Android's sandbox does not let one app read another's private data — no permission grants it, and
there is no root here. So a central agent cannot back anything up on its own; the data has to be
offered. BrightSync is therefore a pair: an agent that owns everything changeable, and a ~10-line
provider inside each app that hands over bytes.

That provider used to live here as `module/LightSyncBackup.kt`, a template you pasted in with the
package name swapped. It is now a class in **light-common**, which is where the sixteen copies
should have been all along — they had already drifted, and only two of them ever learned to
report a label.

That split is the whole design. The server address, the schedule, the encryption, the retention
and the restore UI all live in the agent, so none of them can ever force a release of sixteen
apps. And apps are *discovered* — anything publishing a `*.lightsync.backup` provider is in — so
adding the seventeenth app doesn't touch the agent either.

## Joining

```kotlin
// app/src/main/kotlin/.../backup/Backup.kt
class Backup : LightSyncBackup() {
    override fun label() = "Tip"
    override fun stores() = listOf(
        FileStore("settings", Contents(prefs = listOf("lighttip"))),
        FileStore("bills", Contents(databases = listOf("lighttip.db"))),
    )
}
```

```xml
<provider
    android:name=".backup.Backup"
    android:authorities="${applicationId}.lightsync.backup"
    android:exported="true" />
```

`implementation("com.gios:light-common:1.2.1")`, and that is the whole of it. The authority
suffix is the registration.

**One store per subsystem, not one flat list.** BrightNotebook is notes and a calendar and day
data; each has a different answer to "could another install open these bytes?". Under one list an
app had to pick the worst answer for all of them.

**Anything sealed with an AndroidKeyStore key needs a `LogicalStore`, not a `FileStore`.** That
key cannot leave the device and does not survive a factory reset, so copying the ciphertext gives
you a backup that restores cleanly and decrypts to nothing — worse than no backup, because it
looks like one. A `LogicalStore` gets a stream out and a stream in and writes something portable.
BrightChat does this for its BlueBubbles password; BrightRemote did not need to, because its
pairing keys are plain preferences.

## The fleet screen

The second tab lists every app on the phone that has offered itself, with the version it is
running, the version of light-common it was built against, what its backup would contain, and how
long ago it last went up.

The library column is the one that earns its place. Nearly every bug that turned out to span
apps was one app carrying an older copy of the shared code, and finding that out previously meant
opening twenty repositories. Every value comes from the app itself over the same provider used
for backups — nothing is fetched from GitHub — so the screen describes the phone in your hand and
not the state of a branch. An app that is installed but will not answer is listed too, dimmed,
because that is the row most worth seeing.

## Encryption

The phone seals every payload before it leaves:

```
"LSY1" | salt (16) | iv (12) | AES-256-GCM ciphertext+tag
key = PBKDF2-HMAC-SHA256(passphrase, salt, 200_000, 256)
```

BasilNet holds ciphertext, so it can store your TOTP secrets without being trusted with them.
The format is written down here on purpose: a backup you can only open with your own client isn't
a backup. `curl` the blob, strip 32 bytes of header, and any AES-GCM implementation will open it.

The passphrase is stored in plain SharedPreferences, and that is a deliberate trade rather than an
oversight. Wrapping it in an AndroidKeyStore key would hide it from a rooted shell — and would
make every blob unrecoverable after a factory reset, since the key would be gone. Surviving a lost
phone is the point, so the passphrase has to be something you can type again.

## The server

```bash
cd server
cp .env.example .env      # put a real token in it
docker compose up -d
curl -H "X-Token: $TOKEN" http://192.168.68.59:8099/
```

Three routes, ten versions kept per app, files on disk under `data/<package>/<epoch>.bin`. It
never decrypts anything and has no idea what it is holding. Bound to the LAN address rather than
`0.0.0.0` as a reminder that the token's backup is the network.

## Setting a phone up

Six values, two of them long random strings, thumbed into a phone whose keyboard is the size of
a stamp — and a typo in any of them surfaces as a 401 the following day rather than as an error
at the time. So the server draws them instead:

```bash
open http://192.168.68.59:8099/enroll/$LIGHTSYNC_TOKEN
```

That page is a QR code and the four steps in order. On the phone: **SCAN A CODE**, point it at
the screen, and the server address, the token, Immich and the album arrive together. The scanner
is ZXing over a CameraX stream — ML Kit's reader comes through Play Services, which LightOS does
not have, so it would bind and never answer.

The page has three variants, and *Photos only* is a real answer rather than a lesser one: Immich
needs no blob store and no passphrase, so a phone set up that way types nothing at all.

**If photographs are all you want, there is no server to run and no code to scan.** Choose
*Photos only* on first launch and give the phone your Immich address and the password you log
into it with. BrightSync signs in the way Immich's own app does, asks Immich for a key of its
own, and forgets the password — the key it keeps can upload, read and keep one album, and
nothing it holds can delete a photograph. That path needs no LightSync container at all: the
blob store, the token and the passphrase are for app data, and app data is the half you skipped.

**The passphrase is not in the code by default.** It is the one secret this server does not hold,
and the reason it cannot read your backups; the phone asks for it once after the scan. Setting
`ENROLL_PASSPHRASE` puts it in the QR and finishes setup in one scan — and means the container
can decrypt every blob on its disk. The page says which of the two you are looking at.

A first launch that finds nothing configured walks the same path: what this phone should send,
scan, passphrase, access to the roll. Every step is skippable and the settings screen still does
everything the guide does — the order is only the order in which the values become useful.

## Photographs

The roll goes to **Immich** on the same box, and it is the one payload here that is not
encrypted. That is a decision, not an omission: Immich decodes every file it is given to build
thumbnails, read EXIF, cluster faces and index places, so a library of ciphertext is a library of
nothing. Sealing photographs would have bought a megabyte-per-frame blob that only this app could
ever open, which is not a photo library — it is a folder you hope you never need.

```bash
cd server/immich
cp .env.example .env      # set DB_PASSWORD
docker compose up -d      # http://192.168.68.59:2283
```

Then, on the phone: Setup → PHOTOS → the Immich address and an API key with upload, read and
album rights. `server/immich/README.md` has the rest, including what the external libraries are
for.

**No app has to join.** This is the only part of BrightSync that needs no cooperation from the
app that produced the data. Roll writes photographs to `DCIM/Camera` through MediaStore, which is
shared storage rather than a sandbox, so the agent reads them under its own `READ_MEDIA_IMAGES`
grant. Nothing was added to light-common, and BrightImport's frames — pulled off a real camera
over its wifi — go up the same way without either app knowing this exists.

**Dedupe before the bytes move.** The roll is re-read from scratch every run, so nearly
everything the phone offers is already up. `POST /assets/bulk-upload-check` answers
accept-or-reject for a batch of SHA-1s in one small request, and only the accepted frames are
uploaded. Immich would reject the duplicates anyway — it hashes what it receives — but only after
the phone had pushed the file across the wifi to be told so.

**Fifty frames a pass, and the pass resumes.** A week away shooting is hundreds of files and
gigabytes; WorkManager will not hold a job open for that. So a run takes fifty, and if more are
waiting it queues another for ten minutes' time rather than for tomorrow. The watermark is
`DATE_MODIFIED` of the last frame that got through — a hint about where to start reading, no
more. Whether a frame goes up is answered by Immich against its own index, so a reset watermark
costs some hashing and uploads nothing twice.

**Nothing is deleted, ever.** A pass only ever adds. Clearing space on the phone is a decision
made in Roll, not a side effect of a backup, and there is no photo restore: Immich is a photo
library with its own clients, and `immich-go` moves the whole thing elsewhere if it ever needs
to. The rest of BrightSync restores because a blob is useless without this app; a JPEG is not.

## The work-calendar bridge

`server/calendar_bridge.py` rides along in the same container: Microsoft 365 in, one plain
`.ics` out, so a Light Phone III can carry a work calendar without any of Microsoft's
machinery on the phone. The phone's only job is a GET on a URL — see BrightNotebook's
"Subscribe to a URL".

It exists here rather than on the phone for three reasons. There is no usable OAuth library
on LightOS (MSAL and AppAuth both fail the "full browser" test that killed LightNews'
first attempt). A corporate refresh token has no good home on a sideloaded app. And
BrightNotebook's `IcsParser` deliberately does not expand `RRULE`, so a raw Outlook export
would show a weekly standup exactly once — whereas Graph's `calendarView` returns
*instances*, already expanded, which is what this writes out.

Set up once:

```bash
# .env
GRAPH_CLIENT_ID=<the Azure app registration's application id>
GRAPH_TENANT=<tenant id, or "organizations">
CALENDAR_SECRET=$(openssl rand -hex 24)
CALENDAR_NAME=Work
```

The Azure side is a **public client** app registration with delegated `Calendars.Read`,
"Allow public client flows" enabled. No client secret: this box holds a refresh token, not
a credential it could re-issue.

Then link the account — device code flow, because this process has no browser:

```bash
curl -s -XPOST -H "X-Token: $TOKEN" http://192.168.68.59:8099/cal/auth/start
# → {"go_to": "https://microsoft.com/devicelogin", "enter_code": "A1B2C3D4"}
# type the code in a browser on a real computer, then:
curl -s -H "X-Token: $TOKEN" http://192.168.68.59:8099/cal/status
```

**Expose only the feed.** If this goes through a Cloudflare tunnel, the ingress rule needs a
path, not just a hostname — otherwise the tunnel publishes the whole backup API and the health
endpoint's list of app package names along with it:

```
cal.basilnet.com  path ^/cal/[0-9a-f]+/work\.ics$  ->  http://lightsync:8099
cal.basilnet.com                                   ->  http_status:404
```

`/cal/status` and `/cal/auth/*` stay LAN-only, which is where you drive them from anyway. Use
the container name rather than the host's LAN address if cloudflared shares a docker network
with it — BasilNet does not answer its own LAN address from inside itself.

The feed is then at `/cal/<CALENDAR_SECRET>/work.ics`, refreshed every 20 minutes, and
served **from the last good file** — an outage at Microsoft's end, or a revoked token, still
hands the phone the calendar it had. That URL has no header auth, because a calendar client's
whole vocabulary is GET: **the URL is the credential.** Rotate `CALENDAR_SECRET` if it leaks.

`python3 server/test_calendar_bridge.py` covers the iCalendar half (folding, escaping,
all-day dates, cancelled events). The network half is deliberately untested — what can go
wrong there is Microsoft's answer, not this code's arithmetic.

## Adding an app

See [`module/README.md`](module/README.md). Copy one file, declare what to include, add six lines
of manifest:

```kotlin
class Backup : LightSyncBackup() {
    override fun contents() = Contents(
        prefs = listOf("lightnotebook"),
        databases = listOf("notebook.db"),
        files = listOf("entries"),
    )
}
```

**But not always.** If an app's stored form can't be read anywhere else — anything wrapped with an
AndroidKeyStore key, which by design never leaves the device — backing up the files produces
something that restores into nothing. Those apps override `export`/`restore` and emit a portable
payload instead. BrightAuthenticator is the worked example: it exports `otpauth://` URIs rather than its
database, because the database holds secrets encrypted with a key that dies with the phone.

## The trust boundary, stated plainly

Each LightX app is signed with its own keystore, so a `signature`-level permission can't match
across them. Instead each provider requires the caller to be BrightSync by package **and** by
signing certificate, pinned in the module as a SHA-256 digest. A hostile app can claim the package
name only by also holding the key. That is weaker than a platform permission and stronger than a
name check, and on a phone whose only installer is you it's a reasonable trade — written down here
rather than assumed.

## Gotchas, in the order they'll bite

**BasilNet is LAN-only.** A day out shooting photographs backs up nothing until you are home; the
daily job just waits, and then works through the roll fifty frames at a time. Tailscale on both
ends would fix it — and would be a bad idea for the photo half specifically, since that traffic
is in the clear and would then be in the clear across a tunnel you did not audit.

**A photo library is not a backup of the phone.** Immich holds what the phone sent it. Delete a
frame on the phone after it went up and Immich keeps it; delete it in Immich and the next pass
does not put it back, because the phone's watermark has moved past it. That asymmetry is the
price of not treating photographs as blobs, and it is the right way round for a roll.

**Restore kills the app.** Prefs and Room cache in memory, so an app still running after its files
were swapped underneath it will write the old state back over the new. The module ends the process
after a restore, which looks like a crash and is the cheapest way to be correct.

**A restore is destructive and confirmed by a second tap**, not a dialog. It overwrites what's on
the phone with what's on the server, and there is no undo beyond the previous ten versions.

**Errors are recorded, not thrown away.** A backup quietly failing for a month is worse than one
that never ran, so the last failure sits on the front screen until a run succeeds.

## Layout

```
server/app.py            three routes, retention, no crypto
server/enroll.py         the setup page: one QR, and what it does and does not carry
server/immich/           the photo library: compose, external libraries, Quick Sync
server/calendar_bridge.py Microsoft Graph → one .ics the phone can GET
module/LightSyncBackup.kt the per-app contribution: zip, restore, caller check
sync/Crypto.kt           seal and open, format documented above
sync/Server.kt           the three calls, HttpURLConnection
sync/Discovery.kt        find apps by provider authority suffix
sync/Vault.kt            one backup or one restore, end to end
sync/Immich.kt           the photo path: bulk-check, multipart upload, albums
sync/Roll.kt             DCIM through MediaStore, and the SHA-1 Immich dedupes on
sync/Photos.kt           one resumable pass over the roll
sync/Enrollment.kt       the scanned URI, parsed and applied
qr/QrAnalyzer.kt         ZXing over a CameraX stream, ported from Roll
ui/FirstRunScreen.kt     the guided setup, and the photos-only path through it
sync/SyncWorker.kt       daily, unmetered
ui/AppsScreen.kt         what's backed up, when, and the two verbs
ui/SetupScreen.kt        server, token, passphrase, reachability
```

## Not doing

- **Sync.** This is backup and restore: one direction at a time, chosen by you. Two-way sync needs
  conflict resolution, and nothing here is edited on two devices.
- **Restoring photographs.** They go up and stay up. Immich has its own clients for getting them
  back, and putting a 400-frame roll back onto a phone with 32GB is not a thing anyone wants.
- **Photos through the blob store.** Tried on paper and dropped: it needed the server to hold the
  passphrase to hand anything to Immich, which would have cost the one property the rest of this
  design is built on.
- **Trusting the server.** It stores what it's given and hands it back. That's all.
