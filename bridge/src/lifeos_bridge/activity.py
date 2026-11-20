"""Phone activity in the memory: screen time, app usage, unlocks and sleep.

The phone uploads one summary per day. Each day is one line in a monthly
file under ``activity/`` in the memory folder, replaced when the same day is
uploaded again, committed there like every other write. The data never
leaves the computer.

    activity/2027-01.md
    - 2027-01-15 | screen 3h12m | unlocks 41 | apps: Browser 58m; Messages 47m
    - 2027-01-15 | sleep 23:40-07:05 (7h25m) | estimated
"""

from __future__ import annotations

import re
from datetime import date, datetime, timedelta
from pathlib import Path

from . import memory
from .claude import ClaudeError

MAX_APPS = 20
MAX_LABEL = 40
LINE = re.compile(r"^- (\d{4}-\d{2}-\d{2}) \| (screen|sleep) (.*)$")
SLEEP = re.compile(r"^(\d{2}:\d{2})-(\d{2}:\d{2}) \((\d+)h(\d{2})m\)(?: \| (estimated))?$")
SCREEN = re.compile(r"^(\d+)h(\d{2})m \| unlocks (\d+)(?: \| apps: (.*))?$")


def bad(message: str) -> ClaudeError:
    return ClaudeError("bad_request", 400, message)


def _minutes(value: object, name: str, limit: int = 24 * 60) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or not 0 <= value <= limit:
        raise bad(f"`{name}` must be a number of minutes between 0 and {limit}")
    return value


def _hm(minutes: int) -> str:
    return f"{minutes // 60}h{minutes % 60:02d}m"


def _label(value: object) -> str:
    text = re.sub(r"[|;()]", " ", str(value or ""))
    return " ".join(text.split())[:MAX_LABEL] or "app"


def _upsert(memory_dir: Path, day: date, kind: str, rest: str, message: str) -> None:
    relative = f"activity/{day:%Y-%m}.md"
    with memory.LOCK:
        memory.check_repo(memory_dir)
        path = memory_dir / relative
        lines = memory.read_text(path).splitlines() or [f"# Phone activity {day:%Y-%m}", ""]
        prefix = f"- {day.isoformat()} | {kind} "
        lines = [line for line in lines if not line.startswith(prefix)]
        lines.append(prefix + rest)
        head = [line for line in lines if not line.startswith("- ")]
        body = sorted(line for line in lines if line.startswith("- "))
        memory.write_text(path, "\n".join(head + body) + "\n")
        memory.commit(memory_dir, message, [relative])


def record_usage(memory_dir: Path, data: dict) -> dict:
    """Body: ``{"date", "screen_minutes", "unlocks", "apps": [{"label", "minutes"}]}``."""
    day = memory.read_date(data.get("date"), "date")
    screen = _minutes(data.get("screen_minutes"), "screen_minutes")
    unlocks = data.get("unlocks", 0)
    if isinstance(unlocks, bool) or not isinstance(unlocks, int) or not 0 <= unlocks <= 100_000:
        raise bad("`unlocks` must be a count")
    apps = data.get("apps") or []
    if not isinstance(apps, list) or len(apps) > 200:
        raise bad("`apps` must be a list")
    parts = []
    for item in sorted(
        (a for a in apps if isinstance(a, dict)), key=lambda a: -int(a.get("minutes") or 0)
    )[:MAX_APPS]:
        parts.append(f"{_label(item.get('label'))} {_minutes(item.get('minutes'), 'minutes')}m")
    rest = f"{_hm(screen)} | unlocks {unlocks}" + (f" | apps: {'; '.join(parts)}" if parts else "")
    _upsert(memory_dir, day, "screen", rest, "Record phone screen time")
    return {"saved": True, "date": day.isoformat()}


def record_sleep(memory_dir: Path, data: dict) -> dict:
    """Body: ``{"date" (the morning), "bed": "HH:MM", "wake": "HH:MM", "estimated": bool}``."""
    day = memory.read_date(data.get("date"), "date")
    bed, wake = data.get("bed"), data.get("wake")
    for name, value in (("bed", bed), ("wake", wake)):
        if not isinstance(value, str) or not re.fullmatch(r"([01]\d|2[0-3]):[0-5]\d", value):
            raise bad(f"`{name}` must be a time like 23:40")
    start = datetime.combine(day, datetime.strptime(bed, "%H:%M").time())
    end = datetime.combine(day, datetime.strptime(wake, "%H:%M").time())
    if start >= end:  # went to bed the evening before
        start -= timedelta(days=1)
    minutes = int((end - start).total_seconds() // 60)
    rest = f"{bed}-{wake} ({_hm(minutes)})" + (" | estimated" if data.get("estimated") else "")
    _upsert(memory_dir, day, "sleep", rest, "Record sleep")
    return {"saved": True, "date": day.isoformat(), "minutes": minutes}


def read(memory_dir: Path, first: date, last: date) -> list[dict]:
    """The recorded days from ``first`` to ``last``, oldest first."""
    days: dict[str, dict] = {}
    month = first.replace(day=1)
    while month <= last:
        for line in memory.read_text(memory_dir / f"activity/{month:%Y-%m}.md").splitlines():
            found = LINE.match(line)
            if not found or not first.isoformat() <= found.group(1) <= last.isoformat():
                continue
            entry = days.setdefault(found.group(1), {"date": found.group(1)})
            if found.group(2) == "sleep" and (m := SLEEP.match(found.group(3))):
                entry["sleep"] = {"bed": m.group(1), "wake": m.group(2),
                                  "minutes": int(m.group(3)) * 60 + int(m.group(4)),
                                  "estimated": bool(m.group(5))}  # fmt: skip
            elif found.group(2) == "screen" and (m := SCREEN.match(found.group(3))):
                apps = []
                for part in (m.group(4) or "").split("; "):
                    label, _, minutes = part.rpartition(" ")
                    if label and minutes.endswith("m") and minutes[:-1].isdigit():
                        apps.append({"label": label, "minutes": int(minutes[:-1])})
                entry["screen"] = {"minutes": int(m.group(1)) * 60 + int(m.group(2)),
                                   "unlocks": int(m.group(3)), "apps": apps}  # fmt: skip
        month = (month.replace(day=28) + timedelta(days=4)).replace(day=1)
    return [days[key] for key in sorted(days)]
