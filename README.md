# LightSync

Backups for the LightX apps, onto BasilNet. One app to set up, one container to run, and a file
per app to opt in.

| | |
|---|---|
| **Where it goes** | BasilNet, over the LAN, as one encrypted blob per app |
| **When** | Daily on wifi, or whenever you tap *Back up everything* |
| **What the server sees** | Ciphertext and a package name. Nothing else |
| **Restore** | Tap an app twice |

## Why it isn't one app

Android's sandbox does not let one app read another's private data — no permission grants it, and
there is no root here. So a central agent cannot back anything up on its own; the data has to be
offered. LightSync is therefore a pair: an agent that owns everything changeable, and a ~10-line
provider inside each app that hands over bytes.

That split is the whole design. The server address, the schedule, the encryption, the retention
and the restore UI all live in the agent, so none of them can ever force a release of sixteen
apps. And apps are *discovered* — anything publishing a `*.lightsync.backup` provider is in — so
adding the seventeenth app doesn't touch the agent either.

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

## The work-calendar bridge

`server/calendar_bridge.py` rides along in the same container: Microsoft 365 in, one plain
`.ics` out, so a Light Phone III can carry a work calendar without any of Microsoft's
machinery on the phone. The phone's only job is a GET on a URL — see LightNotebook's
"Subscribe to a URL".

It exists here rather than on the phone for three reasons. There is no usable OAuth library
on LightOS (MSAL and AppAuth both fail the "full browser" test that killed LightNews'
first attempt). A corporate refresh token has no good home on a sideloaded app. And
LightNotebook's `IcsParser` deliberately does not expand `RRULE`, so a raw Outlook export
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
payload instead. LightAuth is the worked example: it exports `otpauth://` URIs rather than its
database, because the database holds secrets encrypted with a key that dies with the phone.

## The trust boundary, stated plainly

Each LightX app is signed with its own keystore, so a `signature`-level permission can't match
across them. Instead each provider requires the caller to be LightSync by package **and** by
signing certificate, pinned in the module as a SHA-256 digest. A hostile app can claim the package
name only by also holding the key. That is weaker than a platform permission and stronger than a
name check, and on a phone whose only installer is you it's a reasonable trade — written down here
rather than assumed.

## Gotchas, in the order they'll bite

**BasilNet is LAN-only.** A day out shooting photos backs up nothing until you're home; the daily
job just waits. Tailscale on both ends would fix it.

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
server/calendar_bridge.py Microsoft Graph → one .ics the phone can GET
module/LightSyncBackup.kt the per-app contribution: zip, restore, caller check
sync/Crypto.kt           seal and open, format documented above
sync/Server.kt           the three calls, HttpURLConnection
sync/Discovery.kt        find apps by provider authority suffix
sync/Vault.kt            one backup or one restore, end to end
sync/SyncWorker.kt       daily, unmetered
ui/AppsScreen.kt         what's backed up, when, and the two verbs
ui/SetupScreen.kt        server, token, passphrase, reachability
```

## Not doing

- **Sync.** This is backup and restore: one direction at a time, chosen by you. Two-way sync needs
  conflict resolution, and nothing here is edited on two devices.
- **Photos, yet.** LightCamera's roll is the one payload that is megabytes rather than kilobytes
  and wants hash-dedupe and chunking of its own.
- **Trusting the server.** It stores what it's given and hands it back. That's all.
