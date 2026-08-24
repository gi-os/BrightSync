"""
The setup payload, which is the one part of enrolment that fails silently.

A wrong URI is not a crash: the phone scans, accepts, and is configured to talk to nothing. So
what is worth testing here is what ends up in the code — that a mode leaves out what it should,
and that values which would break a query string survive the round trip.
"""

import os
import unittest
from urllib.parse import parse_qs, urlparse

os.environ.setdefault("LIGHTSYNC_TOKEN", "tok/with+specials")
os.environ.setdefault("LIGHTSYNC_PHONE_URL", "http://192.168.68.59:8099")
os.environ.setdefault("IMMICH_URL", "http://192.168.68.59:2283")
os.environ.setdefault("IMMICH_KEY", "key with spaces & an ampersand")
os.environ.setdefault("IMMICH_ALBUM", "Light Phone III")

import enroll  # noqa: E402  (imported after the environment it reads at module scope)


def fields(uri: str) -> dict:
    parsed = urlparse(uri)
    assert parsed.scheme == "brightsync"
    return {k: v[0] for k, v in parse_qs(parsed.query).items()}


class PayloadTest(unittest.TestCase):
    def test_everything_carries_both_halves(self):
        f = fields(enroll.payload("everything"))
        self.assertEqual(f["v"], "1")
        self.assertEqual(f["s"], "http://192.168.68.59:8099")
        self.assertEqual(f["t"], "tok/with+specials")
        self.assertEqual(f["i"], "http://192.168.68.59:2283")
        self.assertEqual(f["a"], "Light Phone III")

    def test_specials_survive_the_query_string(self):
        # A `+` read back as a space, or an `&` splitting a value in two, produces a phone that
        # is configured with a credential one character short and an error that says 401.
        self.assertEqual(fields(enroll.payload("everything"))["k"], "key with spaces & an ampersand")

    def test_photos_only_leaves_the_blob_store_out(self):
        f = fields(enroll.payload("photos"))
        self.assertNotIn("s", f)
        self.assertNotIn("t", f)
        self.assertNotIn("p", f)
        self.assertIn("i", f)

    def test_backups_only_leaves_immich_out(self):
        f = fields(enroll.payload("backups"))
        self.assertIn("t", f)
        self.assertNotIn("i", f)

    def test_passphrase_is_absent_unless_asked_for(self):
        self.assertNotIn("p", fields(enroll.payload("everything")))
        enroll.PASSPHRASE = "correct horse"
        try:
            self.assertEqual(fields(enroll.payload("everything"))["p"], "correct horse")
        finally:
            enroll.PASSPHRASE = ""


if __name__ == "__main__":
    unittest.main()
