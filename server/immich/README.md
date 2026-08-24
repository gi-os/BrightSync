# Immich, on BasilNet

The photo half of BrightSync. Blobs go to the LightSync container next door, which cannot read
them; photographs come here, which must — see `app/src/main/kotlin/.../sync/Immich.kt` for why
that is a deliberate exception rather than an oversight.

```bash
cd /volume1/docker/immich
cp .env.example .env        # set DB_PASSWORD
docker compose up -d
curl -s http://127.0.0.1:2283/api/server/ping   # {"res":"pong"}
```

Three things differ from the upstream compose, and each is load-bearing:

**Library and database on `/volume2`.** That is the NVMe volume. Immich's Postgres holds the
vector index the whole app searches through, and on the RAID5 pool every screen in the web UI
feels broken.

**`/volume1/Photos` and `/volume1/Lupo Family Photos` mounted read-only**, registered as
*external* libraries rather than uploaded. Immich indexes 400GB of camera folders in place —
`X-Pro3`, `Z6-III`, `GRIII HDF`, `Cybershot T7`, the film scans — and because the mounts are
`:ro` it cannot move, rename or delete a single file it did not put there itself. Immich owns
`/data` and nothing else.

**`/dev/dri` passed through** for Quick Sync on the Alder Lake iGPU, so generating video
thumbnails does not pin all six cores. Turn it on under Administration → Settings →
Video Transcoding → Hardware Acceleration → Quick Sync.

## The API key the phone uses

Account Settings → API Keys, with upload, read and album rights — not `all`. The phone holds it
in plain preferences like everything else there, so it is scoped to what a lost phone should be
able to do: add photographs, and nothing that deletes one. Rotating it in Immich cuts the phone
off without touching the app.

## What this replaced

`photo-bridge`, which watched the LightSync data directory and pushed frames into iCloud with an
app-specific password. It worked, and it was a strange shape: photographs went up encrypted,
were decrypted on the server, and then left the house again to a service that would not let you
read them back without an Apple device. Immich is the same job with the round trip removed.
