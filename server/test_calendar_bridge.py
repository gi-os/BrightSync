"""
Checks on the calendar bridge's pure half — the part that turns Graph JSON into iCalendar.

Run it with `python3 test_calendar_bridge.py` (no pytest needed, so it works inside the
container as well as on a laptop). The network half is deliberately untested: what could go
wrong there is Microsoft's answer, not this code's arithmetic.
"""

import os

os.environ.setdefault("LIGHTSYNC_DIR", "/tmp/lightsync-test")

import calendar_bridge as cb  # noqa: E402


def event(**over):
    base = {
        "id": "AAA",
        "subject": "Standup",
        "isAllDay": False,
        "start": {"dateTime": "2026-08-04T13:00:00.0000000", "timeZone": "UTC"},
        "end": {"dateTime": "2026-08-04T13:15:00.0000000", "timeZone": "UTC"},
    }
    base.update(over)
    return base


def lines(events):
    return cb._to_ics(events).split("\r\n")


def test_timed_event_is_utc_stamped():
    out = lines([event()])
    assert "DTSTART:20260804T130000Z" in out
    assert "DTEND:20260804T131500Z" in out


def test_all_day_event_is_a_date_not_a_midnight():
    # A midnight-to-midnight instant would land on the grid as an event "at 00:00", and in
    # another timezone on the day before.
    out = lines([event(isAllDay=True, end={"dateTime": "2026-08-11T00:00:00.0000000"},
                       start={"dateTime": "2026-08-10T00:00:00.0000000"})])
    assert "DTSTART;VALUE=DATE:20260810" in out


def test_cancelled_events_are_dropped():
    assert cb._to_ics([event(isCancelled=True)]).count("BEGIN:VEVENT") == 0


def test_text_is_escaped():
    out = lines([event(subject="Review; notes, part 2")])
    assert "SUMMARY:Review\\; notes\\, part 2" in out


def test_every_line_fits_the_75_octet_limit():
    # Folding is the one part of iCalendar that silently corrupts titles when skipped, and
    # a UTF-8 title is where a naive character-count fold goes wrong.
    long_title = "Café résumé üñî " + "long " * 40
    for line in lines([event(subject=long_title)]):
        assert len(line.encode("utf-8")) <= 75, line


def test_folding_survives_a_round_trip():
    long_title = "Quarterly business review with the packaging team and everyone else"
    out = cb._to_ics([event(subject=long_title)])
    unfolded = out.replace("\r\n ", "")
    assert f"SUMMARY:{long_title}" in unfolded


def test_occurrences_of_one_series_get_different_uids():
    # The bug this exists for: Graph instance ids run past 250 characters and differ only at
    # the end, so truncating them made 79 real events share 53 UIDs.
    long_id = "AAMkADRmYThkZjUxLWExYzItNDg5Mi1hMzIzLWZk" + "Q" * 300
    a = event(id=long_id + "AAA", start={"dateTime": "2026-07-22T11:00:00.0000000"})
    b = event(id=long_id + "BBB", start={"dateTime": "2026-07-22T14:00:00.0000000"})
    uids = [l for l in lines([a, b]) if l.startswith("UID:")]
    assert len(set(uids)) == 2, uids
    # And short enough that no UID ever needs folding.
    assert all(len(u.encode("utf-8")) <= 75 for u in uids)


def test_the_same_event_keeps_its_uid_across_refreshes():
    # Re-imports replace a source's events wholesale on the phone, but a UID that changed
    # every pass would make any other client treat each refresh as a new calendar.
    first = [l for l in lines([event()]) if l.startswith("UID:")]
    second = [l for l in lines([event()]) if l.startswith("UID:")]
    assert first == second


def test_line_breaks_are_crlf():
    # RFC 5545 says CRLF. Python's text-mode read turned these into bare LF once already.
    out = cb._to_ics([event()])
    assert "\r\n" in out
    assert "\n" not in out.replace("\r\n", "")


def test_no_rrule_is_ever_emitted():
    # calendarView hands back expanded instances; if an RRULE ever appeared here it would
    # mean the wrong endpoint was called, and the phone's parser would import one occurrence.
    assert "RRULE" not in cb._to_ics([event(), event(id="BBB")])


if __name__ == "__main__":
    passed = 0
    for name, fn in sorted(globals().items()):
        if name.startswith("test_") and callable(fn):
            fn()
            passed += 1
            print(f"ok   {name}")
    print(f"\n{passed} passed")
