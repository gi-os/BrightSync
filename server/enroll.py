"""
Setting up a phone by pointing it at a screen.

BrightSync needs five values that are tedious and error-prone to thumb in on a phone with no
keyboard worth the name: where the blob store is, its token, the passphrase, where Immich is and
its API key. Four of the five are already in this container's environment. This route puts them
in a QR code, and the phone reads them off the screen in one go.

**The URL is the credential**, as it is for the calendar feed: the token is in the path, because
a browser cannot send a header and the setup page is opened in one. That is also why this is
worth being careful about — see the passphrase note below.
"""

import os
from urllib.parse import quote

import segno
from fastapi import APIRouter, HTTPException
from fastapi.responses import HTMLResponse

router = APIRouter()

TOKEN = os.environ.get("LIGHTSYNC_TOKEN", "")

# What the *phone* should call this box, which is not what the container calls itself. Defaults
# to the LAN address the README uses, because that is the only address BasilNet answers on for
# a device in the house.
PHONE_URL = os.environ.get("LIGHTSYNC_PHONE_URL", "http://192.168.68.59:8099")

IMMICH_URL = os.environ.get("IMMICH_URL", "")
IMMICH_KEY = os.environ.get("IMMICH_KEY", "")
IMMICH_ALBUM = os.environ.get("IMMICH_ALBUM", "Light Phone III")

# The passphrase, if you want one scan to finish the job.
#
# Read this before setting it. Everything else here is a credential the server already holds;
# this one is the key to the blobs, and the entire argument for the blob store is that the
# server cannot read what it is storing. Put the passphrase in this container's environment and
# that stops being true — anyone who can read the .env can decrypt every backup on the disk.
#
# Left empty, the QR carries everything else and the phone asks you to type this one value once.
# That is the default, and the setup page says so.
PASSPHRASE = os.environ.get("ENROLL_PASSPHRASE", "")


def payload(mode: str) -> str:
    """
    The URI the phone reads.

    A custom scheme rather than JSON: it is shorter, which matters because every character is
    another module in the QR and a denser code is a code that will not scan across a room. Keys
    are one letter for the same reason.
    """
    fields = [("v", "1")]
    if mode != "photos":
        fields += [("s", PHONE_URL), ("t", TOKEN)]
        if PASSPHRASE:
            fields.append(("p", PASSPHRASE))
    if mode != "backups" and IMMICH_URL:
        fields += [("i", IMMICH_URL), ("k", IMMICH_KEY), ("a", IMMICH_ALBUM)]
    return "brightsync://setup?" + "&".join(f"{k}={quote(v, safe='')}" for k, v in fields)


def code(data: str) -> str:
    """
    One QR as inline SVG.

    Error correction M rather than the default L: this is read off a lit screen by a phone held
    at arm's length, and the two things that break that — a reflection and a hand that moves —
    are exactly what the extra redundancy covers. `scale` is generous for the same reason.
    """
    return segno.make(data, error="m").svg_inline(scale=6, dark="#000", light="#fff")


@router.get("/enroll/{token}", response_class=HTMLResponse)
def enroll(token: str, mode: str = "everything"):
    if not TOKEN:
        raise HTTPException(500, "server has no LIGHTSYNC_TOKEN set")
    if token != TOKEN:
        # 404 rather than 401: an unauthenticated URL that answers differently for a wrong
        # token tells a scanner it has found something worth guessing at.
        raise HTTPException(404, "not found")
    if mode not in ("everything", "photos", "backups"):
        raise HTTPException(400, "mode must be everything, photos or backups")

    data = payload(mode)
    has_immich = bool(IMMICH_URL)
    passphrase_note = (
        "The passphrase is in this code, so one scan finishes setup. It is also now in this "
        "container's environment, which means this server can decrypt the blobs it stores — "
        "the one property the blob store otherwise has. Unset ENROLL_PASSPHRASE to go back."
        if PASSPHRASE
        else "The passphrase is <b>not</b> in this code, deliberately: it is the one secret this "
        "server does not hold, and the reason it cannot read your backups. The phone asks you "
        "for it once after the scan."
    )

    return f"""<!doctype html>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Set up BrightSync</title>
<style>
  body {{ background:#000; color:#fff; font:16px/1.6 system-ui, sans-serif; margin:0;
         padding:32px; display:flex; flex-direction:column; align-items:center; }}
  main {{ max-width:34rem; }}
  h1 {{ font-size:1.4rem; letter-spacing:.02em; }}
  .code {{ background:#fff; padding:16px; width:max-content; margin:24px auto; }}
  ol {{ padding-left:1.2rem; }}
  li {{ margin:.6rem 0; }}
  code {{ background:#161616; padding:.1rem .35rem; }}
  .dim {{ color:#8a8a8a; }}
  nav a {{ color:#fff; margin-right:1rem; }}
</style>
<main>
<h1>Set up BrightSync</h1>
<div class="code">{code(data)}</div>
<ol>
  <li>Open <b>BrightSync</b> on the phone and tap <b>SCAN A CODE</b>.</li>
  <li>Point it at this screen. Server, token{" and Immich" if has_immich else ""} arrive together.</li>
  <li>{"Type your passphrase when it asks." if not PASSPHRASE else "Nothing left to type."}</li>
  <li>Allow access to the roll, then tap <b>Back up everything</b> once to prove the path.</li>
</ol>
<p class="dim">{passphrase_note}</p>
<p class="dim">This code carries live credentials. It is on your LAN behind the token in this
page's own URL — close the tab when you are done, and rotate the token if the screen was
photographed by someone else.</p>
<nav class="dim">
  <a href="/enroll/{token}?mode=everything">Everything</a>
  <a href="/enroll/{token}?mode=photos">Photos only</a>
  <a href="/enroll/{token}?mode=backups">Backups only</a>
</nav>
</main>
"""
