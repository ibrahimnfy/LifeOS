# LifeOS

LifeOS is a self-hosted personal assistant. Over time it should know what its
owner knows about their projects, courses and daily life, and act on it —
from a phone, using the owner's own computer as the brain.

```
Android app (Kotlin, Jetpack Compose, EN/TR)
        │  private network (Tailscale)
        ▼
Bridge server (Python) on the owner's Ubuntu machine
        │  runs Claude Code headless (`claude -p`, `--resume`)
        ▼
Memory folder (local only)  +  the owner's other project folders
```

Nothing is exposed to the public internet, and no personal data lives in
this repository: memory and settings stay on the owner's machine.

## What it will do

In rough order of delivery:

1. **Ask from the phone.** Send a prompt, have Claude Code run it on the
   computer, get the answer back.
2. **Sessions.** List existing Claude Code sessions on the computer and
   resume one from the phone.
3. **Long-term memory.** A context map (what lives where on disk) plus notes,
   tasks and daily logs, kept as plain files under local git.
4. **Calendar and tasks.**
5. **Moodle.** Courses, materials and deadlines from the university Moodle.
6. **Phone activity.** Screen time, app usage, sleep and wake times, charts.
7. **Later.** Weekly AI summaries, startup tracking, e-mail, voice notes,
   messaging.

## Status

Early development. Work is tracked in
[GitHub issues](../../issues), grouped by phase:

| Phase | Topic |
|---|---|
| 0 | Bootstrap |
| 1 | Research (`docs/research/`) |
| 2 | Bridge server (`bridge/`) |
| 3 | Memory |
| 4 | Calendar |
| 5 | Moodle |
| 6 | Android app (`app/`) |

## Repository layout

| Path | Contents |
|---|---|
| `CLAUDE.md` | Operating manual for the coding agent that builds this project |
| `docs/decisions.md` | Decision log (append-only) |
| `docs/research/` | Research notes |
| `config/env.example` | Template for personal settings (the real file lives in `~/.config/lifeos/`) |
| `scripts/notify.sh` | Notifies the owner on the computer |
| `bridge/` | Bridge server (see `docs/bridge.md`) |
| `scripts/init-config.sh` | Creates the private config file with a random bridge token |
| `scripts/install-service.sh` | Installs the bridge as a systemd user service |
| `scripts/sync-blocked.py` | Keeps `blocked` issue labels in sync with issue dependencies |

## Decisions

Technology and design choices, with their reasons, are recorded in
[`docs/decisions.md`](docs/decisions.md).

## Privacy

- This repository is public and contains no personal data.
- The memory folder is local only: it has no git remote and is never pushed.
- Secrets and personal settings live in `~/.config/lifeos/`, outside the
  repository; only `*.example` templates are committed.

## Licence

[MIT](LICENSE)
