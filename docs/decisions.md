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
- **Session listing reads the transcripts directly, scoped to allowed folders.** The official SDK offers session-listing functions, but its package is a download of more than 150 MB that bundles a second copy of the CLI, and it enumerates every project. The bridge instead uses a small tolerant reader that opens only the project folders of allowed roots; it can be swapped for the SDK later behind the same endpoint.

### Owner answers recorded 2026-11-20
- **Commit address clean-up (#2): done once, as an explicit exception to the no-history-rewrite rule.** The author and committer address of one early commit was replaced by the no-reply address; trees, messages, names and dates of all commits are unchanged. Side effect: commit ids changed, and the three earliest commits lost the hosting platform's signature. History rewriting stays forbidden otherwise.
- **Permission policy for phone prompts (#5): full capability with guardrails.** `/ask` stays read-only by default; a capable mode is chosen per request. In the capable mode shell commands are allowed, but: work is confined to the memory folder and the allowed paths, with OS-level sandboxing where Claude Code supports it, otherwise the strictest permission configuration available; the private config folder, SSH keys, credential stores and browser profiles are never readable or writable; `sudo` and system-setting changes are always denied; permanent deletion, pushes and force operations, package installs, network actions that send data out and any message or e-mail sending need the owner's explicit approval and are refused with a clear explanation until the app has an approval screen; every command run for a phone prompt is written to a local audit log outside the repository. A guardrail that cannot be enforced technically becomes a human issue; it is never weakened. Design goes to `docs/` first, then separate agent issues.
- **Allowed folders (#18): start with an empty list.** Paths are added later, on demand, through a bridge endpoint. Never addable: the home directory itself, system directories, hidden config or credential folders, anything outside the home directory. Every change sends a notification and is logged. The context map is built incrementally as paths are added.
- **Calendar backend (#23): local-only calendar in the memory folder.** No reminders are needed until the app exists.
- **App concept (#30, still open):** bottom bar Today · Chat · Tasks · More approved; chat continues the same conversation until "New chat" is pressed; two more screens under More: Notes (browse, search, edit captured notes) and Projects (per project: status, recent activity, open tasks, "Ask about this project"). The visual direction will be chosen from static HTML mockups of directions A, B and C.
