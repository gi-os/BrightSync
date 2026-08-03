"""
The work-calendar bridge: Microsoft 365 in, one plain .ics out.

Why this lives on the server rather than in the phone app. Talking to Microsoft Graph means
an OAuth client, a refresh token that has to survive reboots, paging, and recurrence
expansion. None of that belongs on a Light Phone III, where there is no Play Services, no
usable OAuth library (see LightNews: MSAL and AppAuth both fail the "full browser" test on
LightOS) and no good place to keep a corporate refresh token. So BasilNet holds the
credential and does the work, and the phone does the one thing it is good at: fetch a URL.

The other reason is recurrence. LightNotebook's IcsParser deliberately does not expand
RRULE, so a weekly standup imported from a raw export would appear exactly once. Graph's
`calendarView` returns *instances*, already expanded, so what this bridge writes out is a
flat list of single events — every occurrence of the standup, no RRULE anywhere in the file.
That is the whole trick, and it is why going through Graph beats relaying a published feed.

Authorization is the device code flow: this process has no browser and no client secret, so
it prints a code, you type it into a real browser on a real computer once, and it keeps the
refresh token afterwards. A public client with `Calendars.Read` is the smallest thing that
works.

The feed itself is served at an unguessable path with no header auth, because the consumer
is a calendar client whose only vocabulary is GET. The URL *is* the credential — treat it
like a password, and rotate CALENDAR_SECRET if it leaks.
"""

from __future__ import annotations

import asyncio
import hashlib
import hmac
import json
import os
import time
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone
from pathlib import Path

import httpx
from fastapi import APIRouter, FastAPI, Header, HTTPException
from fastapi.responses import Response

ROOT = Path(os.environ.get("LIGHTSYNC_DIR", "/data"))
# Underscore-prefixed so app.py's health listing can tell it apart from a backed-up app.
DIR = ROOT / "_calendar"
TOKEN_FILE = DIR / "token.json"
ICS_FILE = DIR / "work.ics"
STATE_FILE = DIR / "state.json"

LIGHTSYNC_TOKEN = os.environ.get("LIGHTSYNC_TOKEN", "")
CLIENT_ID = os.environ.get("GRAPH_CLIENT_ID", "")
TENANT = os.environ.get("GRAPH_TENANT", "organizations")
SECRET = os.environ.get("CALENDAR_SECRET", "")
CALENDAR_NAME = os.environ.get("CALENDAR_NAME", "Work")
# Two weeks back is enough to keep "what did I do on Tuesday" answerable without making the
# file grow forever; six months forward covers anything anyone books in advance.
PAST_DAYS = int(os.environ.get("CALENDAR_PAST_DAYS", "14"))
AHEAD_DAYS = int(os.environ.get("CALENDAR_AHEAD_DAYS", "180"))
REFRESH_MIN = int(os.environ.get("CALENDAR_REFRESH_MIN", "20"))

SCOPE = "offline_access Calendars.Read"
GRAPH = "https://graph.microsoft.com/v1.0"

router = APIRouter(prefix="/cal", tags=["calendar"])

# The pending device-code login. In memory on purpose: an interrupted login should be
# restarted, not resumed from disk hours later against a code that has expired.
_pending: dict = {}


def _auth(base: str) -> str:
    return f"https://login.microsoftonline.com/{TENANT}/oauth2/v2.0/{base}"


def _check(token: str | None) -> None:
    if not LIGHTSYNC_TOKEN:
        raise HTTPException(500, "server has no LIGHTSYNC_TOKEN set")
    if token != LIGHTSYNC_TOKEN:
        raise HTTPException(401, "bad token")


def _write(path: Path, text: str) -> None:
    # Same rename dance as the blob store: a refresh interrupted halfway through must not
    # leave a truncated .ics that the phone would then import as "most of your meetings".
    DIR.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".part")
    # newline="" or Python's newline translation rewrites the CRLF that RFC 5545 requires.
    tmp.write_text(text, encoding="utf-8", newline="")
    tmp.replace(path)


def _state() -> dict:
    if STATE_FILE.exists():
        return json.loads(STATE_FILE.read_text())
    return {}


def _set_state(**fields) -> None:
    state = _state()
    state.update(fields)
    _write(STATE_FILE, json.dumps(state, indent=1))


def _refresh_token() -> str | None:
    if not TOKEN_FILE.exists():
        return None
    return json.loads(TOKEN_FILE.read_text()).get("refresh_token")


def _store_tokens(payload: dict) -> None:
    # Microsoft rotates the refresh token on most refreshes; keeping the old one after a
    # rotation is how a bridge silently dies three days later.
    kept = {
        "refresh_token": payload["refresh_token"],
        "at": int(time.time()),
    }
    _write(TOKEN_FILE, json.dumps(kept, indent=1))


# ---------------------------------------------------------------- Microsoft, the auth half


async def _access_token(client: httpx.AsyncClient) -> str:
    refresh = _refresh_token()
    if not refresh:
        raise RuntimeError("not linked — POST /cal/auth/start first")
    r = await client.post(
        _auth("token"),
        data={
            "client_id": CLIENT_ID,
            "grant_type": "refresh_token",
            "refresh_token": refresh,
            "scope": SCOPE,
        },
    )
    if r.status_code != 200:
        # invalid_grant here means the token was revoked, the password changed, or a
        # conditional-access policy pulled the rug. Say so rather than retrying forever.
        raise RuntimeError(f"refresh failed: {r.status_code} {r.text[:200]}")
    payload = r.json()
    if "refresh_token" in payload:
        _store_tokens(payload)
    return payload["access_token"]


async def _poll_device_code(device_code: str, interval: int, expires: int) -> None:
    """Waits for the human to finish typing the code into a browser."""
    deadline = time.time() + expires
    async with httpx.AsyncClient(timeout=30) as client:
        while time.time() < deadline:
            await asyncio.sleep(interval)
            r = await client.post(
                _auth("token"),
                data={
                    "client_id": CLIENT_ID,
                    "grant_type": "urn:ietf:params:oauth:grant-type:device_code",
                    "device_code": device_code,
                },
            )
            if r.status_code == 200:
                _store_tokens(r.json())
                _pending.clear()
                _pending["state"] = "linked"
                _set_state(linked_at=int(time.time()), last_error=None)
                await refresh_once()
                return
            error = r.json().get("error", "")
            if error in ("authorization_pending", "slow_down"):
                if error == "slow_down":
                    interval += 5
                continue
            _pending["state"] = "error"
            _pending["error"] = r.text[:300]
            return
    _pending["state"] = "expired"


# ------------------------------------------------------------- Microsoft, the calendar half


async def _fetch_events(client: httpx.AsyncClient, token: str) -> list[dict]:
    now = datetime.now(timezone.utc)
    url = (
        f"{GRAPH}/me/calendarView"
        f"?startDateTime={(now - timedelta(days=PAST_DAYS)).strftime('%Y-%m-%dT%H:%M:%SZ')}"
        f"&endDateTime={(now + timedelta(days=AHEAD_DAYS)).strftime('%Y-%m-%dT%H:%M:%SZ')}"
        "&$select=id,subject,start,end,isAllDay,isCancelled,showAs,location"
        "&$top=200"
    )
    events: list[dict] = []
    # Paging is not optional: 200 per page and half a year of a working calendar is several
    # pages, and stopping at the first one loses the far end of the window.
    while url and len(events) < 5000:
        r = await client.get(
            url,
            headers={
                "Authorization": f"Bearer {token}",
                # Ask for UTC explicitly. Graph's default is the mailbox timezone, which
                # would make the output depend on a setting nobody here can see.
                "Prefer": 'outlook.timezone="UTC"',
            },
        )
        if r.status_code != 200:
            raise RuntimeError(f"graph {r.status_code}: {r.text[:200]}")
        body = r.json()
        events.extend(body.get("value", []))
        url = body.get("@odata.nextLink")
    return events


# ------------------------------------------------------------------------ the .ics half


def _escape(text: str) -> str:
    return (
        text.replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\r\n", "\\n")
        .replace("\n", "\\n")
    )


def _fold(line: str) -> str:
    """RFC 5545 caps a line at 75 octets; longer ones continue with a leading space."""
    raw = line.encode("utf-8")
    if len(raw) <= 75:
        return line
    out, start = [], 0
    limit = 75
    while start < len(raw):
        chunk = raw[start : start + limit]
        # Never split a multi-byte character in half.
        while chunk and (chunk[-1] & 0xC0) == 0x80:
            chunk = chunk[:-1]
        out.append(chunk.decode("utf-8"))
        start += len(chunk)
        limit = 74  # subsequent lines spend one octet on the leading space
    return out[0] + "".join("\r\n " + c for c in out[1:])


def _uid(event: dict, when: str) -> str:
    """
    A stable, unique UID per occurrence.

    Graph's instance ids run well past 250 characters, and truncating them — which is what
    this did first — made *different* occurrences of the same series share a UID, because
    everything that distinguishes them sits at the end of the string. Observed live: 79
    events collapsed onto 53 UIDs. Hash the whole id instead, and fold the start time in, so
    two occurrences can never collide even if Microsoft hands back the same id twice.

    Stable across refreshes, because both halves are stable — which is what keeps a
    re-import idempotent on the phone.
    """
    digest = hashlib.sha1(str(event.get("id", "")).encode("utf-8")).hexdigest()[:32]
    return f"{digest}-{when}@lightsync"


def _stamp(value: str) -> tuple[str, bool]:
    """Graph's `2026-08-04T09:00:00.0000000` → `20260804T090000Z`, plus is-it-midnight."""
    cleaned = value.split(".")[0].rstrip("Z")
    dt = datetime.fromisoformat(cleaned)
    return dt.strftime("%Y%m%dT%H%M%SZ"), (dt.hour == 0 and dt.minute == 0)


def _to_ics(events: list[dict]) -> str:
    lines = [
        "BEGIN:VCALENDAR",
        "VERSION:2.0",
        "PRODID:-//LightSync//work calendar bridge//EN",
        "CALSCALE:GREGORIAN",
        "METHOD:PUBLISH",
        f"X-WR-CALNAME:{_escape(CALENDAR_NAME)}",
    ]
    written = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    for event in events:
        if event.get("isCancelled"):
            continue
        start_raw = (event.get("start") or {}).get("dateTime")
        end_raw = (event.get("end") or {}).get("dateTime")
        if not start_raw:
            continue
        subject = _escape((event.get("subject") or "Event").strip()[:200])
        when = (
            start_raw.split("T")[0].replace("-", "")
            if event.get("isAllDay")
            else _stamp(start_raw)[0]
        )
        lines.append("BEGIN:VEVENT")
        lines.append(f"UID:{_uid(event, when)}")
        lines.append(f"DTSTAMP:{written}")
        if event.get("isAllDay"):
            # All-day events come back as midnight-to-midnight; DATE values keep them off
            # the timed part of the grid entirely.
            start_day = start_raw.split("T")[0].replace("-", "")
            lines.append(f"DTSTART;VALUE=DATE:{start_day}")
            if end_raw:
                lines.append(f"DTEND;VALUE=DATE:{end_raw.split('T')[0].replace('-', '')}")
        else:
            lines.append(f"DTSTART:{_stamp(start_raw)[0]}")
            if end_raw:
                lines.append(f"DTEND:{_stamp(end_raw)[0]}")
        location = ((event.get("location") or {}).get("displayName") or "").strip()
        if location:
            lines.append(_fold(f"LOCATION:{_escape(location[:200])}"))
        lines.append(_fold(f"SUMMARY:{subject}"))
        if event.get("showAs"):
            lines.append(f"X-MICROSOFT-CDO-BUSYSTATUS:{event['showAs'].upper()}")
        lines.append("END:VEVENT")
    lines.append("END:VCALENDAR")
    return "\r\n".join(lines) + "\r\n"


async def refresh_once() -> dict:
    """One pass: refresh the token, read the window, rewrite the file."""
    async with httpx.AsyncClient(timeout=60) as client:
        token = await _access_token(client)
        events = await _fetch_events(client, token)
    ics = _to_ics(events)
    _write(ICS_FILE, ics)
    _set_state(
        last_sync=int(time.time()),
        events=ics.count("BEGIN:VEVENT"),
        last_error=None,
    )
    return {"events": ics.count("BEGIN:VEVENT"), "bytes": len(ics)}


# ---------------------------------------------------------------------------- the endpoints


@router.post("/auth/start")
async def auth_start(x_token: str = Header(default=None)):
    _check(x_token)
    if not CLIENT_ID:
        raise HTTPException(500, "GRAPH_CLIENT_ID is not set")
    async with httpx.AsyncClient(timeout=30) as client:
        r = await client.post(
            _auth("devicecode"),
            data={"client_id": CLIENT_ID, "scope": SCOPE},
        )
    if r.status_code != 200:
        raise HTTPException(502, f"device code request failed: {r.text[:300]}")
    payload = r.json()
    _pending.clear()
    _pending["state"] = "pending"
    asyncio.create_task(
        _poll_device_code(
            payload["device_code"],
            int(payload.get("interval", 5)),
            int(payload.get("expires_in", 900)),
        )
    )
    return {
        "go_to": payload["verification_uri"],
        "enter_code": payload["user_code"],
        "expires_in": payload["expires_in"],
    }


@router.get("/status")
async def status(x_token: str = Header(default=None)):
    _check(x_token)
    state = _state()
    return {
        "linked": TOKEN_FILE.exists(),
        "pending": _pending.get("state"),
        "pending_error": _pending.get("error"),
        "last_sync": state.get("last_sync"),
        "events": state.get("events"),
        "last_error": state.get("last_error"),
        "feed": f"/cal/{'*' * 8}/work.ics" if SECRET else "CALENDAR_SECRET is not set",
    }


@router.post("/refresh")
async def refresh_now(x_token: str = Header(default=None)):
    _check(x_token)
    try:
        return await refresh_once()
    except Exception as exc:  # surfaced, not swallowed: this is the debug handle
        _set_state(last_error=str(exc)[:300])
        raise HTTPException(502, str(exc)[:300])


@router.get("/{secret}/work.ics")
def feed(secret: str):
    if not SECRET:
        raise HTTPException(503, "CALENDAR_SECRET is not set")
    if not hmac.compare_digest(secret, SECRET):
        # Deliberately the same answer as a wrong path: nothing here confirms that a
        # calendar exists at all.
        raise HTTPException(404, "no such feed")
    if not ICS_FILE.exists():
        raise HTTPException(503, "nothing synced yet")
    # Served from the last good file rather than fetched on demand, so an outage at
    # Microsoft's end, or an expired refresh token, still hands the phone a calendar.
    #
    # Bytes, not text: `read_text` applies universal newline translation and quietly turned
    # every CRLF this file writes into a bare LF, which is not what RFC 5545 says a line
    # break is. LightNotebook's parser tolerates it; another calendar client need not.
    return Response(
        ICS_FILE.read_bytes(),
        media_type="text/calendar; charset=utf-8",
        headers={"Cache-Control": "no-cache"},
    )


# ------------------------------------------------------------------------- the refresh loop


async def _loop() -> None:
    while True:
        if TOKEN_FILE.exists():
            try:
                await refresh_once()
            except Exception as exc:
                # A failed pass leaves the previous .ics in place on purpose.
                _set_state(last_error=str(exc)[:300])
        await asyncio.sleep(REFRESH_MIN * 60)


@asynccontextmanager
async def lifespan(app: FastAPI):
    DIR.mkdir(parents=True, exist_ok=True)
    task = asyncio.create_task(_loop())
    try:
        yield
    finally:
        task.cancel()
