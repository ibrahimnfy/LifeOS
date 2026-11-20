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
| Web tools | none | none |
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
   the date and the list of passed probes.
4. The bridge accepts `"mode": "capable"` only when the marker exists **and**
   its version equals the installed Claude Code version. After a Claude Code
   update the mode is off until the probes are run again.
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
| Deletion and hard-reset commands | refused with the approval explanation (pattern layer) |

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
| Only in capable mode, only in allowed folders, switched on per session | bridge: a per-session switch; without it runs keep `--disable-slash-commands` | to build |
| Only the session folder's own skills and commands; nothing personal or global | bridge passes only that folder; user-level settings, skills and plugins stay excluded | to build, **to verify by probe** |
| A skill cannot run shell lines before the model sees it | the documented setting that disables shell execution in skills | **to verify by probe** |
| A skill cannot widen the tools or leave the sandbox | the tool list comes from the bridge; every command still runs in the OS sandbox and through the gate and audit log | **to verify by probe** |
| Skill text is outside text | never copied into always-loaded memory files | by layout |

New probes before this is switched on: a skill in the run folder whose
shell line would create a file (must not run); a skill that pre-approves a
web tool and a write outside the folder (must change nothing); a personal
skill in the home folder (must not be listed or usable); a read-only run in
the same session (no skills at all).

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

### 9.4 Installing a skill

An install is an approval-required action: the bridge downloads the
proposed skill into a quarantine folder in the private config area (never
loaded, never executed), the app shows source, contents, warnings and the
target folder, and only the owner's explicit approval copies it into the
session folder's `.claude/skills/`. Every proposal, approval and refusal is
in the audit log. Installs are per folder, never global.

### 9.5 Sending files to the phone

Only regular files inside the allowed folders or the memory folder, after
resolving links; never the private config folder or hidden credential
folders. The model can only *offer* a file; the transfer starts on the
owner's tap, for an offer the bridge created and checked. Offers and
downloads are in the audit log (name and size). Files travel sealed, in
chunks that are deleted from the relay as soon as they are acknowledged.
Size limit 50 MB by default.
