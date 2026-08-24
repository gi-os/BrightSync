#!/usr/bin/env python3
"""
A setup code for a phone that only wants Immich, from a machine that runs nothing.

The `/enroll` page needs the LightSync container, which is the half you skipped if photographs
are all you are after. This is the same code, printed by a script: it signs in to Immich once,
asks it for a key scoped to uploading and one album, and draws the QR in the terminal.

    python3 enroll_qr.py --immich http://192.168.68.59:2283 --email you@example.com
    python3 enroll_qr.py --immich http://192.168.68.59:2283 --key <existing key> --svg code.svg

Only `segno` is needed (`pip install segno`); everything else is the standard library. The
password is used for one request and is never written anywhere — the key it returns is what the
phone stores, and revoking it in Immich is enough to cut the phone off.
"""

import argparse
import getpass
import json
import sys
import urllib.error
import urllib.request
from urllib.parse import quote

# What the phone's key is allowed to do. Matches Immich.PERMISSIONS in the app: add photographs,
# read them back, keep one album. Not `all`, and nothing that deletes — a phone left in a taxi
# should not be able to empty the library it was backing up to.
PERMISSIONS = [
    "asset.upload",
    "asset.read",
    "asset.view",
    "asset.download",
    "album.create",
    "album.read",
    "albumAsset.create",
]


def normalize(address: str) -> str:
    """The same trims the app makes: a bare host is http, and a helpful /api is not one."""
    address = address.strip().rstrip("/")
    if not address.startswith(("http://", "https://")):
        address = "http://" + address
    return address.removesuffix("/api")


def call(url: str, body=None, headers=None):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(url, data=data, headers=headers or {})
    if data:
        request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return json.loads(response.read().decode())
    except urllib.error.HTTPError as error:
        detail = error.read().decode()[:200]
        raise SystemExit(f"{url} -> {error.code} {detail}")
    except urllib.error.URLError as error:
        raise SystemExit(f"{url} is not answering: {error.reason}")


def mint(immich: str, email: str, password: str, label: str) -> str:
    token = call(f"{immich}/api/auth/login", {"email": email, "password": password})["accessToken"]
    made = call(
        f"{immich}/api/api-keys",
        {"name": label, "permissions": PERMISSIONS},
        {"Authorization": f"Bearer {token}"},
    )
    return made["secret"]


def payload(immich: str, key: str, album: str) -> str:
    fields = [("v", "1"), ("i", immich), ("k", key)]
    if album:
        fields.append(("a", album))
    return "brightsync://setup?" + "&".join(f"{k}={quote(v, safe='')}" for k, v in fields)


def main() -> None:
    parser = argparse.ArgumentParser(description="Print a BrightSync setup code for Immich.")
    parser.add_argument("--immich", required=True, help="e.g. http://192.168.68.59:2283")
    parser.add_argument("--email", help="Immich account to sign in as, if you have no key yet")
    parser.add_argument("--password", help="asked for on the terminal if omitted")
    parser.add_argument("--key", help="an existing API key, instead of signing in")
    parser.add_argument("--album", default="Light Phone III", help='"" for no album')
    parser.add_argument("--label", default="BrightSync", help="what the key is called in Immich")
    parser.add_argument("--svg", help="also write the code to this file, for a bigger screen")
    args = parser.parse_args()

    immich = normalize(args.immich)
    if args.key:
        key = args.key
    elif args.email:
        key = mint(immich, args.email, args.password or getpass.getpass("Immich password: "), args.label)
        print(f"Made an API key called {args.label!r} in Immich. Revoke it there to undo this.\n")
    else:
        raise SystemExit("give either --key or --email")

    import segno  # imported late so --help works without it installed

    code = segno.make(payload(immich, key, args.album), error="m")
    # Two modules of quiet zone and the small terminal renderer: a code that fits in a window
    # someone actually has open is a code that gets scanned.
    code.terminal(out=sys.stdout, border=2, compact=True)
    print(f"\nScan this in BrightSync: Setup → Scan a code.\nPhotographs → {immich}")
    if args.album:
        print(f"Album → {args.album}")
    if args.svg:
        code.save(args.svg, scale=6)
        print(f"Written to {args.svg}")


if __name__ == "__main__":
    main()
