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
