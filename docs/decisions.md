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
- **In-use detection for session resume fails closed.** Before resuming, the bridge takes its own per-session lock and asks `claude agents --json` for active sessions; if the session is listed, or the check cannot be completed, the resume is refused. Forking the session instead of refusing was not chosen, because the phone and the computer would then hold diverging conversations.
- **Capable mode exists only with the OS sandbox; there is no rules-only fallback.** Rules on command text cannot confine a program, so they serve as the explanation layer only. See `docs/permission-policy.md`.
- **Capable mode is enabled by a marker file, not by a setting.** Only the guardrail check writes it, for one Claude Code version; an update switches the mode off until the check passes again.
- **Capable runs may write the run's folder and the memory folder; other allowed folders are read-only for them.** Narrower than "all allowed folders", and enough for working in one project while keeping notes.
- **Audit log before execution.** A gate program run by Claude Code before every tool call writes the audit record and blocks the call when it cannot; the bridge cross-checks the stream against the log.
- **Fixed limits on allowed folders are applied wherever the list is used**, not only when a path is added.

### Owner input recorded 2026-11-20 (connectivity)
- **The "Tailscale private network" decision is under review.** The computer is usually on a managed network whose firewall blocks the VPN (the node stays offline), the block will not be circumvented, and the system must work on that network. Candidate replacement: phone and bridge both make ordinary outbound HTTPS/WebSocket connections to a relay, with end-to-end authenticated encryption between phone and bridge so that the relay can neither read nor forge messages. Tunnelling products and anything designed to evade network filtering are excluded. Research (`docs/research/connectivity.md`) and a threat model (`docs/security.md`) come first; the relay choice is a decision for the owner. Bridge work that does not depend on the transport continues; the bridge keeps binding to localhost.

### Owner answers recorded 2026-11-20 (second round)
- **Permanent deletion in the capable mode (#50): warn and confirm, no snapshots.** When a capable run is about to perform an obvious deletion, the app shows a warning and the run proceeds only after the owner confirms. Until the app can show that warning, such commands are refused with an explanation. Every command is in the audit log. Backups of a project are made only when a prompt asks for one. Known and accepted limit: overwrites and indirect deletions (a script, a tool that removes files as a side effect) are not recognised. The memory folder stays under its own local git as the safety net.
- **Semantic search over memory (#44): none for now.** Plain files and text search.
- **Automatic sleep detection (#43): decided later.** For now: manual entry with the screen-off / first-unlock fallback. Addition: when last night's sleep has not been entered, the app posts one silent notification early in the morning (default 05:00, configurable) asking for it. It is scheduled on the phone; no polling and no always-running service.
- **Agent SDK (#42): not adopted.** Runs keep using `claude -p`.
- **Billing of headless runs (#41): the subscription login stays.** No API key.
- **Remote Control as an interim phone interface (#40): not wanted.** Phone access starts with the app.
- **Old commit copy on the hosting platform (#54): left as it is.** No support request.

### Connectivity: final direction (2026-11-20)
- **The VPN transport is dropped, not kept as a fallback.** This replaces the 2026-11-19 entry "Phone ↔ computer: Tailscale private network" and the entry "Bind addresses are validated … or a Tailscale-range address". There is one transport: the relay with the end-to-end layer of `docs/security.md`. The bridge binds to the loopback interface only; any other bind address stops it at start-up.
- **The relay runs on Google Cloud**, in the owner's existing account. Candidates: a database mailbox in a Firebase project, or the project's own relay on a free-tier VM. The choice between them is the owner's (#57).
- **Push notifications use Firebase Cloud Messaging** and must work while the app is closed. A push never carries readable content: it is either encrypted with the paired key or a bare wake-up signal after which the app fetches and decrypts. This replaces "no third-party notification service" from the first 2026-11-20 entry for phone push only; no other notification service is added.
- **All earlier security requirements stay in force** (audited library, QR pairing, replay protection, hostile relay, device list with revoke, app lock, written threat model).

### App and assistant behaviour (owner, 2026-11-20)
- **Visual direction: none of A, B, C; a new direction D.** Layout conventions of mainstream photo-sharing social apps: pure white / pure black backgrounds, neutral greys, almost no colour, a slim icon-only bottom bar, thin line icons, generous spacing, full-width rounded buttons. Structure and feel only: own icons and buttons, no third-party logos, assets, gradients or product names anywhere in the repository. Light and dark. The owner refines from the mockups; the review issue stays open.
- **Today screen: as simple as possible.** No events. An advice card on top (what to focus on today and why), then the three most important tasks chosen by the assistant, then the tasks that belong to today. Things are added only when needed.
- **Tasks screen: prioritised by the assistant.** The most important tasks on top, the rest behind "show all".
- **Coach behaviour.** The assistant proactively asks questions in chat (why a task is waiting, what is hard or easy about it), stores what it learns in the memory and uses it for prioritisation and advice. It nudges about work with notifications, within limits: a small daily cap, quiet hours at night, frequency adjustable in settings.
- **Coach defaults (agent's choice, cheap to change in settings):** at most 3 nudges per day; quiet hours 22:00–08:00; at most one open coach question at a time; frequency setting off / low (1 per day) / normal (3 per day).
- **Sessions.** Sessions started in a terminal on the computer are resumable from the phone (allowed folders only, refused while open on the computer; already built). The owner creates groups and assigns sessions to them, renames sessions and pins frequently used ones; ungrouped sessions are grouped by folder automatically. This metadata lives in the bridge's private config folder; Claude Code's own session files are never modified.
- **Per-session reminders:** "remind me about this session at <time>" with an optional note, one-off or repeating; the notification opens that session.
- **Reply notifications:** when a run finishes, the phone gets a push notification, so the app need not stay open during long tasks; can be switched off per session.
- **Android application id: `dev.lifeos.app`** (agent's choice; neutral, needed to register the app for push). Changing it later means re-registering the app with the push service.

### Owner answers recorded 2026-11-20 (third round)
- **Memory design (#17): approved**, with `calendar/` added to the layout now. Consolidation trigger: propose, then approve (may become automatic later, as a new decision). The assistant may write inferred facts on its own, always tagged as inferred.
- **Relay form (#57): the Realtime Database mailbox** in the owner's Firebase project. It is tested on the managed network before anything is built on it.
- **Pairing (#58): the simple design**, the whole key in the QR code. Pairing is a one-time step per phone; afterwards everything reconnects by itself. Pairing again only after a new phone, a reinstall or a revocation.
- **Coach schedule (#62): one scheduled run each morning** prepares a small batch of coach messages. One is sent as the daily notification; the others are shown one at a time at the top of the app on each opening, rotating with some randomness, with no model run per opening. Manual refresh may start a new run. This replaces the agent's earlier default of three nudges per day: the default is one notification per day; the setting can raise it to three, taken from the same batch.
- **Coach defaults (agent's choice, cheap to change):** morning run at 06:30 local time; five to eight messages per batch; at most one refresh run per ten minutes.
- **Sandbox prerequisites (#49) and bridge service (#14): done by the owner**, verified by the agent.
- **Capable mode: switched on by the guardrail check** for Claude Code 2.1.328, for runs in the memory folder. All probes passed in the terminal and in the service environment. Capable runs in other allowed folders stay refused until the check covers such a folder.
- **Moodle (#26): the password token endpoint does not work** on the university Moodle (single sign-on). The owner is not asked to retry it. Other ways to obtain a token are investigated without the owner's credentials.

### Owner answers recorded 2026-11-20 (fourth round)
- **Firebase project (#61): created by the owner** (Realtime Database in locked mode, anonymous sign-in, Android app registered); the two files are in the private config folder and were checked for structure without displaying them.
- **Moodle (#26): the official mobile app can sign in through the institution's login, so the browser-based token flow is used.** The owner signs in on the institution's own page in a normal browser and pastes one line into a local helper; the bridge never sees the password. The token is stored only in the private config folder.
- **Visual direction D (#30, still open): structure accepted, look to be softened.** Not pure black and white only: a slightly soft white, a deep blue-black or charcoal for dark mode, soft grey card surfaces, subtle dividers. Colour where it matters: a vivid blue for primary buttons and links, red for notification badges and counts, and a warm multi-colour gradient accent (pink / orange / purple range, own tones) as a ring or highlight on featured elements such as the coach card, pinned sessions and the active state. Three variants D1, D2, D3 differing in base and accent tones are mocked up for side-by-side comparison, light and dark. The rule against third-party logos, assets, gradients and product names stays.
- **Bug fixed: test runs sent real desktop notifications** ("session reminder"). No real reminder ever existed; the test suite reached the real notification script. Notifications are now switched off for every test.
- **Key derivation in the bridge uses keyed BLAKE2b with salt and personalisation**, with exactly the inputs that define libsodium's `crypto_kdf`, because the Python binding does not expose `crypto_kdf`. It is the same documented construction; the phone uses `crypto_kdf` directly, and an interoperability test belongs to the app work.
- **Libraries added to the bridge:** the official Google auth library (service-account tokens), PyNaCl (libsodium), a pure-Python QR code generator for the terminal. HTTPS requests use Python's standard library so that the system trust store applies.
