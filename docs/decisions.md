# Decisions

Each decision: date, what, why. Changed decisions are never deleted —
a new entry is added that says what changed and why.

## 2026-11-19
- **Bridge language: Python.** Rich libraries for Moodle, Google Calendar, scheduling; easy to read.
- **AI engine: Claude Code in headless mode (`claude -p`).** Runs on the user's subscription; can resume existing sessions (`--resume`).
- **Interface: own Android app.** No Telegram for now; can be added later if needed.
- **Android app: Kotlin + Jetpack Compose.** Native access to usage stats, Health Connect, background work and notifications. UI quality is a priority.
- **App languages: English and Turkish, selectable in settings.** AI replies follow the selected language.
- **Phone ↔ computer: Tailscale private network.** Bridge is never exposed to the public internet.
- **Memory lives outside this repo, local only.** Kept in its own folder with local git (no remote) for history and undo.
- **This repo contains no personal data.** Personal settings live in a git-ignored config outside the code.
- **Language of repo and memory files: English.**
- **Sleep data: manual entry first, fallback to screen-off / first-unlock times.**
- **Workflow: planning in Claude chat, building with Claude Code on the Ubuntu machine.**

## 2026-11-20
- **Owner notifications: local only for now.** `scripts/notify.sh` prints a visible line and shows a desktop notification when available; no third-party notification service. Phone push will be added behind the same script once the app exists.
- **Commits use the hosting platform's no-reply address**, set in this repository's local git config, so no personal e-mail address enters the public history.
- **Issue dependencies are machine-checked.** `scripts/sync-blocked.py` derives the `blocked` label from each issue's `Blocked by:` line, so labels cannot drift from the dependency text.
- **Bridge framework: FastAPI on uvicorn.** Small, well documented, supports streaming responses for later; cheap to replace because the bridge is a handful of routes.
- **Bridge Python version: 3.12 or newer, using the interpreter already on the machine.** `uv` is configured never to download an interpreter, so setup needs no extra installs; newer Pythons work unchanged.
- **Bridge auth is default-deny.** A middleware rejects every path except `/health` without the bearer token, including unknown paths; API docs/schema endpoints are disabled.
- **Bind addresses are validated.** Only localhost or a Tailscale-range address is accepted; wildcard and public addresses stop the bridge at start-up.
- **Headless runs set every flag explicitly and use the CLI's restricted mode.** `/ask` runs `claude -p` with `--permission-mode dontAsk --permission-prompts none --restricted --tools Read,Glob,Grep`, strict MCP config and no slash commands, so a changed CLI default or a personal settings file cannot widen what a phone prompt may do. Confirmed with a real run: reading outside the folder is denied.
- **The Claude child process gets an allow-listed environment.** Prevents a stray API key from silently switching billing and keeps bridge secrets away from the model's process.
- **`/ask` continues only its own sessions.** A `session_id` is accepted only if it belongs to the configured ask folder; other sessions on the computer are reachable only through the sessions endpoints with their own checks.
- **Streaming is implemented** as server-sent events on the same `/ask` endpoint (`"stream": true`).
- **Concurrency: at most two runs at a time by default, one per session**; extra requests are refused immediately (429/409) rather than queued, so the phone always gets a prompt answer.
