"""
LightSync's server half: store a blob per app, hand back the newest one.

Deliberately dumb. It never decrypts anything — the phone encrypts before upload, so this
process holds ciphertext and a filename and has no idea whether it is holding your TOTP
secrets or a nonogram save. That is the whole security argument, and it means losing the
server loses nothing but convenience.

It is also greppable and curl-able on purpose. A backup you can only read with your own
client is not a backup; blobs are plain files under a dated name, so the recovery path of
last resort is `curl` and `openssl`, documented in the README.
"""

import os
import time
from pathlib import Path

from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse

import calendar_bridge
import enroll

ROOT = Path(os.environ.get("LIGHTSYNC_DIR", "/data"))
TOKEN = os.environ.get("LIGHTSYNC_TOKEN", "")
KEEP = int(os.environ.get("LIGHTSYNC_KEEP", "10"))
MAX_BYTES = int(os.environ.get("LIGHTSYNC_MAX_MB", "64")) * 1024 * 1024

app = FastAPI(title="LightSync", lifespan=calendar_bridge.lifespan)

# The work-calendar bridge (Microsoft 365 → one .ics the phone can fetch) rides along in
# this container rather than in one of its own: it is the same box, the same token and the
# same data volume, and a second service to keep running would buy nothing.
app.include_router(calendar_bridge.router)

# Setting a phone up by pointing it at a screen. Same reasoning again: it needs this process's
# token and the Immich address, and a second service to hold the same two values would be a
# second place for them to drift.
app.include_router(enroll.router)


def check(token: str | None) -> None:
    # A constant-time compare would be theatre here: this is a LAN service behind a single
    # token whose real protection is the network it sits on. Refusing to run without a token
    # at all is the part that matters.
    if not TOKEN:
        raise HTTPException(500, "server has no LIGHTSYNC_TOKEN set")
    if token != TOKEN:
        raise HTTPException(401, "bad token")


def folder(app_id: str) -> Path:
    # Package names only. Anything else is a path-traversal attempt or a bug, and both
    # deserve the same answer.
    if not app_id or "/" in app_id or ".." in app_id or len(app_id) > 128:
        raise HTTPException(400, "bad app id")
    d = ROOT / app_id
    d.mkdir(parents=True, exist_ok=True)
    return d


@app.get("/")
def health():
    # Underscore-prefixed folders are this server's own state (the calendar bridge keeps
    # its token and .ics in one), not somebody's backup.
    return {
        "ok": True,
        "apps": sorted(
            p.name for p in ROOT.iterdir() if p.is_dir() and not p.name.startswith("_")
        ),
    }


@app.put("/b/{app_id}")
async def put(app_id: str, request: Request, x_token: str = Header(default=None)):
    check(x_token)
    body = await request.body()
    if not body:
        raise HTTPException(400, "empty body")
    if len(body) > MAX_BYTES:
        raise HTTPException(413, "too big")

    d = folder(app_id)
    name = f"{int(time.time())}.bin"
    # Written beside the target and renamed, so a dropped wifi mid-upload leaves no
    # half-written blob that `latest` would then happily serve back.
    tmp = d / (name + ".part")
    tmp.write_bytes(body)
    tmp.rename(d / name)

    blobs = sorted(d.glob("*.bin"))
    for old in blobs[:-KEEP]:
        old.unlink(missing_ok=True)
    return {"stored": name, "bytes": len(body), "versions": len(blobs[-KEEP:])}


@app.get("/b/{app_id}")
def versions(app_id: str, x_token: str = Header(default=None)):
    check(x_token)
    d = folder(app_id)
    return JSONResponse([
        {"name": p.name, "bytes": p.stat().st_size, "at": int(p.stem)}
        for p in sorted(d.glob("*.bin"))
    ])


@app.get("/b/{app_id}/latest")
def latest(app_id: str, x_token: str = Header(default=None)):
    check(x_token)
    blobs = sorted(folder(app_id).glob("*.bin"))
    if not blobs:
        raise HTTPException(404, "nothing stored for this app")
    return FileResponse(blobs[-1], media_type="application/octet-stream")
