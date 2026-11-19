# Prior-art survey

Survey date: 2026-11-20.

## Scope and method

This document surveys existing work relevant to LifeOS in five areas:

1. Gateways that drive Claude Code (or similar agents) from a phone.
2. Long-term memory designs for assistants.
3. Moodle API clients.
4. Android usage-stats, screen-time and sleep tracking.
5. Session-resume UIs for Claude Code.

Method:

- Sources are official documentation, repository READMEs and source files,
  and a few first-hand write-ups. Vendor landing pages, listicles and
  benchmark claims were ignored.
- Every GitHub repository cited was checked on the survey date with
  `gh api repos/<owner>/<repo>` for existence, licence (SPDX id), last push
  and archived state. "Licence" lines below report that API result.
- "How" claims come from the files named under **Source**. Where a claim
  rests only on a README, or could not be confirmed from a primary source,
  it is marked **not verified**.
- Licence note for LifeOS (MIT): from GPL and AGPL projects we take ideas and
  protocol facts only, never code.

Entry format: **What it does**, **How**, **What we can learn**,
**What to avoid**, **Licence**, **Source**.

---

## 1. Gateways that drive Claude Code from a phone

### 1.1 Claude Code Remote Control (official)

- **What it does:** Drives a Claude Code session that runs on the owner's own
  machine from the Claude web UI or the Claude mobile app. Files, MCP servers
  and tools stay on the machine.
- **How:** Outbound relay, no local listener. The docs state the local
  session "makes outbound HTTPS requests only and never opens inbound ports
  on your machine"; it registers with the Anthropic API and polls for work.
  `claude remote-control` is a server mode (`--spawn same-dir|worktree|session`,
  `--capacity`) in which the phone can start new sessions;
  `claude --remote-control` or `/remote-control` attaches one interactive
  session. Requires a claude.ai login on a paid subscription plan; API keys
  and third-party cloud providers are not supported.
- **What we can learn:** It already covers LifeOS capabilities 1 and 2 (send a
  prompt, continue a session) with zero code, including push notifications.
  It is the baseline our bridge and app must justify themselves against, and
  a possible stopgap while the app does not exist yet.
- **What to avoid:** Assuming it can replace the project. Documented limits:
  while connected, the transcript "is stored on Anthropic servers"; the local
  process must keep running (the docs suggest tmux/screen); server mode exits
  after about 10 minutes without network; the workspace-trust prompt needs a
  terminal, so a non-TTY start fails; `/resume` is local-only. It cannot host
  our own screens (tasks, calendar, courses, charts) or endpoints.
- **Licence:** proprietary product feature (no repository).
- **Source:** <https://code.claude.com/docs/en/remote-control.md>

### 1.2 Other official surfaces: cloud sessions, Dispatch, Channels

- **What it does:**
  - *Cloud sessions* ("Claude Code on the web", also in the mobile app's Code
    tab) run in an Anthropic-managed VM cloned from GitHub.
  - *Dispatch* sends a task from the phone to the Claude Desktop app, which
    spawns a Code session on the computer.
  - *Channels* (research preview) are MCP servers that push events into a
    running session; bundled plugins cover Telegram, Discord and iMessage.
- **How:** Cloud sessions never see the home machine. Channels start with
  `claude --channels plugin:<name>@claude-plugins-official`, use a sender
  allowlist bootstrapped by a pairing code, and can optionally relay
  permission prompts to the chat.
- **What we can learn:** The docs say there is no separate Claude Code mobile
  app; all of these live in one Code tab. A custom channel could later push
  bridge events (a new deadline, a finished sync) into a long-lived session.
  Pairing codes are a simple way to bind a phone to a server.
- **What to avoid:** Cloud sessions cannot read the local memory folder;
  moving it there would break the local-only rule. Channels deliver events
  only while a session is open, and the docs warn the "flag syntax and
  protocol contract may change". With permission relay, anyone who can reply
  in the channel can approve tool use. Whether Dispatch works on Linux is
  **not verified**; the custom-channel API is **not verified** (reference
  page not read).
- **Licence:** product features; the plugin repository
  `anthropics/claude-plugins-official` is Apache-2.0.
- **Source:** <https://code.claude.com/docs/en/claude-code-on-the-web.md>,
  <https://code.claude.com/docs/en/mobile.md>,
  <https://code.claude.com/docs/en/desktop.md>,
  <https://code.claude.com/docs/en/channels.md>,
  <https://github.com/anthropics/claude-plugins-official>

### 1.3 Headless `claude -p` (official; the engine LifeOS already chose)

- **What it does:** Runs one non-interactive turn and prints the result.
- **How:** Flags documented on the headless page include `-p`, `--continue`,
  `--resume <id>`, `--output-format text|json|stream-json`, `--allowedTools`,
  `--permission-mode`, `--append-system-prompt` and `--max-budget-usd`. JSON
  output carries `session_id` and `total_cost_usd`. Streaming uses
  `stream-json` with `--verbose --include-partial-messages`. SIGINT ends the
  turn; SIGTERM exits with code 143 and leaves the turn unfinished.
- **What we can learn:** Three documented behaviours that directly shape the
  bridge:
  1. "`--bare` is the recommended mode for scripted and SDK calls, and will
     become the default for `-p` in a future release", and bare mode "doesn't
     use your subscription login". It also skips `CLAUDE.md` and auto memory.
  2. Recent versions start `-p` runs in a manual permission mode, so tool
     calls that need approval are denied unless explicitly allowed.
  3. A `-p` session shows no workspace-trust dialog and does run project
     hooks and `.mcp.json` servers of the directory it starts in.
- **What to avoid:** Relying on any default. The bridge should pass permission
  mode, allowed tools, model and context explicitly, and have a test that
  fails loudly when a CLI upgrade changes behaviour. Never start a headless
  run in an untrusted directory.
- **Licence:** proprietary CLI.
- **Source:** <https://code.claude.com/docs/en/headless.md>,
  <https://code.claude.com/docs/en/cli-reference.md>. Details belong in
  `docs/research/claude-headless.md`.

### 1.4 Happy (`slopus/happy`)

- **What it does:** Phone, web and desktop clients for Claude Code and other
  coding agents.
- **How:** `happy claude` wraps Claude Code and shows a QR code to pair a
  phone; `happy daemon start` lets the phone spawn sessions with no terminal
  open. Two modes: for sessions started at the desk, a SessionStart hook and
  a scanner tail the session's JSONL transcript; for phone-started sessions,
  the SDK stream is converted and sent through an ordered queue. Transport is
  HTTP plus Socket.IO to a relay server with client-side encryption; the
  relay can be self-hosted. The monorepo contains a native Kotlin / Jetpack
  Compose Android package.
- **What we can learn:** The two-mode design (tail the transcript vs. own the
  process) is exactly the distinction between "resume a desk session" and
  "ask from the phone". A daemon that registers the machine, QR pairing, and
  a real Compose client for a Claude Code chat UI to study.
- **What to avoid:** Relay server, accounts and push infrastructure are
  unnecessary on a private Tailscale network. One backend's notes show
  permission modes silently degrading to skip-all-permissions; a bridge must
  fail closed instead. Encryption primitives are **not verified**.
- **Licence:** MIT. Active (pushed 2026-11-19).
- **Source:** <https://github.com/slopus/happy> (README,
  `packages/happy-cli/README.md`, `docs/cli-architecture.md`,
  `docs/session-protocol-claude.md`)

### 1.5 CloudCLI (`siteboon/claudecodeui`)

- **What it does:** Responsive web UI for Claude Code and other agent CLIs,
  usable from a phone browser.
- **How:** Node/Express with WebSockets on the Claude Agent SDK, a PTY for a
  shell tab, SQLite for state, JWT login. It discovers sessions by scanning
  the per-project JSONL transcripts under the Claude Code config directory
  and upserting them into SQLite; titles come from the CLI's `history.jsonl`
  (`sessionId` to `display`). It deliberately skips
  `<session-id>/subagents/agent-<id>.jsonl` files, because they repeat the
  parent session id and would corrupt the index.
- **What we can learn:** A working session-listing recipe and its main
  pitfall (subagent transcripts). Its README states that all Claude Code
  tools are disabled by default: a safe-by-default permission stance.
- **What to avoid:** AGPL-3.0, so ideas only. Auth flow and bind address are
  **not verified**. The README promotes a hosted product.
- **Licence:** AGPL-3.0. Active (pushed 2026-11-19).
- **Source:** <https://github.com/siteboon/claudecodeui> (README,
  `package.json`, the Claude session synchronizer provider)

### 1.6 OpenClaw (`openclaw/openclaw`)

- **What it does:** Self-hosted assistant gateway with many chat channels and
  native apps.
- **How:** A gateway process is the local control plane for sessions, tools
  and channels. A `claude-cli` backend runs each turn as a new subprocess of
  the installed, logged-in Claude Code, resuming the saved session. Gateway
  tools reach the CLI through a loopback MCP bridge.
- **What we can learn:** Subprocess-per-turn with `--resume` is a proven
  model for our `/ask`. Exposing bridge capabilities (memory, tasks,
  calendar) to headless Claude as a local MCP server is cleaner than stuffing
  them into the prompt. Its docs treat every inbound message as untrusted.
- **What to avoid:** The size of the surface. Its docs note that tools run on
  the host for the main session unless sandboxing is configured. Gateway
  port, bind address and Tailscale handling are **not verified**.
- **Licence:** MIT. Active (pushed 2026-11-19).
- **Source:** <https://github.com/openclaw/openclaw> (README,
  `docs/gateway/cli-backends.md`, `docs/providers/anthropic.md`)

### 1.7 claude-code-telegram (`RichardAtCT/claude-code-telegram`)

- **What it does:** A Python Telegram bot in front of Claude Code; the closest
  analogue to a Python bridge.
- **How:** Python Agent SDK with a CLI fallback, SQLite persistence. Its
  configuration surface is a useful checklist: user allowlist, an approved
  base directory with path-traversal prevention, token-bucket rate limiting,
  a per-user cost cap, an audit log, automatic session resume per user and
  directory, a verbosity switch for tool activity, an optional webhook
  server and a scheduler.
- **What we can learn:** That checklist, minus the multi-user parts.
- **What to avoid:** Telegram as transport (already decided against for now).
  Licence is unclear: the GitHub API reports none and there is no LICENSE
  file; MIT is claimed only in the README badge and `pyproject.toml`. Do not
  copy code.
- **Licence:** not verified (see above). Last pushed 2026-10-01.
- **Source:** <https://github.com/RichardAtCT/claude-code-telegram> (README,
  `pyproject.toml`; the URL redirects to a new organisation)

### 1.8 opencode server mode (`anomalyco/opencode`)

- **What it does:** A different coding agent whose `opencode serve` is a
  headless HTTP server.
- **How:** Binds `127.0.0.1:4096` by default, optional HTTP basic auth via an
  environment variable, publishes an OpenAPI 3.1 spec at `/doc`, exposes
  session resources (`GET/POST /session`, `/session/status`) and a
  server-sent-events stream.
- **What we can learn:** An API shape worth copying: sessions as resources,
  one SSE stream for progress, localhost by default, and a published OpenAPI
  spec from which the Kotlin client can be generated.
- **What to avoid:** It is a different engine; adopting it is out of scope.
- **Licence:** MIT. Active (pushed 2026-11-19).
- **Source:** <https://github.com/anomalyco/opencode> (server docs page in
  the repository)

Checked and set aside: `sugyan/claude-code-webui` (MIT, **archived**);
`amantus-ai/vibetunnel` (MIT, terminal-in-browser proxy, quiet since
2026-08); `omnara-ai/omnara` (Apache-2.0, has pivoted away from being a phone
gateway); `kbwo/ccmanager` (MIT, not read).

---

## 2. Long-term memory designs

### 2.1 Claude Code built-in memory (official)

- **What it does:** Two layers: human-written `CLAUDE.md` instructions and
  Claude-written "auto memory".
- **How:** `CLAUDE.md` files load from the working directory and every
  parent; subdirectory files load lazily; `@path` imports nest up to four
  hops; the docs recommend under 200 lines per file. Auto memory is a
  per-project folder with a `MEMORY.md` index (one line per memory, only the
  first 200 lines or 25 KB are loaded at session start) plus one topic file
  per memory with a frontmatter `type` and a modification timestamp, read on
  demand. Its location can be changed with the `autoMemoryDirectory` setting.
- **What we can learn:** Index plus topic files, with a hard budget on the
  always-loaded file, is the vendor's own answer to memory bloat. Timestamp
  every fact. Do not store what can be derived from disk.
- **What to avoid:** Depending on implicit discovery under `-p`: `--bare`
  skips both layers and is announced as the future default (section 1.3).
  The bridge should inject memory context explicitly. A built-in
  consolidation ("dream") feature was looked for and not found in the memory
  docs: **not verified** either way. The API-level memory tool exists but
  does not apply while Claude Code is the engine.
- **Licence:** proprietary.
- **Source:** <https://code.claude.com/docs/en/memory.md>,
  <https://code.claude.com/docs/en/headless.md>

### 2.2 OpenClaw memory and "dreaming"

- **What it does:** Assistant memory as plain Markdown in a workspace ("there
  is no hidden state"), with a search index and a scheduled consolidation
  job.
- **How:** `USER.md` (profile), `MEMORY.md` (curated durable facts, loaded at
  session start, truncated when over budget), `memory/YYYY-MM-DD.md` daily
  notes (today and yesterday are loaded; older ones are reached by search),
  and `DREAMS.md`, a human-readable consolidation diary. The index is SQLite
  with full-text search plus embeddings (hybrid ranking with recency decay).
  Dreaming runs as a scheduled job in phases; only the last phase writes
  `MEMORY.md`. Candidates must pass score and recall-count gates. The model
  returns operations (add / merge / supersede) rather than rewritten prose;
  each promoted entry carries a `Source: path#Lx-Ly` reference; a rewrite is
  rejected if it loses too many prior entries or exceeds the file budget; the
  previous version is saved first. Chunk provenance (owner / agent /
  untrusted / system) is stored outside the text, and untrusted candidates
  are removed before consolidation.
- **What we can learn:** The promotion pipeline from daily log to curated
  file; source line references; a loss-limit check before accepting a
  rewrite; a reviewable diary; provenance as a gate against persisted
  prompt injection.
- **What to avoid:** The machinery (three phases, lineage tables, weighted
  scoring) is far more than a single-user vault needs. The default embedding
  provider is a hosted API, which would send memory content off the machine.
  Its docs state transcripts are not secret-masked before ingestion. The
  default dreaming cadence is **not verified**.
- **Licence:** MIT.
- **Source:** <https://github.com/openclaw/openclaw>
  (`docs/concepts/memory.md`, `dreaming.md`, `memory-builtin.md`)

### 2.3 Letta Code (`letta-ai/letta-code`, successor of Letta / MemGPT)

- **What it does:** A stateful agent harness whose memory is a git-backed
  file system; the closest analogue to our git-versioned memory folder.
- **How:** Memory blocks are files compiled into the system prompt, plus
  external Markdown files with `name` / `description` frontmatter linked by
  `[[path]]`. Edits take effect after a commit and the next prompt compile.
  A background "reflection" subagent reads recent transcripts and edits
  memory in a fixed priority order (corrections, preferences, facts,
  contradictions, procedures). It skips ephemera, converts relative dates to
  absolute ones, resolves contradictions toward the newest evidence, and
  commits under its own git author identity, listing the transcripts it
  reviewed. Triggers: off, on compaction, or every N steps. The prompt
  forbids storing secrets because memory is git-tracked.
- **What we can learn:** One commit per consolidation run, by a distinct git
  author, gives an audit trail and a one-command undo. "No change, no
  commit." Absolute dates only.
- **What to avoid:** The reflector has shell access and reads raw
  transcripts, so injected text can become standing instructions. The older
  `letta-ai/letta` repository (Apache-2.0) now points here; "sleep-time
  agents" under that name are **not verified** in current source.
- **Licence:** Apache-2.0. Active (pushed 2026-11-19).
- **Source:** <https://github.com/letta-ai/letta-code> (README, memory and
  reflection prompt files, `src/cli/app/reflection.ts`)

### 2.4 basic-memory (`basicmachines-co/basic-memory`)

- **What it does:** Markdown files as the source of truth plus a local SQLite
  index, exposed to agents as MCP tools.
- **How:** Each file is an entity with frontmatter (`title`, `type`,
  `permalink`, `tags`). Facts are lines under `## Observations`
  (`- [category] fact #tag (context)`); links are lines under `## Relations`
  (`- relation_type [[Target]]`). Search combines full-text and local
  embeddings; a file watcher keeps the index in step.
- **What we can learn:** A tiny, greppable line grammar makes Markdown
  machine-parseable without a database. The index is derived and
  rebuildable, so it never needs to be in git. Local embeddings keep data on
  the machine.
- **What to avoid:** AGPL-3.0: ideas only. It has no consolidation; notes
  only accumulate. Sync trigger details are **not verified**.
- **Licence:** AGPL-3.0. Active (pushed 2026-11-19).
- **Source:** <https://github.com/basicmachines-co/basic-memory> (README)

### 2.5 Vector and graph stores: mem0 and Graphiti

- **What it does:** `mem0ai/mem0` extracts facts from conversations into a
  vector store; `getzep/graphiti` builds a temporal knowledge graph.
- **How:** mem0's README describes single-pass, add-only extraction (one LLM
  call, nothing overwritten) with fused semantic, keyword and entity
  retrieval; defaults use a hosted LLM and hosted embeddings. Graphiti stores
  entities and facts with validity windows; contradicted facts are
  invalidated, not deleted, and every fact traces back to the raw episode. It
  needs a graph database and an LLM.
- **What we can learn:** Two data-model ideas that work in plain files:
  append-only facts (never let a model destructively rewrite), and
  "supersede, don't delete" with `valid_from` / `superseded_on` dates, which
  maps onto frontmatter plus git history.
- **What to avoid:** Opaque records the owner cannot read or review; hosted
  defaults that send memory off the machine; a database server and per-ingest
  LLM cost for one user. mem0's README benchmarks concern its managed
  platform and were ignored. Graphiti was read at README level only.
- **Licence:** both Apache-2.0. Both active.
- **Source:** <https://github.com/mem0ai/mem0>,
  <https://github.com/getzep/graphiti>

### 2.6 First-hand vault write-ups

Lower confidence (read through a summarising fetch, not raw):

- A write-up on running an Obsidian vault with Claude Code
  (<https://okhlopkov.com/second-brain-obsidian-claude-code/>) uses
  `projects/<name>/{overview.md,tasks.md,ideas.md}` plus a root instruction
  file, and lists what failed: too many clever folders ("if the agent needs
  to infer where something belongs, the structure is too clever"), letting
  the AI edit raw notes (provenance is lost), mixing credentials with memory,
  and long rule files nobody reads.
- Kenneth Reitz's essay
  (<https://kennethreitz.org/essays/2026-03-06-obsidian_vaults_and_claude_code>)
  uses numbered top-level folders, treats the instruction file as an "API
  contract", and auto-commits to git.

---

## 3. Moodle API clients

Moodle core facts below were read from source at branch `MOODLE_405_STABLE`.

### 3.1 Moodle core web services (`moodle/moodle`)

- **What it does:** The server side every client talks to.
- **How:**
  - *REST:* `POST <base>/webservice/rest/server.php` with `wstoken`,
    `wsfunction` and `moodlewsrestformat=json` (XML if omitted). Errors are
    JSON objects with an `errorcode`, often with HTTP 200.
  - *Mobile service:* the built-in `moodle_mobile_app` service includes,
    among others, `core_webservice_get_site_info`,
    `core_enrol_get_users_courses`, `core_course_get_contents`,
    `core_course_get_updates_since`, `mod_assign_get_assignments`,
    `mod_assign_get_submission_status`,
    `core_calendar_get_action_events_by_timesort` and
    `core_calendar_get_calendar_events`. Whether a given student account may
    call each one on a given site must be tested; reading the allowed list
    from `core_webservice_get_site_info` is the usual way (**not verified**
    by us on a live site).
  - *Files:* `webservice/pluginfile.php` takes a `token` parameter instead of
    cookies; clients append `token=` to each `fileurl` returned by
    `core_course_get_contents`. Fails if the service has file download off.
  - *Token by password:* `POST <base>/login/token.php` with `username`,
    `password`, `service=moodle_mobile_app` returns `{token, privatetoken}`.
    Error codes distinguish "web services off", "mobile service disabled",
    "invalid login" (typical for SSO-only accounts) and "user not allowed".
    `?appsitecheck=1` answers without credentials.
  - *Token by browser (SSO sites):* after a normal browser login,
    `<base>/admin/tool/mobile/launch.php?service=moodle_mobile_app&passport=<random>`
    redirects to `moodlemobile://token=<base64>`. The decoded payload is
    `md5(wwwroot + passport):::token[:::privatetoken]`. The page only works
    when the admin setting `typeoflogin` is "browser" or "embedded browser"
    (or OAuth2 auth is enabled).
  - *Credential-free probe:* `tool_mobile_get_public_config`, callable
    without login through `<base>/lib/ajax/service-nologin.php`, returns
    `enablewebservices`, `enablemobilewebservice`, `typeoflogin`, `launchurl`
    and the identity providers.
  - *Token lifetime:* default 12 weeks, admin-configurable; an expired token
    yields `invalidtoken` and the user must log in again.
  - *iCal fallback:* `calendar/export_execute.php?userid=<id>&authtoken=<hash>&preset_what=all&preset_time=recentupcoming`
    needs no web services, only calendar export enabled. The URL is a secret
    and changes with the password.
  - *QR login* is restricted to the official app by user-agent check; not
    usable by a third-party client without spoofing.
- **What we can learn:** A student-level, read-only client needs one generic
  call helper and about five functions. The probe makes the "is this even
  possible on the owner's site?" question answerable before any credential
  is typed.
- **What to avoid:** GPL-3.0: copy no code, protocol facts only. Never handle
  the password in the bridge. Do not keep the private token (it can mint
  login cookies). Do not spoof the official app.
- **Licence:** GPL-3.0. Active.
- **Source:** <https://github.com/moodle/moodle> (`login/token.php`,
  `admin/tool/mobile/launch.php`, `admin/tool/mobile/classes/api.php`,
  `lib/db/services.php`, `webservice/pluginfile.php`,
  `calendar/export_execute.php`); `moodle/devdocs` (`external/files.md`
  only; licence not asserted by the API)

### 3.2 Official Moodle app (`moodlehq/moodleapp`)

- **What it does:** The official mobile client (TypeScript).
- **How:** Fetches the public config first, then branches: for browser-type
  logins it opens `launch.php` with a random passport and its URL scheme,
  and on return splits the payload on `:::` and checks
  `md5(siteUrl + passport)`; otherwise it posts to `/login/token.php`.
  Background sync runs handlers at a default interval of one hour, skips
  when offline, and re-downloads files based on stored revision and
  modification time.
- **What we can learn:** Probe, then branch on `typeoflogin`; verify the
  passport signature; detect changed files by `timemodified`; hourly is the
  politeness level the vendor itself uses.
- **What to avoid:** Porting code wholesale; it is a large app. Ideas only.
- **Licence:** Apache-2.0. Active (pushed 2026-11-19).
- **Source:** <https://github.com/moodlehq/moodleapp> (login helper and
  sites services)

### 3.3 Moodle-DL (`C0D3D3V/Moodle-DL`)

- **What it does:** Python tool that downloads all course content a student
  can see and notifies about new files.
- **How:** Password login through `login/token.php`. For SSO sites, a wizard
  asks the user to log in with a browser, open `launch.php` with a custom URL
  scheme, and copy the resulting (failed) `...://token=...` URL from the
  browser's network tab; the tool base64-decodes it and splits on `:::`. Uses
  the site-info, courses, course-contents, assignments and calendar
  functions; for modules the API does not cover it mints cookies with the
  private token and scrapes. Redacts tokens and passwords in logs.
- **What we can learn:** The manual SSO token flow needs no password
  handling by the tool. Log
  redaction of `wstoken` / `token=`.
- **What to avoid:** GPL-3.0: no code. It uses a fixed passport and skips
  the signature check; stores token and private token in a plain config
  file; its cookie/scraping path is fragile and, by its own README, the
  cookie file allows account takeover.
- **Licence:** GPL-3.0. Active (pushed 2026-10-22).
- **Source:** <https://github.com/C0D3D3V/Moodle-DL> (wizard, request helper
  and service modules, README)

### 3.4 edu-sync (`mkroening/edu-sync`)

- **What it does:** Rust CLI that syncs course files.
- **How:** Uses only three functions (site info, user courses, course
  contents). Implements the launch flow with a random passport and the md5
  check; has typed handling of `invalidtoken`.
- **What we can learn:** Three functions are enough for materials sync. A
  correct, minimal SSO implementation to compare against.
- **What to avoid:** GPL-3.0: no code.
- **Licence:** GPL-3.0. Active (pushed 2026-10-25).
- **Source:** <https://github.com/mkroening/edu-sync> (README,
  `edu-ws/src/token/sso.rs`)

### 3.5 Moodle MCP servers (`Faixiang/Moodle-MCP`, `G36maid/moodle-mcp`)

- **What it does:** Expose Moodle read functions to an LLM agent as MCP
  tools.
- **How:** The first is a single Python file using only the standard
  library: course contents, updates-since, assignments, submission status,
  with a 150 ms pause between calls and `token=` appended to file URLs. The
  second (Rust; README and docs only, source **not verified**) claims token
  redaction, sending the token only to same-host file URLs, a read-only
  mode, and per-school SSO drivers that replay the login portal.
- **What we can learn:** Read-only tool set; serialised calls with a small
  delay; token attached only to URLs on the Moodle host; redaction.
- **What to avoid:** Replaying an SSO portal with the owner's password is
  fragile and a terms-of-use risk. Feature lists in both READMEs are claims.
  One README contains a "paste this into your agent" install block: a
  reminder that third-party READMEs are untrusted input.
- **Licence:** both MIT. Pushed 2026-11-01 and 2026-10-20.
- **Source:** <https://github.com/Faixiang/Moodle-MCP>,
  <https://github.com/G36maid/moodle-mcp>

Checked and set aside as stale: `hexatester/moodlepy` (MIT, last push 2024),
`mrcinv/moodle_api.py` (MIT, last push 2023).

### 3.6 Recommended "can the owner get a token?" check

1. Without credentials, call `tool_mobile_get_public_config` through
   `service-nologin.php`; read `enablewebservices`,
   `enablemobilewebservice`, `typeoflogin`.
2. If web services or the mobile service are off: stop; fall back to the
   iCal export URL for deadlines.
3. If `typeoflogin` is in-app and the account has a local password: a local
   script, run by the owner, posts to `/login/token.php`.
4. Otherwise (SSO): the owner logs in with a browser, opens the launch URL
   with a random passport, and pastes the resulting `moodlemobile://token=`
   URL into a local script that verifies the md5 signature and stores only
   the token under `~/.config/lifeos/` with mode 600.
5. Validate with `core_webservice_get_site_info`, check the needed functions
   are listed, and fetch one file URL with `token=`.

---

## 4. Android usage-stats, screen-time and sleep tracking

### 4.1 `UsageStatsManager` (Android framework)

- **What it does:** System API for per-app usage and device events.
- **How:**
  - *Access:* needs the special `PACKAGE_USAGE_STATS` access. Declaring it is
    not enough; the user must grant it by hand on the "Usage access" settings
    screen (`Settings.ACTION_USAGE_ACCESS_SETTINGS`). Apps check the grant
    through `AppOpsManager` (`OPSTR_GET_USAGE_STATS`).
    `queryEventsForSelf` needs no permission but only returns the calling
    app's own events.
  - *Aggregates:* `queryUsageStats(interval, begin, end)` returns buckets, and
    the javadoc says the requested range "may be expanded to the nearest
    whole interval period", so an exact local-midnight day cannot be asked
    for.
  - *Raw events:* `queryEvents(begin, end)`. The javadoc says "Events are
    only kept by the system for a few days." In the AOSP source snapshot
    read, daily files are pruned after 10 days; vendor builds may differ
    (**not verified**).
  - *Useful event types:* `ACTIVITY_RESUMED` / `ACTIVITY_PAUSED` (app in
    foreground), `SCREEN_INTERACTIVE` / `SCREEN_NON_INTERACTIVE`,
    `KEYGUARD_SHOWN` / `KEYGUARD_HIDDEN` (the latter "typically happens when
    the user unlocks"), `DEVICE_SHUTDOWN` / `DEVICE_STARTUP`. The screen and
    keyguard events exist from API 28, the activity and shutdown ones from
    API 29.
  - *Caveats:* queries return `null` until the user has unlocked the device
    after boot; the shutdown timestamp is only the last database write
    before shutdown.
- **What we can learn:** The already-decided sleep fallback maps directly to
  the last `SCREEN_NON_INTERACTIVE` before and the first `KEYGUARD_HIDDEN`
  after the longest overnight gap, from the same event stream that gives
  screen time. Minimum SDK 29 is enough.
- **What to avoid:** Building charts from the aggregated buckets. Letting
  more than a few days pass without reading events: they are gone.
- **Licence:** platform API (AOSP; the GitHub mirror read for source is an
  archived snapshot with no licence asserted by the API).
- **Source:**
  <https://developer.android.com/reference/android/app/usage/UsageStatsManager>,
  <https://developer.android.com/reference/android/app/usage/UsageEvents.Event>

### 4.2 Health Connect and the Play services Sleep API

- **What it does:** Health Connect is a shared on-device store for health
  records, including sleep sessions. The Sleep API detects sleep
  automatically from phone motion and ambient light.
- **How:**
  - *Health Connect:* part of the Android framework from Android 14.
    `SleepSessionRecord` holds a start, an end and optional stages.
    Permissions `android.permission.health.READ_SLEEP` / `WRITE_SLEEP`; the
    app must ship a permission-rationale activity. Reads are foreground-only
    unless a separate background-read permission is granted, and only 30
    days before the first grant are readable by default. Health Connect does
    not produce sleep data itself; something (a wearable's app, or a manual
    tracker) must write it. Without such a writer it is expected to be empty
    (inference, **not verified**).
  - *Sleep API:* `ActivityRecognitionClient.requestSleepSegmentUpdates(...)`
    from `play-services-location`, with the `ACTIVITY_RECOGNITION`
    permission. Delivers a daily `SleepSegmentEvent` (start, end, status) and
    periodic `SleepClassifyEvent`s (confidence 0-100). Needs Google Play
    services. Battery cost and accuracy are **not verified**.
- **What we can learn:** Health Connect is useful to us mainly as an *export*
  target (write our manual entries so other apps see them) and as a future
  import path if the owner gets a wearable. The Sleep API is a way to get
  automatic sleep times without a wearable.
- **What to avoid:** Expecting Health Connect to contain sleep data on a
  phone with no wearable. Play Console declaration rules are written for
  store apps; how they apply to a sideloaded app is **not verified**.
- **Licence:** platform / Google Play services APIs.
- **Source:** <https://developer.android.com/health-and-fitness/guides/health-connect>,
  <https://developers.google.com/location-context/sleep>

### 4.3 ActivityWatch for Android (`ActivityWatch/aw-android`)

- **What it does:** Open-source app-usage tracker, the Android member of the
  ActivityWatch family.
- **How:** Embeds the project's Rust server on the device and shows its web
  UI. The usage watcher calls `queryEvents(lastUpdated, ...)` as an
  incremental cursor and turns resume/pause events into heartbeats; unlocks
  (`KEYGUARD_HIDDEN`) go into a separate bucket. A session parser closes a
  session on pause, on another app's resume, on screen-off or on shutdown,
  and caps orphaned sessions at five minutes. Sync runs about every 15
  minutes with an alarm fallback and a worker. Which parser is live and what
  the sync target is are **not verified**.
- **What we can learn:** Cursor-based incremental ingest; explicit rules for
  closing sessions; a separate unlock stream; its README documents granting
  usage access over `adb` (`appops set <pkg> android:get_usage_stats allow`),
  handy during development.
- **What to avoid:** The embedded server, web-view UI and accessibility
  service: heavier than we need and contrary to the native Compose decision.
  MPL-2.0 is file-level copyleft; reimplement, do not copy files.
- **Licence:** MPL-2.0. Active (pushed 2026-11-17).
- **Source:** <https://github.com/ActivityWatch/aw-android> (README, manifest,
  `UsageStatsWatcher.kt`, `SessionParser.kt`)

### 4.4 usageDirect (Codeberg, `fynngodau/usageDirect`)

- **What it does:** Small screen-time app that keeps its own history.
- **How:** Computes "precise foreground time" from `queryEvents` rather than
  buckets. Its event-log wrapper handles unmatched open events, duplicate
  open/close events and close events whose open lies before the query
  window; a second wrapper merges same-app usages less than 30 seconds apart
  for display.
- **What we can learn:** The list of event-log edge cases is a ready-made
  unit-test plan for our own session builder.
- **What to avoid:** GPL-3.0: no code. The README describes the project as
  low-maintenance (last commit 2024-11).
- **Licence:** GPL-3.0 (LICENSE file read on Codeberg; not checkable with
  `gh api`).
- **Source:** <https://codeberg.org/fynngodau/usageDirect>

### 4.5 plees-tracker (`vmiklos/plees-tracker`)

- **What it does:** Manual start/stop sleep tracker with graphs and CSV
  import/export; by its own description it does nothing while you sleep.
- **How:** Stores sessions locally and optionally writes them to Health
  Connect (`WRITE_SLEEP` only, with a rationale activity and a worker). Uses
  the classic View system and MPAndroidChart.
- **What we can learn:** The closest match to our decided manual-first sleep
  model, under a compatible licence. Its Health Connect write path and CSV
  schema are reusable ideas.
- **What to avoid:** Its UI stack (Views) is not ours.
- **Licence:** MIT. Active (pushed 2026-11-18).
- **Source:** <https://github.com/vmiklos/plees-tracker> (README, manifest,
  build file)

### 4.6 Supporting pieces: Vico, WorkManager limits, dontkillmyapp

- **What it does:** `patrykandpatrick/vico` is a chart library for Compose.
  WorkManager is the standard way to schedule the daily sync.
  dontkillmyapp.com documents vendor-specific background-killing behaviour.
- **How:** WorkManager's minimum periodic interval is 15 minutes and timing
  is inexact. The official Doze documentation says WorkManager tasks do not
  run during Doze, and idle apps may get network only about once a day. An
  app can ask for a battery-optimisation exemption
  (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`). dontkillmyapp.com lists
  per-vendor user and developer workarounds and has a JSON API.
- **What we can learn:** Treat background sync as best effort: also ingest
  and sync whenever the app is opened, make uploads idempotent, and show
  "last sync" in the UI. Link the vendor page in the owner's phone-settings
  issue.
- **What to avoid:** Designing anything that needs a sync at an exact time.
  Specific Vico chart types were **not verified**.
- **Licence:** Vico is Apache-2.0 (active); the dontkillmyapp content
  repository is CC-BY-4.0.
- **Source:** <https://github.com/patrykandpatrick/vico>,
  <https://developer.android.com/training/monitoring-device-state/doze-standby>,
  <https://github.com/urbandroid-team/dont-kill-my-app>

Seen but not examined: Gadgetbridge (Codeberg, AGPL-3.0; relevant only with a
wearable), TimeLimit (Codeberg, GPL-3.0).

---

## 5. Session-resume UIs for Claude Code

### 5.1 Official session model and Agent SDK session functions

- **What it does:** Defines where sessions live, how they are resumed, and
  offers supported functions to list them.
- **How:**
  - *Storage:* one JSONL transcript per session at
    `~/.claude/projects/<project>/<session-id>.jsonl`, where `<project>` is
    the working directory with non-alphanumeric characters replaced by `-`
    (long names are truncated and hashed, so the encoding is not
    reversible). The docs state the entry format "is internal to Claude Code
    and changes between versions, so scripts that parse these files directly
    can break on any release."
  - *Flags:* `--resume` (id, name or absolute transcript path), `--continue`,
    `--session-id`, `--fork-session`, `--name`, `--no-session-persistence`
    (print mode only).
  - *Resume directory:* in current versions `claude --resume <id>` works
    "from any directory" by searching all projects. Which working directory
    a cross-project `-p --resume` run then uses is **not verified**.
  - *Visibility:* `-p` and SDK sessions are hidden from the interactive
    `/resume` picker but can be resumed by id; a `-p` run gets no generated
    title.
  - *Retention:* `cleanupPeriodDays` defaults to 30; older transcripts are
    deleted silently.
  - *Concurrency:* resuming one session from two processes makes both write
    one transcript and "messages from one of them can be missing"; the
    documented remedy is `--fork-session`. `claude agents --json` is
    described as "the supported way to read session state from outside
    Claude Code" (fields include `cwd`, `pid`, `status`, `sessionId`).
    Whether plain `-p` runs appear there is **not verified**.
  - *SDK:* the Python Agent SDK documents `list_sessions()`,
    `get_session_messages()`, `get_session_info()`, `rename_session()`,
    `tag_session()` and `fork_session()` (TypeScript: `listSessions()` etc.),
    explicitly for building custom session pickers. A session-info record has
    `session_id`, `summary`, `last_modified`, `custom_title`, `first_prompt`,
    `git_branch`, `cwd`, `tag`, `created_at`. In the Python SDK source the
    listing is pure Python (it does not start the CLI), reads only the first
    and last 64 KB of each file, and skips sidechain (subagent) transcripts.
- **What we can learn:** There is a supported, MIT-licensed, Python-native
  way to list sessions with title, folder and last activity: exactly the
  LifeOS "sessions" feature. No need to parse the internal format ourselves.
- **What to avoid:** Parsing JSONL directly; decoding the folder name back
  into a path; assuming sessions live forever. Nothing in the SDK reports
  whether a session is in use.
- **Licence:** `anthropics/claude-agent-sdk-python` is MIT (active). The CLI
  itself is proprietary.
- **Source:** <https://code.claude.com/docs/en/sessions.md>,
  <https://code.claude.com/docs/en/cli-reference.md>,
  <https://code.claude.com/docs/en/agent-sdk/sessions.md>,
  <https://github.com/anthropics/claude-agent-sdk-python>
  (`src/claude_agent_sdk/_internal/sessions.py`)

### 5.2 CloudCLI liveness check (`siteboon/claudecodeui`)

- **What it does:** See 1.5. Relevant here: the only tool found that detects
  a session being used by a process it did not start.
- **How:** Reads the small per-process files Claude Code keeps under
  `~/.claude/sessions/` (the docs confirm the directory "holds one small
  file per running session"; the field names come from this project's source
  only), probes the pid with signal 0, and on Linux compares the recorded
  process start time with `/proc/<pid>/stat` to defeat pid reuse. It treats
  only "busy" sessions as in use.
- **What we can learn:** The pid-plus-start-time pattern for a robust
  liveness check.
- **What to avoid:** AGPL-3.0: ideas only. The files are undocumented.
  Counting only "busy" is too lax for us: an idle but open interactive
  session is still a second writer.
- **Licence:** AGPL-3.0.
- **Source:** <https://github.com/siteboon/claudecodeui> (Claude CLI liveness
  service)

### 5.3 claude-code-log (`daaain/claude-code-log`)

- **What it does:** Python tool that renders transcripts to HTML/Markdown and
  has a terminal browser that can resume a session.
- **How:** A SQLite cache keyed on file mtime and size stores title fields
  and `cwd` per session. Resume changes into the session's recorded `cwd`
  and then executes `claude --resume=<id>`, warning if the directory no
  longer exists.
- **What we can learn:** Cache by mtime and size; take the directory from
  transcript content; handle the missing-directory case explicitly.
- **What to avoid:** It parses the full internal format; the size of its
  parser shows the maintenance cost of that route.
- **Licence:** MIT. Active (pushed 2026-11-18).
- **Source:** <https://github.com/daaain/claude-code-log> (`cache.py`,
  `tui.py`)

### 5.4 claude-history (`raine/claude-history`)

- **What it does:** Rust terminal UI for fuzzy search and resume across
  several agents' sessions.
- **How:** Reads summary, AI-title and custom-title entries; takes `cwd` from
  the first user record; uses the newest message timestamp as last activity
  (falling back to mtime); resumes with `--resume <id>`, optionally
  `--fork-session`, in the project directory.
- **What we can learn:** Message timestamps are a better "last activity" than
  file mtime, because a rename also appends to the file. Offering "fork" next
  to "resume".
- **What to avoid:** Its fallback of copying a transcript into another
  project's folder creates duplicates, which the official docs say make the
  id lookup fail.
- **Licence:** MIT. Pushed 2026-11-04.
- **Source:** <https://github.com/raine/claude-history>
  (`src/history/sources/claude.rs`)

### 5.5 Small pickers and viewers (`sasazame/ccresume`, `d-kimuson/claude-code-viewer`)

- **What it does:** A minimal terminal picker, and a web viewer/launcher.
- **How:** ccresume sorts transcripts by mtime, reads the project path from
  the first message's `cwd`, and spawns `claude --resume <id>` there; its
  docs note that the folder-name encoding cannot be reversed.
  claude-code-viewer prefers a custom title over an AI title and keeps an
  in-process state machine that refuses a second turn on a session it is
  already running.
- **What we can learn:** An in-process registry of the bridge's own running
  sessions is the first and cheapest layer of "refuse if in use".
- **What to avoid:** Neither was seen to detect externally started sessions
  (**not verified** for the viewer). Both were last pushed in 2026-08.
- **Licence:** both MIT.
- **Source:** <https://github.com/sasazame/ccresume>,
  <https://github.com/d-kimuson/claude-code-viewer>

Seen but not examined: `winfunc/opcode` (AGPL-3.0), `jhlee0409/omnilogue`
(MIT), `simonw/claude-code-transcripts` (Apache-2.0),
`ZeroSumQuant/claude-conversation-extractor` (MIT).

---

## Cross-cutting observations

**Exposing an agent to a phone.** Every gateway surveyed ends up with the
same controls: bind to localhost or a private interface, authenticate every
request, restrict the working directories, restrict tools by default, cap
cost per run, and keep an audit log. Projects that relay through a server add
pairing codes and end-to-end encryption; on a Tailscale-only bridge those are
not needed, but a bearer token still is. A session list is transcript
content (titles are prompt text), so it needs the same protection as the
answers and must stay out of logs and notifications.

**Prompt injection.** A phone-driven agent reads web pages, course
materials, READMEs and later e-mail. Three concrete defences appeared in the
surveyed projects: (1) treat all inbound content as data; (2) record the
provenance of every memory entry and never let externally sourced text be
promoted into always-loaded memory; (3) never give a consolidation job more
tools than it needs. During this survey, documentation pages carried
agent-directed banners and one README carried a "paste this into your
agent" install block; all were ignored. Headless runs also execute project
hooks and MCP servers of the directory they start in, with no trust dialog,
so the bridge must only start runs in directories the owner has approved.

**Permissions drift.** The headless CLI's defaults have changed and are
announced to change again (manual permission mode for `-p`; `--bare` as a
future default). One third-party backend was seen silently falling back to
skipping all permission checks. The bridge should set every relevant flag
explicitly, fail closed, and run a small smoke test after each CLI upgrade.

**Cost and plan usage.** The vendor's help-centre article on using the Agent
SDK with a subscription, as read on the survey date, says that when signed
in with a subscription plan "the Claude Agent SDK and claude -p still draw
from your subscription limits", that a previously announced change to this
was paused, and that some plans now include monthly API credits usable when
running with an API key. Separately, the headless docs say bare mode does
not use the subscription login. Taken together: the current setup works on
the subscription, but billing rules for scripted use are in motion.
Scheduled jobs (nightly consolidation, sync summaries) spend the same budget
as interactive use, so they need a budget cap and an off switch. Exact
credit amounts are **not verified**.
Source: <https://support.claude.com/en/articles/15036540-use-the-claude-agent-sdk-with-your-claude-plan>

**Undocumented formats.** Session transcripts and the per-process session
files are internal formats. Supported interfaces exist (the Agent SDK
session functions, `claude agents --json`) and should be preferred.

**Licences.** Much of the most relevant code is GPL or AGPL (Moodle clients,
CloudCLI, basic-memory, usageDirect). For an MIT project this means reading
for ideas and protocol facts only.

---

## Ranked list of ideas

Effort: S = under a day, M = a few days, L = a week or more.
`[fits]` = consistent with the logged decisions. `[decision]` = would change
scope, architecture or a logged decision; the affected decision is named.

1. **Pin every headless flag and inject context explicitly.** Pass
   permission mode, allowed tools, model and memory context on every
   `claude -p` call; never rely on defaults; add a smoke test that detects a
   changed default (such as `--bare`) after a CLI upgrade. From: official
   headless docs (1.3). Effort: S. `[fits]`
2. **List sessions through the Python Agent SDK's session functions instead
   of parsing transcripts.** `list_sessions()` / `get_session_info()` return
   title, folder, branch and last activity; used as a library only, runs
   still go through `claude -p`. From: official Agent SDK (5.1). Effort: S.
   `[fits]`
3. **Layered "in use" check with a fork option.** Bridge-owned per-session
   lock, then `claude agents --json`, then treat a failing resume as busy;
   offer `--fork-session` instead of refusing outright; resume in the
   session's recorded directory and report a clean error if it is gone or
   the session has expired. From: official session docs, CloudCLI,
   claude-code-log, claude-history (5.1 to 5.4). Effort: M. `[fits]`
4. **Three-tier memory with a hard budget.** A small always-loaded index
   (about 200 lines), curated topic files with `type` and date frontmatter,
   and append-only daily logs of which only today's and yesterday's are
   loaded. From: Claude Code auto memory, OpenClaw (2.1, 2.2). Effort: M.
   `[fits]`
5. **Use Claude Code Remote Control as an interim or fallback phone
   interface** for chat and session resume until the app exists. From:
   official Remote Control (1.1). Effort: S. `[decision]` Changes "Phone to
   computer: Tailscale private network" (traffic and transcripts go through
   the vendor's relay) and "Interface: own Android app".
6. **Owner-only Moodle token flow.** Probe the site without credentials,
   then have the owner obtain a mobile-service token through the browser
   launch flow and a local script that verifies the signature and stores
   only the token; the bridge never sees the password; discard the private
   token. From: Moodle core, official app, Moodle-DL, edu-sync (3.1 to 3.4).
   Effort: M. `[fits]`
7. **Thin read-only Moodle client plus polite sync.** One call helper and
   about five functions; detect changes by `timemodified`; sequential calls
   with a delay, hourly at most; token only to same-host URLs; redact tokens
   in logs; iCal export as fallback for deadlines. From: official app,
   edu-sync, Moodle MCP servers (3.2, 3.4, 3.5). Effort: M. `[fits]`
8. **Ingest raw usage events with a cursor, never buckets.** Store sessions
   locally, ingest on every app open as well as in the background worker
   (events survive only a few days), sync idempotently with stable ids.
   From: `UsageStatsManager` docs, aw-android, usageDirect (4.1, 4.3, 4.4).
   Effort: M. `[fits]`
9. **Derive the sleep fallback from the same event stream.** Last
   screen-off before and first unlock after the longest overnight gap, with
   shutdown and locked-after-boot cases handled; minimum SDK 29. From:
   `UsageStatsManager` docs (4.1). Effort: S. `[fits]`
10. **Nightly consolidation as a separate, tightly limited headless run.**
    Emits add / merge / supersede operations with source references, is
    rejected if it drops too many entries or exceeds the budget, makes one
    git commit under a distinct author, commits nothing when nothing
    changed, and has a cost cap. From: OpenClaw dreaming, Letta Code
    reflection (2.2, 2.3). Effort: L. `[fits]` It spends plan usage unattended
    and lets a model rewrite memory, so the trigger mode (nightly automatic,
    manual only, or propose-then-approve) should be settled in the planned
    memory-design approval.
11. **Provenance gate for memory.** Tag each entry as owner-stated,
    agent-inferred or external (Moodle, web, later e-mail); never promote
    external text into always-loaded files; keep raw captures immutable; no
    secrets in memory. From: OpenClaw, Letta Code, vault write-ups (2.2,
    2.3, 2.6). Effort: S. `[fits]`
12. **Expose memory, tasks and calendar to headless Claude as a local MCP
    server** rather than only as prompt text. From: OpenClaw loopback
    bridge, basic-memory (1.6, 2.4). Effort: M. `[fits]`
13. **Decide how scripted runs are paid for before the default changes.**
    Either keep the subscription login (works today, but `--bare` is
    announced as the future `-p` default and does not use it) or move the
    bridge to an API key funded by plan credits or pay-as-you-go. From:
    official headless docs and help-centre article (1.3, cross-cutting).
    Effort: S. `[decision]` Changes "AI engine: Claude Code in headless mode
    ... runs on the user's subscription"; involves money.
14. **Bridge API shaped as session resources plus one SSE stream, with a
    published OpenAPI spec** from which the Kotlin client is generated.
    From: opencode server (1.8). Effort: M. `[fits]`
15. **Supersede, don't delete; absolute dates only.** Changed facts get a
    `superseded` note and date instead of being overwritten. From: Graphiti,
    Letta Code (2.5, 2.3). Effort: S. `[fits]`
16. **Automatic sleep detection with the Play services Sleep API** as a
    third source next to manual entry and the screen fallback. From: Sleep
    API docs (4.2). Effort: M. `[decision]` Changes "Sleep data: manual
    entry first, fallback to screen-off / first-unlock times"; adds a Google
    Play services dependency and the activity-recognition permission.
17. **Run turns through the Python Agent SDK instead of spawning `claude
    -p`.** Gives typed streaming and hooks in-process. From:
    claude-code-telegram, CloudCLI (1.7, 1.5). Effort: M. `[decision]`
    Changes "AI engine: Claude Code in headless mode (`claude -p`)".
18. **Derived search index outside git.** Start with plain text search; add
    a rebuildable SQLite full-text index when needed. From: basic-memory,
    OpenClaw (2.4, 2.2). Effort: M. `[fits]`
19. **Semantic (embedding) search over memory.** Local embeddings keep data
    on the machine; hosted embeddings are the default in several projects.
    From: OpenClaw, mem0, basic-memory (2.2, 2.5, 2.4). Effort: L.
    `[decision]` A hosted embedding provider would change "Memory lives
    outside this repo, local only"; a local model would not, but adds a
    heavy dependency.
20. **Charts with Vico; write manual sleep entries to Health Connect.**
    From: Vico, plees-tracker (4.6, 4.5). Effort: S each. `[fits]`
21. **Name bridge-started sessions and surface retention.** Set `--name` on
    phone-started runs (they get no generated title) and tell the owner
    about the 30-day default cleanup. From: official session docs (5.1).
    Effort: S. `[fits]`
22. **Config-surface checklist for the bridge:** approved base directories
    with path-traversal checks, rate limit, per-run cost cap, audit log,
    verbosity switch for tool activity. From: claude-code-telegram (1.7).
    Effort: S. `[fits]`
23. **Push bridge events into a long-lived session through a custom
    channel, or add a chat-app channel for notifications.** From: official
    Channels (1.2). Effort: M. `[decision]` A chat-app channel changes
    "Interface: own Android app. No Telegram for now"; the feature is a
    research preview.
