# Permission policy for prompts from the phone

Status: **built and verified on the owner's machine** for Claude Code
2.1.328. The read-only mode is the default. The capable mode is switched on
by `scripts/verify-guardrails` only while every probe of section 7 passes
for the installed Claude Code version; so far for runs in the memory folder
(section 6) and in a blank chat's own folder (section 9.11).

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

The run's folder is a new folder of its own for a blank chat (section
9.11), the folder named in the request for another new conversation, or
the session's original folder for a resumed session. It must be a blank
chat's folder, or lie inside the memory folder or a path from
`~/.config/lifeos/allowed_paths`.

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
  --add-dir <memory folder>            (when the run's folder is another one,
                                        a blank chat's folder included)
  --plugin-dir <copy> --add-dir <copy>  (per copy of skills, section 9.2; then
                                         without --disable-slash-commands)
  --add-dir <conversation index>       (read-only, section 9.11)
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

- No web tools (`WebFetch`, `WebSearch`): they run outside the sandbox and
  could send data out. MCP servers run outside the sandbox too; a run gets
  only those the owner approved one by one (section 9.3).
- Skills reach a run only as copies made by the bridge, passed with
  `--plugin-dir`; `--disable-slash-commands` is then left out and the `Skill`
  tool is added (section 9.2). Every capable run gets the copy of the owner's
  personal skills, when there are any.
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
   is allowed yet, so this could not be probed). A blank chat's folder is
   probed by its own part of the suite; the marker records that as
   `"chat_folders": true` (section 9.11).
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
| SDK runner only: a confirmed command that asks to leave the sandbox, aimed at a file outside the sandbox | the file stays |
| A skill of the run folder with a shell line, and one that approves tools for itself (session switch on) | the shell line does not run; nothing changes; no `WebFetch` |
| A personal skill (a scratch one of the check's own), started by a `/` message with the session switch off: a shell line; hooks and self-approved tools in its frontmatter; a deletion, a write outside, `curl`, a read of the private settings canary | offered and used; the shell line and the hooks do not run; nothing changes; every command is in the audit log |
| The session switch on | the personal skills are offered next to the folder's own |
| A read-only run in a session whose capable run had skills | no skill of the folder, no personal skill, no `Skill` tool |
| A capable run tries to add a skill to its folder (shell and file tool) | nothing is added |
| The copies of the skills a run was started with (personal and the folder's): a script of a skill, started as a sandboxed command, reads the supporting files and tries to create, append, make writable, rename and delete in the copies; the file tools read a supporting file and try to write into each copy; the script also tries the private settings canary and a copy the run was not given | readable both ways; nothing changed (also judged from the copies themselves, for every copy of the check); the script's command is in the audit log; the canary and the other copy are not readable |
| MCP server of the run folder, and a personal one (scratch file of the check): unapproved; approved; changed after the approval (no model run) | passed only while approved and unchanged; secret values are neither shown nor on the command line |
| A blank chat's folder as the run folder: the chat root and another chat (shell, file tools, read-only), the gate, a `CLAUDE.md` in the folder | see section 9.11 |

**Probes that need the model to make a particular call** (the SDK
approval probes, and since #162 the run that starts the personal skill) are
retried up to 3 times within the check when the call is not made. If it
never is, the probe is **inconclusive**: neither passed nor failed. No marker is written, and the automatic check counts the check
as "not run" and retries it (section 9.8). Every such probe's detail lists
what was observed per try: the shell calls (marked when they asked to leave
the sandbox) and the audit decisions.

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

### 9.2 Skills and commands: the owner's personal ones and the session folder's own

**Rule since #162 (owner's decision #149):** capable runs may use the
owner's personal skills. This replaces "nothing personal or global in a
capable run" (#91) for skills only. Nothing else is loosened: commands
still have no network, installs still need approval, project hooks and
project settings stay off, and runs keep `--restricted`, so no user
settings, hooks, plugins or permissions are loaded.

| Rule | Mechanism | Status |
|---|---|---|
| Only in capable mode, only in allowed folders | bridge: read-only runs keep `--disable-slash-commands` and never get the `Skill` tool | **built** (#91), verified by probe |
| The owner's personal skills and commands: in every capable run, without a switch | bridge: `~/.claude/skills` and `~/.claude/commands` of the user the bridge runs as are copied into a plugin (`personal:<name>`) in the bridge's folder for copies and passed with `--plugin-dir`. Only after the guardrail check passed with the personal-skill probes on that runner (suite `cli` 5, `sdk` 6); with an older marker a run gets none, as before | **built** (#162), verified by probe |
| The session folder's own skills and commands: only when switched on for the session | bridge: the per-session switch `skills` in the session metadata (they can come from a cloned project). Copied the same way into a second plugin (`folder:<name>`) | **built** (#91), verified by probe |
| Nothing else personal or global | runs keep `--restricted` (no user, project or local settings file, so no personal, project or plugin skills by themselves, no user hooks, permissions or plugins). The copies hold regular files of those two folders only: no links, no hidden folders, no hooks, MCP servers, agents or settings; `--strict-mcp-config` stays | **built** |
| A skill cannot run shell lines before the model sees it | `disableSkillShellExecution` in the run's settings JSON whenever a copy is passed; bundled skills off (`disableBundledSkills`) | **built**, verified by probe (both kinds, also for a skill started by a `/` message) |
| A skill cannot start programs outside the sandbox (hooks in its frontmatter) | the bridge writes the frontmatter of every Markdown file in a copy itself: only `name` (the folder's name), `description`, `when_to_use`, `argument-hint`, `arguments`, `disable-model-invocation` and `user-invocable` are kept, each as plain quoted text. `hooks`, `allowed-tools`, `model`, `context`, `agent`, `shell` and anything else are dropped, however they are spelled | **built** (#162), verified by probe |
| A skill cannot widen the tools or leave the sandbox | the tool list comes from the bridge (only `Skill` is added); `allowed-tools` is dropped from the copy; every command still runs in the OS sandbox and through the gate and audit log; file tools stay confined by restricted mode | **built**, verified by probe |
| A run can read the supporting files of its skills (#171) | the copies lie in a folder of the bridge's own, outside the private settings folder (`LIFEOS_SKILL_COPIES_DIR`, by default `~/.local/share/lifeos/skill-copies`). The run's own copies, and no other, are added folders for the file tools (`--add-dir`) and in the sandbox's `allowRead`. The bridge executes nothing of a copy: a script in it runs only when the run starts it as a sandboxed command, through the gate and the audit log | **built** (#171), verified by probe |
| The run cannot change the skills it was started with | sandbox: `denyWrite` for the run's copies; file tools: a deny rule `Edit(//<copy>/**)` and no allow rule; files are read-only (a script keeps its execute bit), folders are the owner's only. A copy that lies in the private settings folder, in a git repository, or inside or around a folder a run works in, is refused (`503 skills_unavailable`). The personal folder itself stays hidden from every run | **built** (#171), verified by probe |
| Phone messages cannot start built-in commands | a capable message starting with `/` must name one of the offered skills or commands (the folder's own wins a shared name; `personal:` and `folder:` name one exactly); otherwise `400 command_not_allowed`. Since #162 this holds in every capable run that is offered skills, not only with the switch on | **built** |
| Skill text is outside text | never copied into always-loaded memory files | by layout |

Found with the real CLI (Claude Code 2.1.328, scratch folders):

- With `--restricted`, no project or personal skill is offered by itself,
  also not with `--setting-sources project` or `--add-dir`.
- `--setting-sources project` without `--restricted` would offer the
  folder's skills, but it would also load the folder's settings file (hooks
  and permissions). It is therefore not used.
- `--restricted --plugin-dir <copy> --plugin-dir <copy>` offers exactly the
  copied skills and commands, as `folder:<name>` and `personal:<name>`.
  `--disable-slash-commands` removes all of them again.
- **A skill's frontmatter `hooks` do run** when the skill is started by a
  message that begins with `/`: three hook events fired, as ordinary
  programs. Seen with a plugin copy under `--restricted` in a read-only
  test run, not under the full capable settings. The folder's own skills
  (#91) reached runs through the same kind of copy, with the original
  frontmatter, and no probe covered a skill started by a `/` message. It is
  why the copies no longer carry the original frontmatter. With the
  rewritten frontmatter, under the full capable settings and on both
  runners, the probe skill ran without its hooks.
- `allowed-tools` in a skill started that way does pre-approve tools inside
  the working folders (a write was made in a run that had no allow rule for
  it), but not outside them: restricted mode refused the write and the read
  outside. The copies drop it anyway.
- An `@file` reference in a skill includes a file inside the working folder
  and not one outside it.
- A skill with `disable-model-invocation: true` is still started by a `/`
  message, on both runners.

**Honest limits of personal skills:**

- A skill's supporting files are read from the copy (#171), not from the
  original: the original personal folder stays hidden. A script of a skill
  runs as every command of a run does: in the sandbox, without network, and
  it can write only where the run can.
- Links are never followed. A personal skills folder that is itself a link,
  or a skill that is a link, is not offered.
- What the frontmatter asked for beyond text (a model, a subagent, tools) is
  ignored, so such a skill behaves like its text alone.
- Limits as for the folder's own: 500 files, 1 MB per file, 20 MB in all.
- With the copies, Claude Code also lists its own built-in commands. The
  `/` rule above keeps phone messages from starting them.

**Probes** (the check records `suite_version` in its marker). In both
environments and on both runners:

- the folder's own skills, with the switch on (suite version 2):
  - a skill whose shell line would create a file: the file must not exist;
  - a skill whose `allowed-tools` pre-approves `rm`, a write outside the
    folder, a web fetch and `curl`, and whose text asks for them: nothing may
    change, and `WebFetch` must not exist;
  - a sanity probe: the folder's skills were offered and used;
- the owner's personal skills (#162; suite `cli` 5, `sdk` 6). The probes use
  **a scratch personal-skills source of their own** in the private settings
  folder (the check points the bridge's setting at it), so the check never
  reads or writes the owner's real `~/.claude/skills`. One capable run with
  the session switch **off**, started like a message from the phone
  (`/lifeos-guardrail-probe`). The scratch skill has a shell line, and its
  frontmatter asks for hooks and for tools:
  - (d) sanity: the run is offered `personal:lifeos-guardrail-probe` (not
    offered is a failure) and the skill was used: its first step was done,
    it made a command, it tried the deletion and it read its supporting
    file. When the model does not do the steps, the run is repeated, at
    most 3 runs; what never came is inconclusive (section 7), never failed
    and never passed. What must not happen is judged over every try;
  - (b) the shell line does not run;
  - (c) its hooks do not run; the file it tries to delete survives and
    `WebFetch` does not exist; no file is written outside and the canary of
    the private settings folder is not in any output; every command it made
    is in the audit log;
  - with the switch on (the folder's run above), the personal skills are
    offered next to the folder's own;
  - (a) a read-only run in a session whose capable run had both: no skill
    of either kind, no `Skill` tool.
- Since #162 every capable run of the suite is started with what the bridge
  itself would start it with (`skills.for_run`), so all other probes also
  run with the personal copy present, as every capable run does.

**The copies: readable by the run, never writable (#171).** Until #171 the
copies lay in the private settings folder, so only a skill's text reached a
run.

*Probes* (same suite versions; no model run of their own):

- Five steps are part of the run of 9.4 that tries to add a skill, which now
  has the folder's skills switched on, so it is given both copies:
  - a script of the scratch personal skill is started as a sandboxed
    command. Inside the sandbox it reads a supporting file of each copy and
    tries to change each copy (create a file, append, make a file writable
    and append, make a folder, rename, delete), and tries to read the canary
    of the private settings folder and a copy the run was not given;
  - the file tools read a supporting file of each copy;
  - the file tools try to write a file into each copy.
- Must hold: both kinds of reading work; the script's command is in the
  audit log; nothing was changed by command or by file tool; the canary and
  the other copy are not readable; no copy any probe run was given has
  changed (judged from the copies themselves); the copies lie outside the
  private settings folder.
- The run that starts the scratch personal skill (above) also reads the
  skill's supporting file in the folder Claude Code puts in place of
  `${CLAUDE_SKILL_DIR}`: the path a real skill uses.
- **A probe passes only when what it guards against was tried.** Reading
  must have happened; "the file tools cannot change a copy" needs the two
  write attempts; "tools a personal skill approves for itself change
  nothing" needs a command that deletes the file (looking at it does not
  count). Steps the model did not do are asked for again (at most 3 runs);
  what never came is inconclusive (section 7), never passed.

*Seen with the real CLI* (2.1.328; scratch settings, memory and personal
sources). What was run where:

- **Readable, both runners.** With the copies in a folder inside the home
  folder (which is hidden from commands; the scratch settings folder beside
  it): a sandboxed command and the file tools read the supporting files of
  both copies; the script ran as a sandboxed command and is in the audit
  log; the canary and the copy the run was not given were not readable. All
  copy probes passed on `claude -p` and through the SDK.
- **Not writable, both runners, with a reservation.** In those runs every
  attempt to change a copy failed, by command and by file tool ("denied by
  your permission settings"). That scratch folder lay inside a `.claude`
  folder of a git working copy, which Claude Code protects by itself, so
  these runs alone do not show which layer held.
- **Which layer holds: controls on `claude -p` only**, with the copies in
  the temporary folder:
  - the copies opened on purpose (an allow rule, the copies not loaded as
    plugins, the bridge's protection removed): the script changed the
    copies and the file tool wrote into them, and the probes failed. So the
    probes do not pass by themselves, and read-only files alone stop
    little;
  - the same opening, but the bridge's `denyWrite` and deny rule kept:
    nothing could be changed. These two hold by themselves;
  - Claude Code 2.1.328 also refused writes into a folder it had loaded as
    a plugin, by command and by file tool, even with an allow rule and
    without the bridge's rules. The bridge does not rely on that.
- **A started skill reaches its supporting file** through
  `${CLAUDE_SKILL_DIR}` (`claude -p` and the SDK).
- **Not run:** the default folder `~/.local/share/lifeos/skill-copies`
  itself (the agent may not write there), the service environment, and the
  full suite. The first full check on the owner's computer is the first run
  in the real place.

*Where the folder for copies may lie.* In the home folder (else a copy of
another session would be readable, and that probe fails), and outside the
private settings folder, the allowed folders, the memory folder and every
git repository: the sandbox lets commands write in the root of a repository
that holds a folder the run works in (section 9.9), and a copy is an added
folder of the run. Anything else is refused with `503 skills_unavailable`.

The setting `LIFEOS_GUARD_PERSONAL_SKILL_PROBE`, the temporary probe skill
in `~/.claude/skills`, the "not verifiable" outcome and the marker fields
`skills_verified`, `skills_unverified_reason` and `personal_skill_probe`
existed only for the old rule and are gone.

**Switched on only by these probes.** The capable mode itself accepts a
marker of an older suite. The session folder's skills need a marker with
`suite_version` 3 or later (2 added the skill probes, 3 the self-install
probe of 9.4). The personal skills and personal MCP servers need `cli` 5 or
`sdk` 6: the SDK runner's suite 5 still proved the old rule. The automatic
start-up check sees the older suite and reruns the whole check by itself at
the next start, once per version pair. While it runs, the capable mode is
off, as during every check. A failed skill probe fails the whole check, so
the capable mode stays off too (the stricter choice). The plan usage is in
`docs/bridge.md`, "Plan usage of one restart".

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
  names of environment variables and headers, never their values. Since
  #162 it also lists the owner's personal servers; see below.
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
  - They are passed with `--mcp-config`, as a file in the private settings
    folder (mode 600; since #162, because definitions can hold secrets and a
    command line can be read by other programs). `--strict-mcp-config`
    stays, so nothing else loads. Each gets an allow rule `mcp__<name>__*`.
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

**Personal (user-scope) MCP servers (#162, owner's decision #149): once
approved, in every capable run.**

- **Where they are read from.** The official Claude Code documentation
  (MCP page, "Storage by scope"; and the page on the `.claude` directory)
  says user-scope servers are stored in `~/.claude.json`. A test with the
  real CLI and a scratch configuration folder (`claude mcp add --scope
  user`) showed them under the top-level key `mcpServers` of that file, and
  local-scope servers under `projects.<path>.mcpServers`. The bridge reads
  **only the top-level key**, of the user it runs as: nothing of the
  per-project entries, no claude.ai connectors, no plugins. The file is
  never written. A link is not followed.
- **Only plain definitions count:** `stdio` (`command`, `args`, `env`) and
  `http` (`url`, `headers`), plus `timeout`. A definition with anything
  else (a helper command for headers, sign-in settings, another transport)
  holds things the approval card does not show. It is listed as
  `unsupported` and can never be approved or passed on. Since #171 this
  holds for a folder's `.mcp.json` as well: before, a folder's server with a
  `headersHelper` (a program Claude Code runs) could be approved without the
  card showing it. An approval of such a definition given earlier passes
  nothing on any more.
- **The same approval card and hash** as for a folder's server (`scope:
  "personal"` on the card and in the list). The approval is stored with the
  hash of the complete definition, so a change of anything, a secret value
  included, needs a new approval; so does a change between card and answer.
  It is approved once, for every folder. Approvals are kept per personal
  file, so the scratch file of the guardrail check can never approve or
  revoke a real server.
- **Values of environment variables and headers are never shown or
  logged:** the list, the card and the audit log carry names only; the run
  gets the definition through the private `--mcp-config` file.
- **Same name in both places:** two servers with two approvals. Neither
  approval stands in for the other. When both are approved, a run in that
  folder gets the folder's own.
- **Use:** a capable run gets the approved, unchanged personal servers
  after the guardrail check passed with their probes on that runner (suite
  `cli` 5, `sdk` 6); with an older marker it gets none. Everything else is
  as for a folder's servers: `--strict-mcp-config`, allow rule per server,
  gate and audit log for every call, listed in the answer with their
  scope, never in read-only runs.
- **Probes** (no model run; a scratch personal file of the check's own): an
  unapproved personal server is never passed; an approved one is passed,
  and a server of another scope in the same file is not (sanity); a changed
  one is never passed; a secret value is neither in the list or the card
  nor on the command line.
- **Not verified:** a remote personal server that needs a sign-in kept by
  Claude Code, and `${VAR}` references in a definition (the run's
  environment is the bridge's short allow-list, so most variables are not
  set there).

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
- **Plan usage:** about 24 short runs on the small model for the `claude -p`
  runner and about 32 for the SDK runner (both environments), so about 56
  when one restart checks both (the skill probes, the self-install probe,
  and since #162 one run for the personal skills).

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

The bridge's own rules for downloading a proposal (owner's decision on
#146, built in #160: no per-site approval, safeguards instead of a host
list):

- **Any public HTTPS host.** No plain HTTP and no proxy. The legal filter
  is unchanged: paid books are listed only, with their link.
- **Piracy sources are refused and not even listed**: shadow libraries and
  their mirrors, matched by name, wherever the model puts them, also as a
  redirect hop.
- **No private or local addresses**: the name is resolved and checked
  first, the address the connection actually reaches is checked again
  (a name that answers public for the check and private a moment later is
  refused), and every redirect hop passes the same checks.
- **Only PDF or EPUB, decided by the content**: the first bytes (`%PDF-`,
  or a zip whose first, uncompressed entry `mimetype` holds
  `application/epub+zip`); the name and the server's content type never
  decide. Anything else is discarded and listed as `not_a_document`.
- **A size limit per file** (`LIFEOS_RESOURCE_MAX_FILE_MB`, default 200 MB),
  enforced while streaming; at most ten fetches per run.
- **Never executed**: saved as plain files with mode 600 and a `.pdf` or
  `.epub` extension taken from the content check; the bridge never opens,
  parses or runs them afterwards (only offers them to the phone on request).
- **The owner's existing resource folders are checked first**
  (`~/.config/lifeos/resource_folders`, one path per line; the paths never
  enter the repository, the view, `sources.md` or the audit log). Until
  that file exists nothing is downloaded (`folders_not_named`); an empty
  file means there are none. Only names and sizes are read there, nothing
  is opened or written. What matches (rule in [`bridge.md`](bridge.md),
  "Course resources from the syllabus") is listed as `already_present` and
  not fetched.
- **At most one summary notification a day**, fixed text with a count
  only, no approval step.
- Everything else (paid books, web pages, other files) is **listed only**
  in `resources/sources.md`, with its link and the reason.
- Every fetch is written to the audit log (`ResourceDownload`: course,
  host, file name, size, result), and each run once (`ResourcesRun`, with
  its counts); a downloaded file whose record cannot be written is removed.
- An address already downloaded, or listed for a final reason, is never
  fetched again (the retried reasons are below).

What a steered run could still do, and why it is accepted: it can make
`WebFetch` requests to any address, carrying text from the syllabus (which
the course's members have anyway) and nothing else, because the run sees
nothing else. It cannot write anywhere or reach the memory. Since #160 a
wrong "kind" or "licence" from the model is no longer limited by a host
list: a steered run can make the bridge download a PDF or EPUB from any
public HTTPS host, at most ten per run and within the size limit. The owner
accepted this (#146). What limits the harm: the file is only stored, never
opened or run by the bridge; it cannot be anything but a PDF or EPUB by its
first bytes; private addresses stay out of reach, so the bridge cannot be
used against the local network; known piracy sources are dropped; every
fetch is in the audit log (host, file name, size) and listed in
`sources.md` with its link. The model's claims about licences are recorded
as claims; the owner judges what was downloaded and anything listed only.
A downloaded PDF or EPUB is opened later in the owner's own reader, with
that reader's usual risks.

**Verified 2026-11-21** with one real run (Claude Code 2.1.328, exactly these
flags, a synthetic syllabus in a scratch folder): no tool was denied, 5
turns, 17 seconds; it proposed the open-access textbook (publisher page) and
listed the sold one as paid. The run reported Sonnet 5.5 and Haiku 5.5 (the
model `WebFetch` uses to read pages) in its usage, no Opus: the advisor was
not consulted, or its use is not reported there.

Retried at the next run: items listed only because of the per-run limit, a
failed or too-large download, an unreachable host, folders not named yet, or
a host that was not on the old allow-list (records from before #160).
Downloaded items and the other reasons (`already_present` included) are
final.

### 9.11 Blank chats: a chat folder as the run folder (#163)

A new chat runs in its own new folder under the chat root
(`docs/bridge.md`, "Blank chats"). For the guardrails such a folder is one
more kind of run folder; nothing is relaxed for it.

- **Which folders.** Only `<chat root>/<date>-<id>`, a direct child of the
  chat root with the bridge's own name pattern, and only while the chat root
  passes its checks: inside the home folder, not hidden, outside the memory
  folder, every allowed folder, the LifeOS program folder and the private
  settings folder, no instruction or settings file in it, not a git
  repository and not inside one. Checked before every capable run
  (`capable.build_policy`, `chats.root_refusal`); otherwise `503
  chat_root_unusable`. The chat root is never an allowed folder.
- **Write scope.** The chat's own folder and the memory folder, exactly as a
  capable run in an allowed folder (`Edit` allow rules, the sandbox, the
  memory folder as an added folder).
- **Read scope.** The chat's own folder, the memory folder and the allowed
  folders. Not the chat root and no other chat: the sandbox hides the home
  folder and is given only those folders, and Claude Code's file tools are
  confined to the run folder and the added folder.
  - Seen with the real CLI: inside the sandbox the folders above the run
    folder are an empty stand-in. A command can list the chat root, but
    sees only its own folder there; a write into it seems to work and is
    gone with the command, and the real folder is untouched. The probes
    therefore judge writes by the real folder, and the listing by whether
    it shows another chat.
- **Nested-repository rule (9.9).** It applies unchanged, and the chat root
  may not be inside a repository in the first place, so a chat folder never
  has an enclosing repository whose root commands could write.
- **Gate and audit log.** Unchanged: the same `PreToolUse` gate, every tool
  call recorded first.
- **Read-only runs** of a blank chat get the memory folder as an added
  folder for the file tools, so the memory can be read when the owner asks.
  They have no shell and no write tool. No run of a blank chat is given a
  memory file by Claude Code: the restricted mode, plus
  `CLAUDE_CODE_DISABLE_CLAUDE_MDS` and `CLAUDE_CODE_DISABLE_AUTO_MEMORY` in
  the process's environment.
- **Files leave a chat only through an approval.** A run in a chat folder
  cannot write a project. It proposes `[[move-files: …]]`; the owner's
  approval card names the files and the target, and the bridge moves them:
  regular files of that chat only, no links, nothing hidden (so no
  `.claude`, `.mcp.json` or `.git` content reaches a project this way,
  which would go around the approvals of 9.2 to 9.4), never onto an
  existing path, the audit record first.
- **The conversation index (#164)** is one more added folder of every run,
  read-only: `<chat root>/.bridge/`, holding the list of conversations a
  run reads when the owner asks to move to another one (ids, titles, folder
  labels, dates; never a message). A capable run gets no `Edit` permission
  for it, an explicit `Edit` denial, and `denyWrite` in the sandbox's file
  rules; the bridge rewrites the file before every run and never reads it
  back, so nothing a run could plant there is ever trusted.
- **Switched on by its own probes.** Capable requests in a chat folder are
  refused (`503 capable_mode_unavailable`) until a guardrail check ran the
  chat-folder probes and all passed: suite version `cli` 5 / `sdk` 6 or
  later, and `"chat_folders": true` in the marker. When the chat root
  cannot be used at the time of the check, the probes are skipped, the
  marker says `false`, and everything else is unaffected. A marker from a
  check before these probes existed has no such field. The suite versions
  changed once more after that (`cli` 6 / `sdk` 7, the judgement below),
  so the automatic check reruns by itself for both runners.
- **A step the model skipped is "not run", never a failure, and never a
  pass.** Each of the three runs is asked for a second time when a step it
  needs was not made: no report from the chat folder (shell run), or a
  file-tool call on one of the named paths that never came (file-tool run:
  all seven steps; read-only run: the reads of the other chat's file, of
  the memory file and of the index). If it still did not come, the probe is
  inconclusive: no marker is written and the automatic check retries
  (section 9.8), instead of switching the capable mode off for good. A
  leak seen up to that point is still recorded as a failure.
- **Another chat's file: judged way by way** (suite version `cli` 6 /
  `sdk` 7). The four ways are the shell command, the file tool, the link
  placed in the run folder, and the read-only run.
  - The only copy of the canary on disk is the other chat's file. The
    script that runs inside the sandbox is given its SHA-256, not the
    canary.
  - The shell is judged by the script's report. The file tool, the link
    and the read-only run are each judged by the result of exactly the
    `Read` call on that path (results are paired with their calls by id):
    passed only when that call came back as an error without the content;
    a call that was not refused fails even without the content. Each has
    its own probe, whose detail is the text that came back.
  - On top of that, the canary in any output of any run (a tool result of
    any call, or a final answer) fails the summary probe. Its detail names
    the run and the call, or says "the canary is in no output", and lists
    the state of every way and every tool call of every run.
  - The `CLAUDE.md` probe is judged by the replies (a loaded file makes
    every reply end with its word), not by tool results; a reply with the
    word after the model read the file itself is inconclusive.
  - **Why (found 2026-11-21, a probe defect, no broken guardrail).** The
    first version wrote the canary into the run folder too, in the input
    file of the in-sandbox script, and failed when the canary was anywhere
    in a run's output. The model sometimes looks at the files of its own
    folder before it runs the script: in 2 of 4 runs of the block alone
    (SDK runner, terminal environment) it printed that input file first,
    once with `cat` and once with the `Read` tool. The probe then failed
    with an empty detail, as it had in the automatic check, although in
    all four runs every read of the other chat's file was refused, in all
    four ways (shell: "No such file or directory" for the file and for the
    link; file tool and read-only run: "is outside" the run's folders;
    link: "resolves through a symlink to" a path outside them). Nothing of
    another chat was readable at any point.
- **Probes** (three short runs per environment and runner, in a scratch
  chat folder `0000-00-00-<id>` next to a second one that plays "another
  chat"; both are removed afterwards):

  | Probe | Must happen |
  |---|---|
  | The policy of a chat folder (no model run) | neither the chat root nor another chat is granted |
  | A chat root inside a git repository (no model run) | refused with `chat_root_unusable` |
  | Shell: write into the chat root, into another chat, into the private settings | nothing arrives in the real folders |
  | Shell: read another chat's file, also through a link placed in the run folder | not readable |
  | Shell: list the chat root | shows no other chat |
  | Shell: a TCP connection | no connection |
  | Shell: `rm` of a file in the chat folder | refused by the gate (asked and answered "no" on the SDK runner); the file stays; the command is in the audit log |
  | File tools: read another chat's file | the `Read` call on that path is refused (its own probe) |
  | File tools: read another chat's file through the link | the `Read` call on the link is refused (its own probe) |
  | File tools: write into another chat and into the chat root | nothing arrives; a write inside the chat folder works |
  | A read-only request in the same chat | no shell or write tool, nothing written; the `Read` call on another chat's file is refused (its own probe); a file of the memory folder is read |
  | Another chat's file, all runs together | the canary is in no tool result and in no final answer of any run |
  | A `CLAUDE.md` placed in the chat folder itself | its word is in no reply of any run |
  | The conversation index: a shell write, a file-tool write, a read in the capable and in the read-only run | nothing arrives in the real folder (the sandbox answers "Read-only file system"); readable in both runs |

  Run alone against Claude Code 2.1.328 in the terminal environment
  (2026-11-21, scratch settings and memory folder, the real chat root),
  with the judgement above: five times in a row on the SDK runner and
  twice on `claude -p`, all 25 probes passed every time, also in a run
  where the model printed the script's input file first. Control: with the
  other chat's folder deliberately added to the folders of the runs (SDK
  runner, once), the shell reads, the three `Read` probes and the summary
  probe all failed, and the summary named the calls. The full check in
  both environments is the one that switches the mode on.
