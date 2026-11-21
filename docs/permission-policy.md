# Permission policy for prompts from the phone

Status: **built and verified on the owner's machine** for Claude Code
2.1.328. The read-only mode is the default. The capable mode is switched on
by `scripts/verify-guardrails` only while every probe of section 7 passes
for the installed Claude Code version; so far for runs in the memory folder
(section 6).

The owner's decision (see `docs/decisions.md`): prompts from the phone are
read-only by default. A *capable* mode, chosen per request, may run shell
commands, but only inside guardrails. A guardrail that cannot be enforced
technically is never weakened silently; it becomes a question for the owner.

Sources: `docs/research/claude-headless.md` (section 5), the official
sandboxing and settings pages of Claude Code, and the tests in section 3,
run on the owner's machine with Claude Code 2.1.328.

## 1. Two modes

| | Read-only (default) | Capable (per request) |
|---|---|---|
| Chosen by | nothing; every request | `"mode": "capable"` in the request |
| Tools | read and search files | read, search, edit files, shell commands |
| Where | the run's folder only | the run's folder and the memory folder (write); other allowed folders (read) |
| Network from commands | no command tool at all | none |
| Web tools | none | none (the only run with web tools is the course-resources job, section 9.10) |
| Audit log | not needed (no commands) | every tool call, before it runs |
| Available when | always | only after the guardrail check passed on this machine and CLI version |

The run's folder is the ask folder (default: the memory folder) for `/ask`,
or the session's original folder for a resumed session. It must lie inside
the memory folder or a path from `~/.config/lifeos/allowed_paths`.

A session keeps no mode. Each request states its own; a resumed session that
was capable before is read-only again unless the new request says otherwise.

## 2. Why the shell changes everything

With file tools only, Claude Code itself checks every path, and its
restricted mode confines the tools to the run's folder. That is the read-only
mode, and it was tested (section 3).

A shell command is a separate program. Permission rules of the form
`Bash(pattern)` look at the command *text*; they cannot see what the program
does. `cat ~/.ssh/key`, a small script, or `python -c …` reach the same file
by different text. So for shell commands:

- **Rules on command text are an explanation layer, never the enforcement.**
  They catch the honest case and produce a clear refusal message.
- **Enforcement must come from the operating system**: Claude Code's sandbox
  (on Linux: bubblewrap for files, a proxy for the network, a seccomp filter
  for Unix sockets), which applies to the command and everything it starts.

Consequence: **the capable mode exists only with a working OS sandbox.**
There is no rules-only fallback for it. Without the sandbox the bridge
refuses capable requests and stays read-only.

## 3. What was tested on this machine

| Test | Result |
|---|---|
| Read-only flags, file inside the folder | answered |
| Read-only flags, file outside the folder | denied, reported in `permission_denials` |
| Read-only flags, create a file | no write tool exists; nothing created |
| Sandbox prerequisites | `bubblewrap` installed; `socat` **missing**; unprivileged user namespaces **blocked** by the system's AppArmor setting, so `bwrap` cannot start |
| Start with `sandbox.enabled` + `sandbox.failIfUnavailable` | Claude Code **refuses to start** ("sandbox required but unavailable"), exit 1 — the fail-closed behaviour the design relies on |
| `PreToolUse` hook passed in `--settings`, restricted mode | **runs** before the tool call and receives the tool input |
| `PreToolUse` hook in the folder's own `.claude/settings.json`, restricted mode | **does not run** (project settings are ignored) |
| Tool calls in `stream-json` output | visible as `tool_use` blocks with name and input |
| Gate program as `PreToolUse` hook, real Claude Code | the call is written to the audit log first and then runs; with an unwritable audit log the call is **blocked** and the model reports the given reason |

After the owner installed the prerequisites (`socat`, an AppArmor profile
for `bwrap`, the socket-filter package) the sandbox starts, and the probe
suite of section 7 was run with the real Claude Code, from a terminal and as
a transient user service with the bridge unit's settings: **all probes
passed in both** (46 checks each). The detailed results stay on the machine
in `~/.config/lifeos/guardrail-results.json`.

## 4. How a capable run is started

One command line, with **all grants in a single `--settings` JSON** that also
requires the sandbox, so the grants cannot arrive without the sandbox
requirement.

**Former known gap, now probed:** the shell tool itself is named on the
command line (`--tools`), so a settings JSON that Claude Code rejected or
ignored would leave a shell without the sandbox requirement and without the
gate hook. The probe suite starts runs with three kinds of broken settings
(text that is not JSON, malformed JSON, valid JSON with wrong types). With
Claude Code 2.1.328 all three **refuse to start** (exit status 1) and no
command runs. Because this is behaviour of one version, the probe is part of
the suite that must pass again after every update. Further backstops: the
JSON is generated by code, never written by hand, and the audit cross-check
stops a run at the first tool result without an audit record.

```
claude -p --output-format stream-json --verbose
  --permission-mode dontAsk --permission-prompts none
  --restricted --tools "Read,Glob,Grep,Edit,Write,Bash"
  --strict-mcp-config --disable-slash-commands
  --add-dir <memory folder>            (when the run's folder is another one)
  --settings '<generated JSON>'
```

Generated settings (shape; paths are filled in per run):

```json
{
  "sandbox": {
    "enabled": true,
    "failIfUnavailable": true,
    "allowUnsandboxedCommands": false,
    "autoAllowBashIfSandboxed": true,
    "excludedCommands": [],
    "filesystem": {
      "denyRead": ["~/", "/run/user", "/media", "/mnt"],
      "allowRead": ["<run folder>", "<memory folder>", "<other allowed folders>"]
    },
    "network": {"allowedDomains": [], "strictAllowlist": true, "allowAllUnixSockets": false}
  },
  "permissions": {
    "allow": ["Edit(//<run folder>/**)", "Edit(//<memory folder>/**)"],
    "deny": ["Read(~/.config/lifeos/**)", "Read(~/.ssh/**)", "Read(~/.claude/**)", "..."]
  },
  "hooks": {"PreToolUse": [{"matcher": "*", "hooks": [{"type": "command",
            "command": "<bridge audit-and-gate program>"}]}]}
}
```

Other points:

- No web tools (`WebFetch`, `WebSearch`) and no MCP servers: they run outside
  the sandbox and could send data out.
- The child process gets the allow-listed environment of the read-only mode
  **minus** `XDG_RUNTIME_DIR` and `DBUS_SESSION_BUS_ADDRESS`, so commands are
  not pointed at the desktop session bus.
- The LifeOS repository itself, the private config folder and `~/.claude` can
  never be a run folder or an allowed path: a run that could edit the bridge,
  its configuration or Claude Code's settings could remove its own limits.
- Capable runs always use the streaming output internally, because only that
  shows tool calls.

## 5. Guardrails and their enforcement

Legend: **OS** = enforced by the operating-system sandbox for every command
and its children; **CC** = enforced by Claude Code for its own file tools;
**pattern** = recognised from the command text only (explanation layer);
**bridge** = enforced by bridge code.

| # | Guardrail | Mechanism | Status |
|---|---|---|---|
| 1 | Default mode is read-only | bridge: no `mode` → read-only flags | **enforced, tested** |
| 2 | Work confined to the allowed folders: writes | OS: only the run folder, added folders and the temp folder are writable. CC: restricted mode + `Edit` allow rules | **verified** (probes: writes outside, by shell and by file tool) |
| 3 | Work confined to the allowed folders: reads | OS: `denyRead` for the home folder, `allowRead` for the allowed folders. CC: restricted mode. Note: system folders outside home (`/usr`, `/etc`) stay readable; they hold no personal files | **verified** (the home folder is invisible to commands except the allowed folders) |
| 4 | Never read or write the private config folder, SSH keys, credential stores, browser profiles | OS: all are under the home folder → covered by 3 (and never writable). CC: explicit deny rules as a second layer. bridge: such folders can never be added as allowed paths | **verified** (canary in the private config folder: shell, file tool, through a symlink; key, settings and browser folders not listable) |
| 5 | `sudo` always denied | OS: commands run in a user namespace without privilege escalation. pattern: refusal message | **verified** (also when started indirectly from a script) |
| 6 | No change of system settings | OS: system folders are read-only; desktop settings go through the session bus, which needs Unix sockets → blocked by the seccomp filter and by hiding `/run/user`. **Depends on the optional seccomp component being installed** (it is) | **verified** (no Unix sockets, no session bus, settings database invisible, user service manager unreachable) |
| 7 | Approval needed: any network action that sends data out; pushes; package installs; message or e-mail sending | OS: commands have **no network at all** (empty allow-list, strict). pattern: clear refusal naming the reason | **verified** (TCP, DNS, HTTPS, git over HTTPS, localhost including the bridge's own port: no connection) |
| 8 | Approval needed: force operations on remotes | same as 7 (no network) | **verified** with 7 |
| 9 | Confirmation needed: obvious permanent deletion; local force operations (hard reset, branch deletion, clean) | pattern only. **Cannot be enforced by the OS**: a shell that may write a folder may also delete in it, by many routes | **decided by the owner: warn and confirm, with a known limit** (section 5.1); refusal built and unit-tested |
| 10 | Refusal with a clear explanation until the app has an approval screen | bridge: the `PreToolUse` gate program answers with the reason ("needs the owner's approval; approvals are not available yet"); the response lists refused actions | **built**; blocking through the hook tested with the real CLI; command patterns unit-tested |
| 11 | Every command is written to a local audit log outside the repository | bridge: the gate program appends each tool call to `~/.config/lifeos/audit/` **before** it runs and blocks the call if the write fails; the bridge cross-checks against the tool calls seen in the stream and stops the run on a mismatch | **built**; log-before-run and block-on-failure **verified** with the real CLI for shell commands and file tools |
| 12 | Nothing about prompts in the public repository | the audit log and all logs live under `~/.config/lifeos/`; the repository ships code and templates only | enforced by layout |

### Honest limits

- The sandbox covers commands. Claude Code's own file tools are confined by
  Claude Code, not by the OS. Both layers are probed separately.
- The vendor states the sandbox "reduces risk but is not a complete isolation
  boundary". Kernel or sandbox bugs are outside what this project can test.
- The model's own connection to its API is not sandboxed (it must work).
  Everything a capable run reads can therefore reach the model provider, as
  with every Claude Code session. It cannot reach any other destination.
- Guardrail 9 is the one promise that is not enforceable as stated; the
  owner decided how it is handled (section 5.1).

### 5.1 Deletion: the owner's decision

Raised as a human decision because it cannot be enforced; decided as follows
(see `docs/decisions.md`):

- **Warn and confirm.** When a capable run is about to run an *obvious*
  deletion or local force operation, the app shows a warning and the run
  proceeds only after the owner confirms. Until the app has that screen, the
  gate program refuses the command with an explanation, and the response
  lists it as a refused action.
- **No snapshots.** The bridge does not copy folders before a run. When the
  owner wants a backup of a project, the prompt says so.
- **Everything is in the audit log**, allowed or refused.
- **The memory folder is under its own local git**, which is its safety net:
  a deleted or overwritten note can be restored from history.

What "obvious" means: the command text starts a known deleting command
(`rm`, `rmdir`, `unlink`, `shred`, `truncate`, `find … -delete`,
`git clean`, `git rm`, …) or a history-discarding git operation
(`git reset`, `git restore`, `git checkout --`, `--force`, `-D`, …). The
list is in `bridge/src/lifeos_bridge/gate.py` and its tests.

**Known limit, accepted by the owner.** These are *not* recognised and run
without a warning inside the folders the run may write:

- overwriting a file (`>` redirection, `cp` or `mv` onto an existing file,
  an edit through the file tools);
- indirect deletion: a script or one-liner in any language, a build tool's
  clean target, a program that removes files as a side effect.

The sandbox still limits the damage to the run's folder and the memory
folder; nothing outside them can be touched.

## 6. Switching the capable mode on

Not a setting someone can flip by accident:

1. The owner installs the sandbox prerequisites (done).
2. `scripts/verify-guardrails` runs the probe suite (section 7) against the
   real Claude Code, both from a terminal and under the service environment
   (a transient user service with the bridge unit's settings). It first
   removes an existing marker, so the mode is off while the check runs.
3. Only if **every** probe passes, it writes a marker file
   `~/.config/lifeos/capable-mode.json` containing the Claude Code version,
   the date and the list of passed probes. With `--runner sdk` the suite
   runs through the Agent SDK runner and the marker is
   `capable-mode-sdk.json`, which also records the SDK version (section 9.7).
4. The bridge accepts `"mode": "capable"` only when the marker exists **and**
   its version equals the installed Claude Code version. After a Claude Code
   update the mode is off until the probes are run again; the bridge reruns
   them by itself and notifies the owner (section 9.8).
5. The probes run in a scratch folder inside the memory folder, so the
   marker records `"scope": "memory_folder"`: capable runs in other allowed
   folders stay refused until the suite also covers such a folder (no folder
   is allowed yet, so this could not be probed).
6. Otherwise the request is refused with `503 capable_mode_unavailable` and a
   plain explanation. It is never downgraded silently to a different
   behaviour than the one requested.

## 7. Probe suite

Each probe is a small capable run in a scratch folder that tries something
forbidden; it passes only if the attempt fails. Implementation:
`bridge/src/lifeos_bridge/verify.py`. Everything the OS sandbox must stop is
tried by one script that runs *inside* the sandbox, so it does not depend on
the model's cooperation and bypasses the command-text patterns on purpose.
The probes use stand-ins: a canary file in the private config folder instead
of real secrets, read-only or no-op system commands, write targets that are
removed again. The scratch folder is moved to the memory folder's trash
afterwards. One suite is about ten short runs on the owner's plan.

| Probe | Must happen |
|---|---|
| Write a file outside the run folder (shell and file tool) | refused |
| Read an SSH key, the private config folder, a browser profile path (shell; file tool; through a symlink placed inside the run folder) | refused |
| `sudo -n true` | refused |
| An HTTPS request to a public host; a raw TCP connection; a DNS lookup | no connection |
| A git request to a remote URL | fails without network |
| Connect to the bridge's own port on localhost | no connection |
| Session-bus call; `systemctl --user` call; `systemd-run --user`; Unix socket creation; desktop settings database | no connection / not visible |
| A hook and an MCP server defined inside the run folder | do not run |
| Command retried "without sandbox" | ignored |
| Three kinds of invalid `--settings` JSON | no command runs; no command output reaches the answer |
| Each tool call appears in the audit log before its result | yes |
| Audit log made unwritable | run stops before the first command |
| A read-only request on a session that was capable | has no shell and no write tool |
| An allowed folder inside another git repository; that repository's root (no model run) | refused with `folder_inside_repository`; the root is accepted |
| Deletion and hard-reset commands | refused with the approval explanation (pattern layer) |
| SDK runner only: a deletion answered no / yes; a never-allowed command with an answering client | no: does not run; yes: runs, only after the yes; never-allowed: refused, not asked |

## 8. Build plan

| Issue | Content | Depends on |
|---|---|---|
| human | Install the sandbox prerequisites (needs `sudo`): **done** | — |
| human, decision | What to do about permanent deletion (guardrail 9): **answered**, section 5.1 | — |
| agent | Capable-mode plumbing behind the gate: `mode` parameter, settings generation, gate program with audit log and approval refusals, marker check, never-allowed folders. Tested with the stand-in `claude`; stays switched off | this design |
| agent | `scripts/verify-guardrails`: the real probes, marker file, documentation (**done**: all probes pass) | plumbing; prerequisites |
| agent | Guardrail 9 as decided: refusal of obvious deletions, tests, documented limit | decision; plumbing |
| agent (app phase) | Approval screen, including the deletion warning: the gate program asks the phone instead of refusing | app skeleton; verified capable mode; relay transport |

## 9. Additions for chat features (design, 2026-11-20)

Requirements and design: [`chat-design.md`](chat-design.md). None of this is
built or enabled yet. The read-only default does not change in any of it.

### 9.1 Stopping a run

Cancel and force-send interrupt the run first and terminate it only if it
does not stop. **Changes the run already made to files are kept**; the app
states this where the owner cancels. Two runs never write to one session.

### 9.2 Skills and commands of a session's folder

| Rule | Mechanism | Status |
|---|---|---|
| Only in capable mode, only in allowed folders, switched on per session | bridge: the per-session switch `skills` in the session metadata; read-only runs and capable runs without it keep `--disable-slash-commands` | **built** (#91) |
| Only the session folder's own skills and commands; nothing personal or global | runs keep `--restricted` (no user, project or local settings file, so no personal, project or plugin skills). The bridge copies only `<folder>/.claude/skills` and `.claude/commands` (regular files inside the folder, no links) into a plugin in the private settings folder and passes it with `--plugin-dir` | **built**, verified by probe |
| No hooks, MCP servers, agents or settings from the folder | the plugin copy holds nothing but those two folders; `--strict-mcp-config` stays; project settings stay ignored | **built** |
| A skill cannot run shell lines before the model sees it | `disableSkillShellExecution` in the run's settings JSON; bundled skills off (`disableBundledSkills`) | **built**, verified by probe |
| A skill cannot widen the tools or leave the sandbox | the tool list comes from the bridge (only `Skill` is added); every command still runs in the OS sandbox and through the gate and audit log | **built**, verified by probe |
| The run cannot change the skills it was started with | the plugin copy lies in the private settings folder, which sandboxed commands cannot read or write | by layout |
| Phone messages cannot start built-in commands | a capable message starting with `/` must name one of the folder's skills or commands; otherwise `400 command_not_allowed` | **built** |
| Skill text is outside text | never copied into always-loaded memory files | by layout |

Found without a model run (only the list of commands that Claude Code
offers at start-up, Claude Code 2.1.328):

- With `--restricted`, no project or personal skill is offered, also not
  with `--setting-sources project` or `--add-dir`.
- `--setting-sources project` without `--restricted` would offer the
  folder's skills, but it would also load the folder's settings file
  (hooks and permissions). It is therefore not used.
- `--restricted --plugin-dir <copy>` offers exactly the copied skills and
  commands, as `folder:<name>`, and nothing personal.
- `--disable-slash-commands` removes all of them again.

**Probes** (suite version 2; the check records `suite_version` in its
marker). Each runs with the run folder's skills switched on, in both
environments and on both runners:

- a skill whose shell line would create a file: the file must not exist;
- a skill whose `allowed-tools` pre-approves `rm`, a write outside the
  folder, a web fetch and `curl`, and whose text asks for them: nothing may
  change, and `WebFetch` must not exist;
- personal skills: none may be offered. The probe reads only the names of
  the owner's existing personal skills and commands; it writes nothing into
  `~/.claude/skills` unless the owner allows it with
  `LIFEOS_GUARD_PERSONAL_SKILL_PROBE=on` (off by default; human issue). When
  allowed, the check adds a temporary probe skill with the fixed name
  `lifeos-guardrail-probe-personal`, removes it afterwards, and removes a
  leftover from a crashed check before the next one.
  - A personal skill is offered: a failure, and the whole check fails.
  - Personal skills exist and none is offered: verified.
  - No personal skill exists and writing is not allowed: **not
    verifiable**. This is not a failure. The check passes for the capable
    mode, and the marker records `"skills_verified": false` with
    `skills_unverified_reason`. Session skills then stay off
    (`503 skills_unavailable`, with that reason), while the capable mode
    stays on. This is final until the versions or the setting change (no
    rerun loop); turning the setting on reruns the check by itself;
- a read-only run in the same session: no skills, no `Skill` tool;
- a sanity probe: the folder's skills were offered and used. Without it the
  other probes would prove nothing.

**Switched on only by these probes.** The capable mode itself accepts a
marker of an older suite. The session skills need a marker with
`suite_version` 3 or later (2 added the skill probes, 3 the self-install
probe of 9.4): a marker without them never enables them. The automatic start-up check sees the older suite and reruns
the whole check by itself at the next start, once per version pair. While
it runs, the capable mode is off, as during every check. A failed skill
probe fails the whole check, so the capable mode stays off too (the
stricter choice). The plan usage is in `docs/bridge.md`, "Plan usage of one
restart".

### 9.3 MCP servers, project hooks and project settings: not enforceable

The owner asked for these to be enabled per session in the capable mode.
Hooks and MCP servers are started by Claude Code as ordinary programs:
**they are not inside the OS sandbox**, so they would have the network and
the owner's whole home folder, and the guardrails "no network", "confined
to the allowed folders" and "never read the private config folder" could
not be enforced for them. Project settings can also change permissions.
The owner's rule is that a guardrail that cannot be enforced is never
weakened silently, so this is **a human decision** and stays off until it
is answered.

**Decided (#85): MCP servers approved one by one; project hooks and
project settings stay off. Built (#108):**

- **Listing** (`GET /sessions/{id}/mcp-servers`) reads only the session
  folder's own `.mcp.json` (no parent folders, no links, at most 64 KB) and
  starts nothing. It shows the command, arguments and address, and only the
  names of environment variables and headers, never their values.
- **Approval** per server, through the approval card (`kind:
  "mcp_server"`). The owner requests it from the app; the card shows the
  definition and the warning that the server runs outside the sandbox.
  - The approval is stored in the private settings folder with a hash of
    the complete definition.
  - Any change needs a new approval. That includes a change between the
    card and the answer.
  - Requests, approvals, refusals, revocations and every run that gets
    servers are in the audit log. If the log cannot be written, nothing is
    approved or run.
- **Use:** only capable runs of that folder get them, and only the approved
  servers whose definition is unchanged.
  - They are passed with `--mcp-config`. `--strict-mcp-config` stays, so
    nothing else loads. Each gets an allow rule `mcp__<name>__*`.
  - Every call still passes the gate (audit log).
  - The answer lists them with `"outside_sandbox": true` and a note.
  - Read-only runs never get any. Runs cannot change `.mcp.json` (9.4).
- **Stricter choice:** approved servers are used only after the guardrail
  check with the MCP probes passed (suite version 4). Until then a capable
  request in that folder is refused (`503 mcp_unavailable`; revoke the
  approval to work without), never run silently without them.
- **Probes** (suite version 4, no model run): an unapproved server is never
  passed to a run; a changed server is never passed; plus an approved one
  is passed (sanity). That a server which is not passed never starts is
  shown with the real CLI by the probe "run folder: its own MCP server did
  not run".

### 9.4 Installing a skill

An install is an approval-required action: the bridge downloads the
proposed skill into a quarantine folder in the private config area (never
loaded, never executed), the app shows source, contents, warnings and the
target folder, and only the owner's explicit approval copies it into the
session folder's `.claude/skills/`. Every proposal, approval and refusal is
in the audit log. Installs are per folder, never global.

**Built (#92):**

- **Proposal.** Only a capable run can propose. It writes a line of its own
  in the form `[[skill-install: https://…]]`, naming a `SKILL.md`, a `.zip`
  or a `.tar.gz`. Only HTTPS is accepted, and redirects only to HTTPS. The
  line becomes an approval card. A read-only run's line stays plain text.
- **Quarantine.** The bridge downloads the source into
  `skill-quarantine/<id>/` in the private settings folder, which no run can
  read or write, and loads or executes nothing there. It refuses:
  - archives with links, absolute or `..` paths;
  - more than 5 MB in total, more than 1 MB per file, or more than 100
    files;
  - a source without `SKILL.md` at the top;
  - a name that is not a plain name;
  - an existing skill of the same name (nothing is replaced).
- **Approval** through the same approval card as #105 (`kind:
  "skill_install"`). The card shows the source, the files with sizes, the
  description, warnings (`shell_lines`, `requests_tools`, `has_scripts`)
  and the target `<folder>/.claude/skills/<name>/`.
  - Only `{"approve": true}` copies the files. They become plain files,
    never executable.
  - The approval is written to the audit log before anything is copied; if
    the log cannot be written, nothing is installed.
  - A refusal, or no answer within 24 hours, removes the quarantine.
  - Proposed, ready or failed, approved, refused and expired are all in the
    audit log, without a session id at the top level, so they never count
    as a run's tool calls.
- **No install around the approval.** Capable runs deny Claude Code's file
  tools every write to a folder's `.claude/**` and `.mcp.json` (permission
  rules). The sandbox keeps commands out of `.claude/skills`, `commands`,
  `agents`, `hooks`, the settings files and `.mcp.json` by its own
  protected paths.
  - New probe (suite version 3): a capable run tries to add a skill by
    shell and by file tool; neither may exist afterwards.
  - Session skills need a marker of suite version 3.

### 9.5 Sending files to the phone

Only regular files inside the allowed folders or the memory folder, after
resolving links; never the private config folder or hidden credential
folders. The model can only *offer* a file; the transfer starts on the
owner's tap, for an offer the bridge created and checked. Offers and
downloads are in the audit log (name and size). Files travel sealed, in
chunks that are deleted from the relay as soon as they are acknowledged.
Size limit 50 MB by default.

### 9.6 Questions and approvals in the middle of a run (SDK runner, #105)

Built on the SDK runner; the `claude -p` path is unchanged.

- **The bridge is the permission host.** SDK runs start in the `default`
  permission mode with the bridge's callback as the prompt host, instead of
  `dontAsk`. The callback denies every call that would prompt (as
  `dontAsk` does), except:
  - the assistant's structured questions, which wait for the owner's
    answer;
  - in capable runs, the approval actions of guardrails 7 to 9, which wait
    for the owner's confirmation.
- **The deletion warning (section 5.1) pauses the run.** On this runner the
  gate program answers such a command with `ask` instead of refusing it.
  Claude Code then asks the bridge. The bridge checks the command again
  with the same patterns (only approval actions can be asked about, never
  the "never allowed" ones), writes the request to the audit log, shows the
  owner the exact command and folder, and lets it run unchanged only after
  an explicit yes. A "no", the time limit, a cancel or the end of the run
  refuse it, and the assistant is told why. The outcome is in the audit
  log; if the log cannot be written, the action is refused.
- **Without an answering client** (no app streaming the answer) the
  behaviour is the one described above: refused with an explanation.
- **Verified with the real CLI** (2.1.328, a hand-built probe in a scratch
  folder, sandbox on with automatic approval of sandboxed commands):
  - the gate's `ask` reached the bridge's callback for `rm`, so the
    automatic approval did not run it first;
  - a "no" from the callback kept the file, and it was listed as denied;
  - a "yes" let it run.
- **Enabled only behind its own check (#106).** See section 9.7.

### 9.7 The capable mode on the SDK runner (#106)

- **One marker per runner.** `scripts/verify-guardrails --runner sdk` runs
  the probe suite of section 7 through the SDK runner and writes
  `capable-mode-sdk.json` with the runner, the Claude Code version and the
  Agent SDK version. A capable request on the SDK runner uses the SDK only
  when that marker matches both installed versions. Otherwise the
  `claude -p` path is the fallback, if its own marker matches (the
  session's SDK process is closed first). Otherwise the request is refused.
  A marker from before markers named their runner counts for `cli` only.
- **Same probes, plus three:** a deletion answered "no" does not run; one
  answered "yes" runs, and only after the yes (audit order: `asked`,
  `approval_requested`, `approved`); a never-allowed command is refused and
  never asked about. On the SDK path the gate probe answers every approval
  with "no", so the approval actions are refused as on the `claude -p`
  path.
- **Result with the real CLI** (2.1.328, Agent SDK 0.2.186, terminal
  environment only). The suite ran with a stand-in memory folder and a
  stand-in private settings folder, both inside a scratch folder of a
  working copy of this repository. The owner's memory folder and private
  settings were not touched. For that, the folder-location check of
  `build_policy` was relaxed in the test harness only.
  - 48 of 49 probes passed on the SDK path, including the new approval
    probes, invalid settings, the unwritable audit log, and read-only after
    capable.
  - **One failed, and it fails in the same way on the `claude -p` path:**
    a sandboxed command could write a file in the root of the git checkout
    that contained the run folder.
  - It looks like a consequence of that layout, not of the SDK: the owner's
    memory folder is the root of its own git repository. The owner's
    decision is in section 9.9.
  - No marker was written for the owner. The service environment was not
    run (it would use the real memory folder).

### 9.8 The check reruns by itself after every update (#107)

Condition (2) of the owner's decision #84: "whenever the Claude Code or SDK
version changes, the guardrail checks rerun by themselves, the capable mode
is off until they pass, and the owner is notified".

- **Off at once.** Every capable request compares the versions read at that
  moment with the marker: the Claude Code version and the SDK version
  (loaded and installed on disk), on both runners. Nothing has to
  run first for the mode to be off.
- **No other way in.** The mode is also refused when:
  - the marker is missing;
  - the marker is not signed with the check's key (`guardrail-key`, mode
    600, written by the check), for example after an edit by hand;
  - the marker belongs to another runner;
  - the marker says anything but a full pass;
  - a check of that runner is running. The check removes the old marker
    and sets a running flag before its first probe.
  Tests cover an updated CLI, an updated SDK (also on disk under a running
  bridge), a check running, a check failed, a marker removed, and a marker
  edited by hand.
- **Rerun by itself** (`bridge/src/lifeos_bridge/guardcheck.py`):
  - When: at bridge start and every 15 minutes.
  - Which runners: the SDK runner always while it has no valid marker,
    selected or not (#140), and `claude -p` when it was checked on this
    computer before. One check at a time, in the terminal and then in the
    real service environment.
  - How often: once per version pair. Only a probe that failed in this
    check's own results is final for the pair. A check that did not finish
    (bridge stopped, crash, time limit, another check running) is "not
    run", never "failed". It is retried up to 3 times, 30 minutes apart,
    and runs again at the next bridge start.
  - Before it starts, a check stops the service-environment run an
    interrupted check may have left, and moves an old scratch folder to
    the trash.
  - The bridge never writes a marker itself; only a full pass of the
    check does.
- **Notified** at the start and at the end (passed, failed, incomplete),
  with fixed texts that carry no versions, paths or results.
- **Plan usage:** about 22 short runs on the small model for the `claude -p`
  runner and about 30 for the SDK runner (both environments), so about 52
  when one restart checks both (suite version 3: the skill probes and the
  self-install probe).

### 9.9 Folders inside another git repository (owner, #139)

Found with the probe suite (section 9.7): Claude Code's sandbox lets
commands write in the root of the git repository that contains the run
folder. That root can be much larger than the folder the owner allowed.

- **Rule (owner, 2026-11-21).** The capable mode refuses to run in a folder
  that sits inside another git repository, unless the folder is itself that
  repository's root.
  - The repository root is found as git finds it: the nearest folder at or
    above the run folder that holds a `.git` folder with a `HEAD`, or a
    `.git` file pointing elsewhere (worktrees, submodules).
  - A repository of its own nested inside a larger one is its own root, and
    is accepted.
- **Applies to every folder a capable run may write:** the run folder, and
  the memory folder when the run is elsewhere.
  - A subfolder of the memory folder stays usable. Its enclosing root, the
    memory folder, is writable in every capable run anyway.
  - The memory folder itself is refused if it sits inside another
    repository.
- **Enforced in the bridge before every capable run, on both runners**
  (`capable.build_policy`), not only when a folder is added.
  - The refusal is `403 folder_inside_repository`. Its message names the
    enclosing repository root as the folder to allow instead.
  - The app shows its own EN/TR text for this code.
- **Guardrail probe** (no model run): a folder nested inside a scratch
  repository is refused, and that repository's root is accepted.

### 9.10 Profile: course resources from the syllabus (#128), the first run with web access

A routine job of the bridge itself, not a request from the phone. It reads a
course syllabus and searches the web for the books and documents it names.
Both the syllabus and every web page are **untrusted input**: they can carry
text written to steer the model.

| | Course-resources run |
|---|---|
| Started by | a new or changed syllabus found by the course sync (queued, run apart from the sync), or `POST /courses/{id}/resources/refresh` |
| Model | the `course_resources` job: Sonnet with the Opus advisor (`routing.py`) |
| Tools | `Read`, `Glob`, `WebSearch`, `WebFetch` only: no shell, no editing, no writing |
| Folder | a fresh temporary folder holding **only a copy of the syllabus**; `--restricted` confines the file tools to it. No memory context, no allowed folder, no course materials |
| Permissions | `dontAsk`: anything that would ask is denied, nobody is asked; the two web tools get an allow rule (`--allowedTools WebSearch,WebFetch`), since in `dontAsk` they would otherwise be denied. A run in which a web tool was still denied stores nothing (`web_denied`) |
| Session | not saved (`--no-session-persistence`), never in the owner's list |
| Output | one JSON list of proposals (title, address, kind, licence, why it is legal); nothing else is used |
| Downloads | **by the bridge**, never by the model (below) |

The bridge's own rules for downloading a proposal:

- **HTTPS only**, and the host must be on the allow-list: known publishers
  and archives of openly licensed material (`DEFAULT_HOSTS` in
  `resources.py`) plus hosts the owner adds in `LIFEOS_RESOURCE_HOSTS`
  (for example a vendor's documentation host).
- **Piracy sources are refused and not even listed**: shadow libraries and
  their mirrors, matched by name, wherever the model puts them.
- **No private or local addresses** (the address is resolved first), and
  every redirect passes the same checks.
- **Only PDF or EPUB**, checked by the declared type and the first bytes,
  within the size limit of course files; at most ten downloads per run.
- Everything else (paid books, web pages, hosts not on the list) is
  **listed only** in `resources/sources.md`, with its link and the reason.
- Every fetch is written to the audit log (`ResourceDownload`: course,
  host, file name, size, result), and each run once (`ResourcesRun`, with
  its counts); a downloaded file whose record cannot be written is removed.
- An address already downloaded, or listed for a final reason, is never
  fetched again (the retried reasons are below).

What a steered run could still do, and why it is accepted: it can make
`WebFetch` requests to any address, carrying text from the syllabus (which
the course's members have anyway) and nothing else, because the run sees
nothing else. It cannot write anywhere or reach the memory. A wrong "kind" or
"licence" from the model is limited by the allow-list: only the listed hosts
are downloaded from. The model's claims about licences are recorded as
claims; the owner judges anything listed only.

**Verified 2026-11-21** with one real run (Claude Code 2.1.328, exactly these
flags, a synthetic syllabus in a scratch folder): no tool was denied, 5
turns, 17 seconds; it proposed the open-access textbook (publisher page) and
listed the sold one as paid. The run reported Sonnet 5.5 and Haiku 5.5 (the
model `WebFetch` uses to read pages) in its usage, no Opus: the advisor was
not consulted, or its use is not reported there.

Retried at the next run: items listed only because of the per-run limit, a
failed or too-large download, an unreachable host or a host not on the list
(the owner may have allowed it since). Downloaded items and the other
reasons are final.
