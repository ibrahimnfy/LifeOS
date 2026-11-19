# Headless Claude Code reference

Reference for the LifeOS bridge: the exact `claude` flags and behaviour the
bridge depends on when it runs Claude Code without a human at the keyboard.

- **Researched:** 2026-11-20
- **CLI version checked:** `claude --version` = 2.1.328, on Linux
- **Authentication used for experiments:** a claude.ai subscription login (no API key)

## How to read this document

Every claim is tagged with where it comes from:

| Tag | Meaning |
|---|---|
| **Documented** | Stated in official documentation. The source page is given in brackets, using the keys in [Sources](#sources). |
| **`--help`** | Shown by `claude --help` or a subcommand's `--help` on 2.1.328. |
| **Observed** | Verified by running the CLI on 2.1.328 (see [Experiments](#experiments)). Observed behaviour that is not also documented can change in any release. |
| **Unknown** | Not documented and not verified. Do not rely on a guess. |

All paths and IDs in examples are synthetic.

## Sources

Official documentation lives at `https://code.claude.com/docs/en/` (index:
`https://code.claude.com/docs/llms.txt`). All pages were fetched on 2026-11-20.

| Key | Page |
|---|---|
| [headless] | https://code.claude.com/docs/en/headless |
| [cli] | https://code.claude.com/docs/en/cli-reference |
| [sessions] | https://code.claude.com/docs/en/sessions |
| [modes] | https://code.claude.com/docs/en/permission-modes |
| [perms] | https://code.claude.com/docs/en/permissions |
| [settings] | https://code.claude.com/docs/en/settings |
| [settings-ref] | https://code.claude.com/docs/en/settings-reference |
| [env] | https://code.claude.com/docs/en/env-vars |
| [agent-view] | https://code.claude.com/docs/en/agent-view |
| [dir] | https://code.claude.com/docs/en/claude-directory |
| [auth] | https://code.claude.com/docs/en/authentication |
| [errors] | https://code.claude.com/docs/en/errors |
| [costs] | https://code.claude.com/docs/en/costs |
| [legal] | https://code.claude.com/docs/en/legal-and-compliance |
| [sdk-ts] | https://code.claude.com/docs/en/agent-sdk/typescript |
| [sdk-py] | https://code.claude.com/docs/en/agent-sdk/python |
| [sdk-sessions] | https://code.claude.com/docs/en/agent-sdk/sessions |
| [sdk-cost] | https://code.claude.com/docs/en/agent-sdk/cost-tracking |
| [support-sdk] | https://support.claude.com/en/articles/15036540-use-the-claude-agent-sdk-with-your-claude-plan |
| [support-plan] | https://support.claude.com/en/articles/11145838-using-claude-code-with-your-pro-or-max-plan |

## Experiments

Three paid runs were made, all with a small model, in empty scratch
directories, with every inherited `CLAUDE*` variable removed from the
environment:

| # | What ran | What it settled |
|---|---|---|
| E1 | `-p` with `--output-format stream-json --verbose --include-partial-messages --permission-mode dontAsk --tools "Read,Glob,Grep,Write"`, prompt asks for a file write. Polled `claude agents --json` while it ran. | Event sequence, `result` fields, denial behaviour, exit code, in-use detection, transcript location and line types. |
| E2 | From a **different** directory: prompt on stdin, `-p --resume <id> --output-format json`. | Resume across directories, which cwd the run uses, where the transcript is appended, `json` output shape. |
| E3 | `-p --permission-mode dontAsk --tools "Read,Glob,Grep,Edit,Write" --allowedTools "Edit(//<abs>/allowed/**)"`, prompt asks for a write inside the allowed folder, a write outside it, and a read of a file outside the working directory. | The "edit only inside listed folders, no shell" policy. |

All three runs also carried `--model haiku --strict-mcp-config
--disable-slash-commands --setting-sources ""`, had stdin closed or piped,
and used **no** `--settings` file and **no** `--permission-prompts none`.
This matters when reading the **Observed** tags:

- The experiments ran with **no user, project or local settings loaded**
  (through the undocumented empty `--setting-sources` value), so no
  user-level allow rules, additional directories or hooks were in play.
  The recommended flag sets in section 5 use `--setting-sources user` and
  a `--settings` deny file instead. **The recommended command lines were
  not run as written against the model.** They were only parse-checked
  (see below).
- Empty `slash_commands`, `skills` and `mcp_servers` in the `init` event
  were observed with all three of `--disable-slash-commands`,
  `--strict-mcp-config` and `--setting-sources ""` present; the effect of
  each flag alone was not isolated. The `init` event still listed some
  plugins and agents in this configuration.

Free checks (they fail before any model request): flag validation errors,
missing prompt, resuming an unknown session ID in both output formats,
`claude auth status`, `claude daemon status`, a comparison of the flag
names in `claude --help` with the CLI reference page, and a parse check of
the recommended `/ask` and resume command lines (every flag in them was
accepted together by option parsing).

---

## 1. `-p` / `--print`

### Passing the prompt

- **Documented** [headless]: `claude -p "<prompt>"` runs non-interactively. Non-interactive mode also reads stdin, so data can be piped in: `cat file | claude -p "<instruction>"`.
- **Observed** (E2): the prompt can be given on stdin alone, with no prompt argument: `printf '%s' "$PROMPT" | claude -p --resume <id> ...`.
- **Observed**: with no prompt argument and no stdin data, the CLI prints `Error: Input must be provided either through stdin or as a prompt argument when using --print` to stderr and exits 1.
- **Documented** [headless]: piped stdin is capped at 10 MB; above that the CLI exits non-zero with an error. If stdin cannot be read, the CLI warns on stderr and continues with the argument prompt.
- **Documented** [headless]: a prompt that starts with `/skill-name` is expanded as a skill or custom command. Some built-in commands also work in `-p` (`/model <x>`, `/config key=value`, ...). The bridge must therefore treat a leading `/` in phone input as significant, or pass `--disable-slash-commands`.
- **Unknown**: a size limit for the prompt argument. Only the operating system's argument-length limit applies as far as the documentation says. Passing the prompt on stdin avoids the question and keeps the prompt out of the process list.

### Exit codes

- **Documented** [headless]: exit code 0 on success, non-zero when the run fails. An invalid flag is reported on stderr before the run starts. A failure inside the run, such as missing authentication, is printed "as the result on stdout".
- **Documented** [headless]: SIGTERM makes the CLI exit with code 143, leaves the turn unfinished and records no result. SIGINT ends the turn instead. On SIGTERM the process tree of a running Bash command is terminated.
- **Observed**:

| Situation | Exit | stdout | stderr |
|---|---|---|---|
| Normal run, including one where a tool call was denied (E1, E3) | 0 | result | empty |
| Unknown flag | 1 | empty | `error: unknown option '--x'` |
| `--output-format stream-json` without `--verbose` | 1 | empty | `Error: When using --print, --output-format=stream-json requires --verbose` |
| `--session-id` that is not a UUID | 1 | empty | `Error: Invalid session ID. Must be a valid UUID.` |
| `--resume <unknown-uuid> --output-format json` | 1 | **empty (no JSON)** | `No conversation found with session ID: <id>` |
| `--resume <unknown-uuid> --output-format stream-json` | 1 | one `result` line: `subtype: "error_during_execution"`, `is_error: true`, `num_turns: 0`, zeroed totals, `errors: ["No conversation found with session ID: <id>"]`, and a fresh unrelated `session_id` | same message |
| No prompt | 1 | empty | see above |

- **Unknown**: the exact exit code for each in-run failure (`error_max_turns`, API error, usage limit). The bridge must not depend on specific non-zero values other than 143.

### Other limits and behaviours

- **Documented** [headless], **`--help`**: `-p` shows no workspace trust dialog. Without `--bare`, a `-p` run loads hooks from the project's `.claude/settings.json` and connects servers from its `.mcp.json` "even in a folder you've never trusted". Settings files that fail validation are silently ignored in `-p` mode.
- **Documented** [headless]: after the final turn a `-p` run can stay open waiting for background work Claude started (background commands, subagents); the idle wait is capped at 10 minutes by default (`CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS`). The wait begins once "stdin has closed", so the bridge should always close the child's stdin.
- **Documented** [headless]: `-p` rejects `--bg`.
- **`--help`**: there is no wall-clock timeout flag. See section 6.

---

## 2. Output formats

`--output-format` accepts `text` (default), `json`, `stream-json` (**`--help`**, **Documented** [cli]). It only works with `--print`.

### `text`

**Documented** [headless]: plain response text on stdout. No session ID, so it is not useful to the bridge.

### `json`

**Documented** [headless]: one JSON object with the result text in `result`, the session ID, usage and cost metadata.

**Observed** (E2, E3): stdout is a single JSON **object** (not an array) of the same kind that `stream-json` emits as its last line. The key sets were not identical between the two formats (the stream result in E1 had one extra key, `model_usage_since_last_result`).

The documented shape is `SDKResultMessage` [sdk-ts]. Fields the bridge should parse:

| Field | Type | Source | Notes |
|---|---|---|---|
| `type` | `"result"` | Documented, Observed | |
| `subtype` | `"success"` \| `"error_max_turns"` \| `"error_during_execution"` \| `"error_max_budget_usd"` \| `"error_max_structured_output_retries"` | Documented | `success` and `error_during_execution` were observed. |
| `is_error` | boolean | Documented, Observed | Present on **both** arms. The `success` arm can still have `is_error: true` (it is typed boolean, and the arm also carries `api_error_status`). **Branch on `is_error`, not on `subtype` alone.** |
| `result` | string | Documented, Observed | Final answer text. Only on the `success` arm. |
| `errors` | string[] | Documented, Observed | Only on the error arms, in place of `result`. Observed once, for an unknown session ID with `stream-json` output (section 1). |
| `session_id` | string (UUID) | Documented, Observed | Use for `--resume`. |
| `num_turns` | number | Documented, Observed | |
| `duration_ms`, `duration_api_ms` | number | Documented, Observed | |
| `total_cost_usd` | number | Documented, Observed | Client-side **estimate**, see below. |
| `usage` | object | Documented, Observed | Main loop only. Observed keys include `input_tokens`, `output_tokens`, `cache_creation_input_tokens`, `cache_read_input_tokens`, `service_tier`. |
| `modelUsage` | map model name → object | Documented, Observed | Observed keys per model include `inputTokens`, `outputTokens`, `cacheReadInputTokens`, `cacheCreationInputTokens`, `costUSD`, `contextWindow`, `maxOutputTokens`. |
| `permission_denials` | array of `{tool_name, tool_use_id, tool_input}` | Documented, Observed | Authoritative record of denied tool calls. Empty array when none. `tool_input` contains the full tool arguments (for a write: the absolute path **and the content**), so do not log it by default. |
| `stop_reason` | string \| null | Documented, Observed | Observed `"end_turn"`. |
| `terminal_reason` | string | Documented, Observed | Why the loop ended. Observed `"completed"`. Documented values include `"max_turns"`, `"api_error"`, `"budget_exhausted"`, `"prompt_too_long"`, `"blocking_limit"`, `"model_error"`. |
| `api_error_status` | number \| null | Documented, Observed | HTTP status of the API error that ended the conversation; `null` otherwise. |
| `structured_output` | any | Documented | Only with `--json-schema`; absent if no valid structured output was produced. |
| `startup_failure_reason` | string | Documented | Only on an `error_during_execution` result written for a known startup failure. |
| `uuid` | string | Documented, Observed | |

**Observed** additional keys on 2.1.328 that the bridge should ignore:
`ttft_ms`, `ttft_stream_ms`, `time_to_request_ms`, `first_content_frame_ms`,
`queued_turn_count`, `result_index`, `fast_mode_state`,
`fast_mode_disabled_reason`, `subagent_stats`, `safety_stops`,
`model_usage_since_last_result`, `credential_expires_at`. The parser must
tolerate unknown keys.

Cost fields:

- **Documented** [sdk-cost]: `total_cost_usd` and `costUSD` are "client-side estimates, not authoritative billing data", computed from a bundled price table.
- **Documented** [headless], [sdk-ts]: a run started with `--continue` or `--resume` reports the conversation's **cumulative** total, earlier runs included. Do not sum `total_cost_usd` across resumes of one session.
- **Observed**: on a subscription login the field is still a non-zero dollar estimate. It is not what the owner is charged.

Synthetic example:

```json
{
  "type": "result",
  "subtype": "success",
  "is_error": false,
  "result": "The answer text.",
  "session_id": "11111111-2222-4333-8444-555555555555",
  "num_turns": 1,
  "duration_ms": 1800,
  "duration_api_ms": 1700,
  "total_cost_usd": 0.0012,
  "usage": {"input_tokens": 4, "output_tokens": 120, "cache_creation_input_tokens": 0, "cache_read_input_tokens": 9000},
  "modelUsage": {"<model-id>": {"inputTokens": 4, "outputTokens": 120, "costUSD": 0.0012}},
  "permission_denials": [],
  "stop_reason": "end_turn",
  "terminal_reason": "completed",
  "api_error_status": null,
  "uuid": "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
}
```

### `stream-json`

- **Documented** [headless]: newline-delimited JSON, one event per line; the last line is the `result` message.
- **Observed**: `--output-format stream-json` with `-p` **requires `--verbose`**; without it the CLI exits 1 (table in section 1). The documentation only shows the flags together.
- **Documented** [cli], **`--help`**: `--include-partial-messages` adds partial streaming events and requires `--print` and `--output-format stream-json`.

Event types. Every event has `type` and `session_id`; most have `uuid`.

| `type` (/ `subtype`) | Source | Content |
|---|---|---|
| `system` / `init` | Documented [headless], [sdk-ts]; Observed | First event unless plugin-install or hook events precede it. Fields include `session_id`, `cwd`, `model`, `tools`, `mcp_servers`, `permissionMode`, `apiKeySource`, `claude_code_version`, `slash_commands`, `skills`, `plugins`, `agents`, `capabilities`; optional `plugin_errors`, `mcp_server_errors`. **The session ID is available here, before any model output.** |
| `system` / `status` | Documented type [sdk-ts]; Observed | `status: "requesting"` was observed before each model request. Ignore. |
| `stream_event` | Documented [sdk-ts]; Observed | Only with `--include-partial-messages`. `event` is a raw Messages API stream event: `message_start`, `content_block_start`, `content_block_delta`, `content_block_stop`, `message_delta`, `message_stop`. Text arrives as `event.delta.type == "text_delta"` with `event.delta.text`. Observed other delta types: `input_json_delta` (tool arguments), `thinking_delta`, `signature_delta`. Main session only (`parent_tool_use_id` is always `null`). |
| `assistant` | Documented [sdk-ts]; Observed | A complete assistant message. `message.content` is a list of blocks (`text`, `tool_use`, `thinking`). One event per content block was observed. Optional `error` field with a category such as `rate_limit`, `authentication_failed`, `billing_error`, `overloaded`, `server_error`. |
| `user` | Documented [headless]; Observed | Tool results fed back to the model (`message.content[].type == "tool_result"`). |
| `system` / `permission_denied` | Documented [headless], [sdk-ts]; Observed | A tool call was denied without a prompt. Fields `tool_name`, `tool_use_id`, `decision_reason_type` (observed `"mode"`), `message`. Best-effort; `permission_denials` on the result is authoritative. |
| `system` / `api_retry` | Documented [headless] | Emitted before a retry. Fields `attempt`, `max_retries`, `retry_delay_ms`, `error_status`, `error` (category, e.g. `rate_limit`, `overloaded`). Not observed. |
| `rate_limit_event` | Documented [sdk-ts]; Observed | See section 7. |
| `system` / `thinking_tokens` | Documented name [sdk-ts]; Observed | Thinking progress (`estimated_tokens`). Ignore. |
| `result` | Documented; Observed | Last line. Same kind of object as `json` output. |

Many more `system` subtypes are documented for hooks, plugins, tasks and
subagents [sdk-ts]. The bridge must ignore event types it does not know.

**Observed** sequence in E1 (one denied tool call, then a text answer):
`system/init` → `system/status` → `stream_event`s → `assistant` (tool_use) →
`rate_limit_event` → `system/permission_denied` → `user` (tool_result) →
`system/status` → `stream_event`s → `assistant` (thinking) → `assistant`
(text) → `result`.

**Documented** [headless]: if the consumer reads slowly, the CLI waits for queued output to drain before exiting, up to 30 seconds.

### `--input-format`

- **`--help`**, **Documented** [cli]: `text` (default) or `stream-json` ("realtime streaming input"); only with `--print`. `--replay-user-messages` re-emits stdin user messages on stdout and requires both formats to be `stream-json`.
- The message schema for `stream-json` input is documented only through the Agent SDK's user-message type [sdk-ts], not on the CLI pages. The bridge does not need it: one process per prompt with `text` input is enough. **Not verified.**

---

## 3. Sessions

### Flags

| Flag | Source | Behaviour |
|---|---|---|
| `-r, --resume <id>` | Documented [cli], [sessions]; `--help`; Observed | Resume by session ID (UUID). Also accepts a session name, or the absolute path to a session's `.jsonl` transcript. |
| `-c, --continue` | Documented [cli], [sessions]; `--help` | Most recent conversation in the **current directory**. `claude -p --continue` includes sessions created by `-p`, the SDK and `/loop`; the interactive form skips them. Not suitable for the bridge: it is ambiguous when several sessions exist. |
| `--session-id <uuid>` | Documented [cli]; `--help` | Use a specific ID for the conversation. Must be a valid UUID (Observed: otherwise exit 1). Lets the bridge choose the ID before the run starts. **Unknown**: behaviour when the UUID already exists. |
| `--fork-session` | Documented [cli], [sessions]; `--help` | With `--resume`/`--continue`: copy the conversation into a new session ID and leave the original untouched. |
| `--no-session-persistence` | Documented [cli]; `--help` | Print mode only. Nothing is written to disk and the session cannot be resumed. `CLAUDE_CODE_SKIP_PROMPT_HISTORY=1` does the same in any mode [env]. |
| `-n, --name <name>` | Documented [cli]; `--help` | Sets a display name, usable with `--resume <name>`. |

### What `--resume` restores and what it does not

**Documented** [sessions]:

- Restored: conversation history, the model the session was using (with exceptions), the session's `--agent`.
- **Not restored; pass again on every resume**: `--mcp-config`, `--plugin-dir`, `--add-dir`, `--settings`, `--fallback-model`.
- Permission mode: `claude -p --resume` starts "in the permission mode a new `claude -p` run would start in". The mode of the original session is **not** carried over (one exception for plan mode that needs `--permission-prompt-tool`). The bridge must pass its permission flags on every resume.
- System prompt: by default the system prompt is recorded on the conversation's first request and reused on every resume until the conversation is compacted. A different `--append-system-prompt` on a later resume does not take effect until then (`--system-prompt-snapshot off` changes this) [cli].
- An interrupted tool call is not rerun. A turn interrupted by a kill is left as it is unless `CLAUDE_CODE_RESUME_INTERRUPTED_TURN=1` [headless].

### Where sessions are stored

**Documented** [sessions], [sdk-sessions]:

- Path: `~/.claude/projects/<project>/<session-id>.jsonl`.
- `<project>` is the absolute working directory with **every non-alphanumeric character replaced by `-`**. Example: `/home/user/my_app` → `-home-user-my-app`. If the converted name is longer than 200 characters it is truncated to 200 and a hash of the full path is appended.
- `CLAUDE_CONFIG_DIR` moves the whole tree (`$CLAUDE_CONFIG_DIR/projects/...`).
- The encoding is lossy (`/`, `_`, `.` and `-` all become `-`), so the working directory **cannot** be recovered from the directory name. Read it from the session metadata instead.
- Each line is a JSON object: "a message, a tool use or result, a record of a request Claude Code sent the model, or a metadata entry". **"The entry format is internal to Claude Code and changes between versions, so scripts that parse these files directly can break on any release."**
- Related per-session directories: `projects/<project>/<session>/subagents/`, `.../tool-results/` [dir].

**Observed** (E1, E2; unstable by the documentation's own statement):

- The computed path matched the documented encoding. File mode was `0600`.
- The project directory also contained a `memory/` directory.
- Line `type` values seen in a `-p` session: `session-owner`, `queue-operation`, `user`, `assistant`, `attachment`, `api-request-shape`, `api-request-blob`, `api-request`, `last-prompt`, `tools-used`, `cost-state`, `atis-latch`.
- `user`, `assistant` and `attachment` lines carry `uuid`, `parentUuid`, `timestamp` (ISO 8601, UTC), `sessionId`, `cwd`, `gitBranch`, `version`, `entrypoint` (`"sdk-cli"` for `-p`), `isSidechain`, and `message` (`role`, `content`). User prompt lines also carry `permissionMode`.
- `last-prompt` lines carry `lastPrompt` (text of the most recent prompt) and `leafUuid`.
- `session-owner` lines carry account and organisation identifiers, and `api-request*` lines carry the system prompt and request parameters. **Raw transcript lines must never be returned to the phone or logged.**
- No title line was present. This matches the documentation: a `claude -p` run started from a shell "doesn't get" a generated title [sessions].
- **Unknown**: the line type that stores a custom or AI-generated title. No session with a title was inspected.

Synthetic example of the fields the bridge would care about on one line:

```json
{"type":"user","uuid":"...","parentUuid":null,"timestamp":"2026-01-01T12:00:00.000Z",
 "sessionId":"11111111-2222-4333-8444-555555555555","cwd":"/home/user/project",
 "gitBranch":"main","version":"2.1.328","entrypoint":"sdk-cli",
 "message":{"role":"user","content":"the prompt text"}}
```

### Listing sessions: use the documented API, not the JSONL files

**`--help`**: the CLI has **no** non-interactive "list sessions" command. `claude --resume` without a value opens an interactive picker.

**Documented** [sdk-py], [sdk-ts], [sdk-sessions]: the Agent SDK exposes functions for "enumerating sessions on disk". In Python (`claude_agent_sdk`):

```python
def list_sessions(directory: str | None = None, limit: int | None = None,
                  offset: int = 0, include_worktrees: bool = True) -> list[SDKSessionInfo]
def get_session_info(session_id, directory=None) -> SDKSessionInfo | None
def get_session_messages(session_id, directory=None, limit=None, offset=0) -> list[SessionMessage]
```

`list_sessions()` is synchronous, returns sessions across all projects when `directory` is omitted, sorted by `last_modified` descending. `SDKSessionInfo` fields:

| Field | Meaning | Bridge use |
|---|---|---|
| `session_id` | UUID | resume key |
| `summary` | "Display title: custom title, most recent prompt, auto-generated summary, or first prompt" | **title** |
| `custom_title` | user-set title, or the auto-generated one | |
| `first_prompt` | first meaningful user prompt | |
| `cwd` | working directory for the session | **folder**, and the directory to resume in |
| `last_modified` | milliseconds since epoch | **last activity** |
| `created_at` | milliseconds since epoch | |
| `git_branch`, `tag`, `file_size` | | |

This is the supported way to get title, folder and last activity. **Not verified by experiment** (the SDK is not installed in this repo yet).

**Unknown**: whether `list_sessions()` includes sessions created by `claude -p`. The interactive picker and interactive `--continue` hide them [sessions]; the SDK page says nothing either way. After E2 the session's first lines carried one `cwd` and later lines another; which one `SDKSessionInfo.cwd` reports is also unknown.

### Is a session bound to its working directory?

- **Documented** [sessions], [headless]: "A session is a saved conversation tied to a project directory", but "You can run `claude --resume <session-id>` from any directory". The ID is looked up first in the current project directory and its git worktrees, then in every other project on the machine. The cross-project search resolves "only when exactly one other project holds a transcript with messages for it". Before v2.1.223 the search covered only the current project and its worktrees.
- **Observed** (E2): a session created in directory A was resumed with `claude -p --resume <id>` from directory B.
  - The run succeeded and had the earlier conversation.
  - The process **ran in B**: new transcript lines carry `cwd` = B. Claude Code did not change back to A.
  - The transcript **stayed in A's project directory** and was appended there. No file was created under B's project directory.
- Consequence: `--resume` does not have to run from the original directory, but it does not restore that directory either. To "resume in the session's original directory" the bridge must itself start the child with `cwd` set to the session's recorded `cwd`. Relative paths in the old conversation, project `CLAUDE.md` and project settings all depend on it.
- **Documented** [sessions]: if the original directory no longer exists, the interactive picker resumes in the current directory. For `-p` the bridge should check that the directory exists and refuse otherwise.

### Retention and cleanup

**Documented** [dir], [settings-ref], [sessions]:

- Transcripts older than `cleanupPeriodDays` are deleted by a background sweep that runs after a session starts. Default **30 days**, minimum 1; `0` is a validation error. Deletion is silent.
- `claude purge [path]` deletes a project's transcripts and related state (`--dry-run`, `--all`, `-y`) (**`--help`**).
- `CLAUDE_CODE_TRANSCRIPT_LOCAL_GC=1` trims pre-compaction history from large `-p` transcripts [env].

A session listed by the bridge can therefore disappear later. `--resume` of a deleted session fails as in section 1 (exit 1, stderr only).

---

## 4. Detecting that a session is in use

There is **no documented lock** that stops two processes from resuming the same session.

- **Documented** [sessions]: "If you resume the same session in two terminals or two `claude -p` runs at the same time, both write to one transcript. When you resume that session later, messages from one of them can be missing." The documented remedy is `--fork-session`.

### Documented mechanisms

1. **`claude agents --json`** [agent-view], [cli], **`--help`**: "Print active sessions (interactive and background) as a JSON array and exit (for scripts; works without a terminal)". It lists "every live session, plus background sessions that are still working or blocked". The page calls it "the supported way to read session state from outside Claude Code". Entry fields:

   | Field | Present | Meaning |
   |---|---|---|
   | `cwd`, `kind`, `startedAt` | always | `kind` is `interactive` or `background`; `startedAt` is Unix ms |
   | `pid`, `status` | while the process is alive | `status` is `busy`, `waiting` or `idle` |
   | `sessionId`, `name` | when set | `sessionId` is the full UUID usable with `--resume` |
   | `id`, `state` | background sessions | `state` is `working`, `blocked`, `done`, `failed` or `stopped` |
   | `waitingFor` | when `status` is `waiting` | e.g. `permission prompt`, `input needed` |

   `--all` adds completed background sessions; `--cwd <path>` filters.

2. **Background sessions refuse on their own** [sessions]: `claude -p --resume <id>` on a conversation held by a *running background session*, when the command has output flags such as `--output-format json` or piped I/O, "sends nothing and exits with status 1, printing that the session is running in the background". By default this ends with stderr only; with `CLAUDE_CODE_STARTUP_FAILURE_RESULTS=1` and `stream-json` output a result with `startup_failure_reason: "session_held_by_background"` is written [sdk-ts], [env]. This covers background (`--bg`, agent view) sessions **only**, not a session open in an ordinary terminal.

3. **`~/.claude/sessions/`** [dir]: "holds one small file per running session, used to detect concurrent sessions and crashes"; each file is removed when its session exits. The file format is not documented.

### Observed (E1)

- While a `claude -p` run was active, `claude agents --json` listed it as `{"pid": <n>, "cwd": "<dir>", "kind": "interactive", "startedAt": <ms>, "sessionId": "<uuid>", "name": "<dir>-xx", "status": "busy"}`. So `-p` runs appear with `kind: "interactive"`. `status` changed to `idle` shortly before exit and the entry was gone after exit.
- Calling `claude agents --json` did not start the background supervisor (`claude daemon status` reported `not running` before and after).
- A file `~/.claude/sessions/<pid>.json` existed during the run and was gone afterwards. Keys included `pid`, `sessionId`, `cwd`, `startedAt`, `status`, `kind`, `entrypoint`, `version`.

### Not verified

- Whether the `sessionId` reported for a **resumed** session (interactive `claude --resume`, or after `/clear` or `/resume` inside a session) is always the ID the bridge would look up.
- Whether every live session is listed. Documented exceptions exist: a nested interactive session started from inside another Claude Code session is excluded from the `claude agents` list [env, `CLAUDE_CODE_CHILD_SESSION`], and agent view can be disabled (`disableAgentView`).
- Sessions held by the desktop app or an IDE extension, which keep their own session lists [sessions].

### Recommended approach for the bridge

1. **Own lock (required).** One in-process lock per session ID, so the bridge never runs two children on one session. This is the only protection against the bridge's own concurrency.
2. **Documented check.** Before resuming, run `claude agents --json` and refuse if any entry has `sessionId` equal to the target. A stricter variant also refuses when a live entry has the same `cwd`, which is safer given the unverified points above but produces false refusals.
3. **Heuristics (clearly not guaranteed):**
   - Refuse if the transcript file's modification time is within the last N seconds. An idle interactive session does not write, so this only catches active turns.
   - Read `~/.claude/sessions/*.json` and match `sessionId`. Undocumented format; prefer step 2, which exposes the same data through a supported command.
4. **Alternative that cannot corrupt:** resume with `--fork-session`. The original is left intact and the bridge continues in a copy under a new ID. The cost is that the phone and the computer then hold diverging conversations.
5. Treat exit 1 with a stderr message containing "running in the background" as "in use".

There is a race between the check and the start of the child in every variant.

---

## 5. Permissions without a human

### What happens when a tool is not allowed

- **Documented** [modes], [headless]: on v2.1.302 or later, `claude -p` runs with no permission mode set start in Manual mode (config value `default`). "Calls needing approval are denied unless you approve them with an allow rule, hook, `canUseTool` callback, or `--permission-prompt-tool`." The run does not wait: `dontAsk` mode "never waits for input".
- **Observed** (E1, E3): a denied call does **not hang and does not fail the run**. The model receives an error tool result, continues, and the run ends with exit code 0, `subtype: "success"`, `is_error: false`. The denial is reported in `permission_denials` on the result, and as a `system/permission_denied` event in `stream-json`.
- Consequence: the bridge must inspect `permission_denials` to tell the phone that part of the request was refused. The exit code and `is_error` will not show it.
- A run **can** wait when a permission host is configured (`--permission-prompt-tool`, or an SDK callback). `--permission-prompts none` or the setting `permissions.denyInsteadOfPrompting: true` removes that wait [headless], [settings-ref].

### `--permission-mode`

**`--help`** choices: `acceptEdits`, `auto`, `bypassPermissions`, `manual`, `dontAsk`, `plan`. **Documented** [cli]: `default` is also accepted; `manual` is an alias for it.

| Mode | Documented behaviour [modes] | In an unattended `-p` run |
|---|---|---|
| `default` / `manual` | Reads only, inside the working directories. Everything else asks. | Starting mode of `-p`. Anything that would ask is denied. |
| `dontAsk` | "File reads inside your working directories and pre-approved tools; anything that would prompt is denied". Explicit `ask` rules are denied. Writes to protected paths are denied. | Intended for "locked-down CI and scripts". **Recommended base mode for the bridge.** |
| `acceptEdits` | Also auto-approves file edits and the filesystem commands `mkdir`, `touch`, `rm`, `rmdir`, `mv`, `cp`, `sed`, only for paths inside the working directory or additional directories. | Other shell commands are denied unless allowed by a rule. Note that it auto-approves `rm` and `mv` inside the working directories if Bash is available. |
| `plan` | Read and explore; edits blocked until a plan is approved. | Nobody can approve the plan. Not useful. |
| `auto` | A classifier model reviews actions instead of a person. Has account, model and organisation availability requirements; falls back to Manual when unavailable. | With `--permission-prompts none`, whatever the classifier does not approve is denied. Protected-path writes go to the classifier. |
| `bypassPermissions` | "Everything". Skips prompts and safety checks, including protected-path writes. A few actions are still never auto-approved (explicit `ask` rules, recognised critical-path removals such as `rm -rf ~`). Deny rules still apply. | Documented warning: "Only use this mode in isolated environments like containers, VMs, or dev containers without internet access". |

`--dangerously-skip-permissions` is "equivalent to `--permission-mode bypassPermissions`" [cli]. `--allow-dangerously-skip-permissions` only makes the mode selectable later (**`--help`**).

Precedence for the starting mode: the flag, then `permissions.defaultMode` in a settings file, then the built-in default [modes]. A user-level `defaultMode` therefore changes what a flagless `-p` run does, so the bridge should always pass `--permission-mode` explicitly.

### Rules: `--allowedTools`, `--disallowedTools`, `--tools`

- **`--allowedTools` / `--allowed-tools`** (**`--help`**, [cli]): allow rules, comma- or space-separated. Matching calls run without asking. It does **not** restrict which tools exist: "Calls to tools you didn't list run if they need no approval" [headless].
- **`--disallowedTools` / `--disallowed-tools`** (**`--help`**, [cli]): deny rules. A bare tool name removes the tool from the model's context; `"mcp__*"` removes every MCP tool. A scoped rule such as `Bash(rm *)` leaves the tool and denies matching calls.
- **`--tools`** (**`--help`**, [cli]): restricts which **built-in** tools exist at all. `""` for none, `"default"` for the default set, or names such as `"Read,Glob,Grep"`. It does not affect MCP tools. **Observed** (E1): with `--tools "Read,Glob,Grep,Write"` the `init` event listed exactly those four tools.
- Evaluation order [perms]: **deny, then ask, then allow**; the first match wins and specificity does not matter. An allow rule cannot carve an exception out of a deny rule. "If a tool is denied at any level, no other level can allow it."
- Rules are enforced by Claude Code, not by the model [perms].

Rule syntax [perms]: `Tool` or `Tool(specifier)`.

| Rule | Meaning |
|---|---|
| `Read`, `Edit`, `Bash`, `WebFetch` | every use of the tool |
| `Bash(git diff *)` | prefix match; the space before `*` matters (`Bash(git diff*)` would also match `git diff-index`) |
| `WebFetch(domain:example.com)` | by domain |
| `mcp__server__tool`, `mcp__*` | MCP tools |
| `Read(<path>)`, `Edit(<path>)` | path rules, gitignore-style patterns |

Path rules for `Read` and `Edit` [perms]:

| Pattern | Anchored at | Example |
|---|---|---|
| `//path` | filesystem root (absolute) | `Edit(//home/user/project/docs/**)` |
| `~/path` | home directory | `Read(~/.ssh/**)` |
| `/path` | the settings source: the primary working directory for CLI flags and project settings; the file's directory for a `--settings` file; `~/.claude` for user settings | `Edit(/src/**)` |
| `path` or `./path` | current directory | `Read(*.env)` |

- **A single leading slash is not an absolute path.** Use `//` for absolute paths.
- `Edit` rules cover all built-in file-editing tools. `Write(path)`, `Glob(path)` and `Grep(path)` rules are accepted but **do not match**; write `Edit(path)` and `Read(path)` instead.
- `Read` deny rules are applied best-effort to Grep and Glob, and also block Edit/Write on the same path.
- `*` matches within one path segment, `**` across directories.
- `Read`/`Edit` deny rules do not stop an arbitrary subprocess (for example a script run through Bash) from touching the files. Only removing Bash, or OS-level sandboxing, does.

**Read-only Bash is not confined** [perms]: a built-in set of commands (`ls`, `cat`, `head`, `tail`, `grep`, `find`, `wc`, `stat`, `du`, read-only `git`, ...) runs "without a permission prompt in every mode". If Bash is available, the model can read files anywhere the user can, including the bridge's secrets directory, unless `permissions.blockReadsOutsideWorkingDirectories` is on (which makes recognised file-reading commands ask, and therefore get denied, outside the working directories). **A read-only policy must remove Bash with `--tools`.**

### `--add-dir`

**Documented** [cli], [perms]: adds working directories. Files there "become readable without prompts", and edit permission follows the current mode. It grants file access, not configuration: most `.claude/` configuration in an added directory is not loaded. Each path must exist. It is not restored on resume.

### `--permission-prompt-tool` and `--permission-prompts`

- `--permission-prompt-tool <mcp__server__tool>`: **Documented** [cli], but **not listed in `claude --help` on 2.1.328**. **Observed**: the flag is accepted (it passes option parsing). It names an MCP tool that answers permission prompts. It would let the bridge forward approvals to the phone later; with it the run **waits** for the tool's answer.
- `--permission-prompts <host|none>`: **`--help`**, **Documented** [headless], [cli] (v2.1.259+). `none` means "nobody: anything that would prompt is denied automatically". In a `-p` run with no host the calls are denied either way; the flag additionally tells Claude not to retry, and removes tools that need a person, such as `AskUserQuestion`.

### Settings files and precedence

**Documented** [settings], [perms]. Highest first:

1. Managed settings (organisation policy).
2. Command line: `--settings <file-or-json>`, for that run only.
3. Project local: `.claude/settings.local.json`.
4. Shared project: `.claude/settings.json`.
5. User: `~/.claude/settings.json`.

- List keys such as `permissions.allow` are **merged** across levels, not overridden. A user-level allow rule therefore still applies to bridge runs unless the user source is excluded.
- Deny from any level wins over allow from any level.
- `--setting-sources <user,project,local>` selects which file sources load (**`--help`**, [cli]). The documentation recommends `--setting-sources user` to run in a repository you did not write: Claude Code then "reads neither the project's settings files nor its `.mcp.json`" [perms].
- In a `-p` run in a folder that was never trusted interactively: project **hooks, `env` block and helper commands are used**; project `permissions.allow` and `additionalDirectories` are **not** used (a warning goes to stderr); `.mcp.json` servers are **connected without asking** [perms].
- Protected paths (`.git`, `.claude`, shell rc files, `.mcp.json`, ...) cannot be pre-approved by allow rules from settings files; in `dontAsk` writes to them are denied [modes].

### `--restricted`

**`--help`**, **Documented** [cli] (v2.1.248+): removes the built-in tools that run commands or code and WebFetch (unless named in `--tools`), ignores user, project and local settings files (managed settings and `--settings` still apply), confines the file tools to the working directories including `--add-dir`, and refuses `bypassPermissions`. It is described for evaluation harnesses, and looks like a close fit for the bridge's two lower levels. **Not verified by experiment**, including whether anything the bridge needs from user settings is lost. See Open questions.

### Recommended flag sets

All three sets assume:

- the child is started with `cwd` set to the target directory;
- these common flags are present:

```
--output-format json            # or: stream-json --verbose --include-partial-messages
--permission-prompts none
--setting-sources user
--strict-mcp-config
--settings <bridge-settings.json>
```

where the bridge-owned settings file holds deny rules that no level can override, for example:

```json
{"permissions": {"deny": [
  "Read(~/.config/lifeos/**)", "Read(~/.ssh/**)", "Read(~/.claude/**)",
  "Edit(~/.config/lifeos/**)", "Edit(~/.claude/**)"
]}}
```

Two warnings about this file:

- **It can fail silently.** `claude --help` states that in `-p` mode "Settings files that fail validation are silently ignored". A typo in the bridge settings file would remove the deny rules without any error. The bridge should validate the file itself (well-formed JSON, known keys) and have a test that proves a denied path is actually denied.
- **These deny rules were not tested.** The experiments used no `--settings` file. The `~/` anchor is documented [perms]; its effect in this file is unverified.

`--setting-sources user` keeps the owner's own user-level settings (including user hooks and user allow rules) and drops repository-supplied settings and `.mcp.json`. **Observed**: `--setting-sources ""` is also accepted by the CLI, but an empty value is not documented, so it is not recommended here.

**Level 1: read-only**

```
--permission-mode dontAsk
--tools "Read,Glob,Grep"
```

- No Bash, so no unconfined read-only commands; no Edit/Write; no WebFetch/WebSearch, so no network channel; no Agent tool.
- Reads are limited to the working directory plus any `--add-dir`. **Observed** (E3): in `dontAsk`, `Read` of a file outside the working directory was denied and reported in `permission_denials`.
- To let the run read more folders, add `--add-dir <path>...`.
- The observation was made with no settings files loaded. With `--setting-sources user`, a user-level `Read(...)` allow rule, a `permissions.additionalDirectories` entry or an approving hook would widen what can be read, because allow lists merge across levels. The bridge deny file is the backstop for the secrets directories.

**Level 2: edit only inside listed folders, no shell**

```
--permission-mode dontAsk
--tools "Read,Glob,Grep,Edit,Write"
--allowedTools "Edit(//abs/path/one/**)" "Edit(//abs/path/two/**)"
--add-dir /abs/path/one /abs/path/two      # only for folders outside cwd, so they are readable
```

- **Observed** (E3) with one `Edit(//<abs>/allowed/**)` rule and no settings files loaded: a `Write` inside the folder succeeded; a `Write` to a sibling folder and a `Read` outside the working directory were denied and reported; exit code 0.
- Use `//` absolute paths. Use `Edit(...)`, not `Write(...)`.
- Do not use `acceptEdits` for this level: it approves edits in the whole working directory rather than the listed folders.
- A user-level `permissions.allow` entry for `Edit` would widen this policy, because allow lists merge. Either keep user settings free of broad `Edit` rules, or drop the user source.
- Protected paths inside an allowed folder (for example its `.git` directory) stay denied in `dontAsk`.

**Level 3: full auto**

Two documented options. Choosing between them is a security trade-off for the owner, not a technical detail.

```
# 3a: classifier-reviewed
--permission-mode auto

# 3b: no checks
--permission-mode bypassPermissions        # same as --dangerously-skip-permissions
```

- 3a: the documentation's own unattended example is `claude -p "..." --permission-mode auto --permission-prompts none` [headless]. It needs auto mode to be available for the account and model; otherwise the run silently starts in Manual and most actions are denied. Check `permissionMode` in the `init` event (stream-json) to confirm what was applied. **Not verified by experiment.**
- 3b: documented as for "isolated containers and VMs only". The bridge runs on the owner's real machine with network access, which is exactly the case the warning is about. Deny rules still apply in this mode, so the bridge deny file above still protects the listed paths from the file tools (not from shell commands).
- For either, keep the bridge deny rules, and consider `--max-turns` and `--max-budget-usd`.

---

## 6. Other flags and environment

### Flags

| Flag | Source | Notes |
|---|---|---|
| `--model <alias-or-id>` | `--help`, [cli] | Alias (`sonnet`, `opus`, `haiku`, ...) or full model name. Overrides the `model` setting and `ANTHROPIC_MODEL`. A resumed session continues on its previous model unless overridden. |
| `--fallback-model <list>` | `--help`, [cli] | Comma-separated fallbacks when the main model is overloaded or unavailable. Not restored on resume. |
| `--max-turns <n>` | [cli]; **not in `--help`** | "Limit the number of agentic turns (print mode only). No limit by default." **Observed**: accepted by option parsing on 2.1.328. Result subtype `error_max_turns` when hit (Documented, not observed). `CLAUDE_CODE_MAX_TURNS` is the equivalent variable [env]. |
| `--max-budget-usd <amount>` | `--help`, [cli] | Print mode only. Stops once the **client-side cost estimate** reaches the amount; spend can pass the cap. Totals restored from earlier runs of a resumed session do not count toward it. Result subtype `error_max_budget_usd`. On a subscription this is an estimate at list prices, not money spent. |
| `--append-system-prompt <text>` | `--help`, [cli] | Appends to the default system prompt. Recommended over replacement for adding bridge context. `--append-system-prompt-file <path>` is documented, not in `--help`, and accepted (Observed). |
| `--system-prompt <text>` | `--help`, [cli] | Replaces the whole default prompt, including tool guidance and safety instructions. `--system-prompt-file` likewise (Observed: a missing file gives `Error: System prompt file not found`, exit 1). |
| `--system-prompt-snapshot <on\|off>` | `--help`, [cli] | Default `on`: the prompt is recorded on the first request and reused on resume (section 3). |
| `--mcp-config <file-or-json...>` | `--help`, [cli] | Loads MCP servers. With `-p` the CLI waits for pending servers up to `MCP_TIMEOUT` (30 s default) before the first turn. Invalid entries are skipped and reported only in `mcp_server_errors` of the `init` event. Not restored on resume. |
| `--strict-mcp-config` | `--help`, [cli] | Only servers from `--mcp-config`; ignores `.mcp.json`, user-level and plugin servers. With no `--mcp-config`, no MCP servers load (Observed: `mcp_servers` was `[]`, in the experiment configuration described under Experiments). |
| `--settings <file-or-json>` | `--help`, [cli] | Extra settings for this run; overrides the same keys in user/project files. File at most 2 MiB. Not restored on resume. `/path` rules in such a file anchor at the file's directory. |
| `--setting-sources <list>` | `--help`, [cli] | `user`, `project`, `local`. |
| `--bare` | `--help`, [headless], [cli] | Skips hooks, skills, plugins, MCP discovery, auto memory and `CLAUDE.md`. **Never reads OAuth credentials or the keychain: only `ANTHROPIC_API_KEY` or an `apiKeyHelper`.** A subscription-authenticated bridge therefore **cannot use `--bare`**. Documented note: `--bare` "will become the default for `-p` in a future release". |
| `--disable-slash-commands` | `--help`, [cli] | Disables all skills and commands. Observed: `slash_commands` and `skills` in `init` were empty (in the experiment configuration described under Experiments). Removes the leading-`/` ambiguity for phone input. |
| `--restricted`, `--safe-mode` | `--help`, [cli] | See section 5. `--safe-mode` disables customisations for troubleshooting. |
| `--json-schema <schema>` | `--help`, [headless] | Validated structured output in `structured_output`. |
| `--effort <level>` | `--help` | `low`, `medium`, `high`, `xhigh`, `max`. |
| `-d, --debug`, `--debug-file <path>` | `--help` | Debug logging. May contain prompt contents; keep off by default. |

### Timeouts

- **`--help`**: no overall timeout flag exists. The bridge must enforce its own wall-clock limit.
- **Documented** [headless]: to stop a run cleanly send **SIGINT** first (ends the turn); **SIGTERM** exits with 143 and records no result. After SIGTERM the session can still be resumed; the interrupted turn is left as it is.
- Relevant variables [env]:

| Variable | Default | Meaning |
|---|---|---|
| `API_TIMEOUT_MS` | 600000 | Time for each API request attempt to return response headers. |
| `CLAUDE_CODE_MAX_RETRIES` | 10 | Retries of failed API requests. |
| `CLAUDE_CODE_RETRY_WATCHDOG` | unset | `1` retries 429/529 capacity errors indefinitely, for unattended sessions. Not wanted for a request the phone is waiting on. |
| `BASH_DEFAULT_TIMEOUT_MS` / `BASH_MAX_TIMEOUT_MS` | 120000 / 600000 | Bash tool command timeouts. |
| `MCP_TIMEOUT` / `MCP_TOOL_TIMEOUT` | 30000 / about 28 h | MCP startup and tool timeouts. |
| `CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS` | 600000 | Idle wait for background work after the final turn of a `-p` run. |
| `CLAUDE_CODE_DISABLE_BACKGROUND_TASKS` | unset | `1` removes background execution, so the run cannot linger on it. |
| `CLAUDE_STREAM_IDLE_TIMEOUT_MS` | per watchdog | Stalled-stream watchdog; minimum 300000 when set. |

### Environment variables the parent must not leak into the child

If the bridge is ever started from inside a Claude Code session (for example
from its Bash tool during development), the child inherits that session's
markers.

**Observed** in a Bash-tool subprocess of 2.1.328 (names only):
`CLAUDECODE`, `CLAUDE_CODE_CHILD_SESSION`, `CLAUDE_CODE_ENTRYPOINT`,
`CLAUDE_CODE_EXECPATH`, `CLAUDE_CODE_SESSION_ID`,
`CLAUDE_CODE_SESSION_ATTENDED`, `CLAUDE_CODE_CHILD_NOTES_DIR`,
`CLAUDE_CODE_MESSAGING_SOCKET`, `CLAUDE_CODE_MESSAGING_TOKEN`,
`CLAUDE_EFFORT`, `CLAUDE_PID`.

**Documented** [env]:

| Variable | Why it matters |
|---|---|
| `CLAUDECODE` | Set to `1` in subprocesses Claude Code spawns. |
| `CLAUDE_CODE_CHILD_SESSION` | Marks a nested session. A nested interactive session is excluded from `--resume`, `--continue` and the `claude agents` list. The page says "`claude -p` sessions still persist", but it also documents `CLAUDE_CODE_FORCE_SESSION_PERSISTENCE` for the case where a genuine top-level session is "misclassified as nested" through an inherited marker. |
| `CLAUDE_CODE_SESSION_ID`, `CLAUDE_CODE_MESSAGING_SOCKET`, `CLAUDE_CODE_MESSAGING_TOKEN`, `CLAUDE_PID`, `CLAUDE_EFFORT`, `CLAUDE_JOB_DIR` | Per-session values of the parent session. |
| `ANTHROPIC_API_KEY` | "In non-interactive mode (`-p`), the key is always used when present", instead of the subscription. A leaked key silently moves the bridge to API billing. |
| `ANTHROPIC_AUTH_TOKEN`, `ANTHROPIC_BASE_URL`, `CLAUDE_CODE_USE_BEDROCK`, `CLAUDE_CODE_USE_VERTEX`, `CLAUDE_CODE_USE_FOUNDRY`, `ANTHROPIC_PROFILE` | Change the credential or provider (authentication precedence in [auth]). |
| `CLAUDE_CODE_OAUTH_TOKEN` | A long-lived subscription token from `claude setup-token`; takes precedence over the stored login. Only set it deliberately. |
| `CLAUDE_CONFIG_DIR` | Moves settings, credentials and **session storage**. A different value makes existing sessions invisible. |
| `CLAUDE_CODE_SIMPLE` | Same as `--bare`; breaks subscription auth. |

Recommendation: build the child environment from an allow-list (`HOME`,
`PATH`, `LANG`, `USER`, `XDG_RUNTIME_DIR`, plus the timeouts the bridge
sets on purpose) rather than copying the parent environment and removing
names. At minimum remove every variable starting with `CLAUDE` or
`ANTHROPIC` that the bridge did not set itself. The experiments in this
document were run with all inherited `CLAUDE*` variables removed. The
effect of leaving them in place was **not tested**.

A systemd user service starts with a minimal environment, so this mainly
matters for development and tests.

---

## 7. Plan usage, rate limits and policy

### Which credential a headless run uses

**Documented** [auth], authentication precedence, first match wins:

1. Cloud provider credentials (`CLAUDE_CODE_USE_BEDROCK` / `_VERTEX` / `_FOUNDRY`).
2. `ANTHROPIC_AUTH_TOKEN`.
3. `ANTHROPIC_API_KEY` (always used in `-p` when present).
4. `apiKeyHelper`.
5. `CLAUDE_CODE_OAUTH_TOKEN` (from `claude setup-token`, valid one year).
6. Anthropic profile / federation credentials.
7. Subscription OAuth credentials from `/login`. On Linux these are stored in `~/.claude/.credentials.json`, mode `0600`.

`claude auth status` prints JSON and exits 0 when logged in, 1 when not; `authMethod` is one of `none`, `claude.ai`, `oauth_token`, `api_key`, `api_key_helper`, `third_party` [cli]. Observed: it works without a terminal. The output contains account details, so the bridge should read only `loggedIn` and `authMethod`.

**Observed**: with a subscription login and no API key, the `init` event reported `apiKeySource: "none"`.

A stored login expires. Close to expiry interactive sessions show a warning; after expiry requests fail until `/login` is run again [auth]. Observed: the result object carries `credential_expires_at`. A headless bridge cannot re-login by itself; this needs a human notification.

### How usage is counted

**Subscription login (Pro, Max and similar plans)**

- **Documented** [support-sdk], accessed 2026-11-20 (page shows "Updated over 2 weeks ago"). The article currently consists of two notices, quoted in full:

  > "Update October 7, 2026: Claude Max, Ultra, and Team plans now include monthly API credits. They cover the Claude API, Claude Managed Agents, and the Claude Agent SDK and `claude -p` when you run them with an API key from your linked Claude Console organization. Nothing changes for your plan: when you're signed in with your Claude plan, the Claude Agent SDK and `claude -p` still draw from your subscription limits."

  > "Update June 15, 2026: We've paused the previously-announced changes to Claude Agent SDK usage. For now, nothing has changed: Claude Agent SDK, `claude -p`, and third-party app usage still draw from your subscription limits."

- So, as of 2026-11-20: **a `claude -p` run authenticated with the subscription login draws from the same subscription limits as interactive use.** A change to this was announced earlier in 2026 and then **paused** on 2026-06-15. The article does not describe the paused change, does not say whether or when it will return, and the wording "for now" leaves that open. Details of the paused proposal circulate in third-party posts; they are not reproduced here because they are not in the current official text.
- **Documented** [support-plan]: "Pro and Max plans have a five-hour session limit and a weekly limit." "These limits are shared across Claude and Claude Code, meaning all activity in both tools counts against the same limits." Bridge usage therefore competes with the owner's own interactive use of Claude and Claude Code.
- **Documented** [support-plan]: "Monthly API credits included with the Max plan can't be used in Claude Code or the Claude apps, including for usage credits." Together with the October notice: those credits apply only when `claude -p` runs **with an API key** from the linked Console organisation, not under the subscription login.
- **Documented** [costs], [support-plan]: "usage credits" (extra usage) can be enabled to continue past a plan limit, billed at standard API rates. If enabled on the account, a headless run past the limit may spend them. **Unknown**: whether a `-p` run uses them without any confirmation.

**API key**

- **Documented** [costs], [env]: with `ANTHROPIC_API_KEY`, usage is billed per token to the Console organisation. In `-p` the key is used whenever it is present, even when a subscription login exists.

### Rate-limit behaviour and how it shows up

**Documented**:

- When a plan allowance runs out the message is one of: `You've hit your session limit · resets <time>`, `You've hit your weekly limit · resets <time>`, or a model-family limit [errors]. "Claude Code blocks further requests until the reset time".
- Waiting for the reset and continuing automatically is described for **interactive** sessions [errors]. For unattended runs `CLAUDE_CODE_RETRY_WATCHDOG=1` exists, with `CLAUDE_CODE_RETRY_WATCHDOG_MAX_USAGE_LIMIT_WAIT_MS` to bound the wait [errors].
- Retryable API errors produce `system/api_retry` events with `error` categories such as `rate_limit` (a 429 against quota) and `overloaded` (529) [headless], [sdk-ts]. "A 529 is not your usage limit" [errors].
- `stream-json` carries a `rate_limit_event` [sdk-ts]:

  ```
  {"type":"rate_limit_event","rate_limit_info":{
     "status":"allowed"|"allowed_warning"|"rejected",
     "resetsAt"?: number, "utilization"?: number,
     "errorCode"?: "credits_required", "overageEnabled"?: boolean, ...}}
  ```

  `errorCode: "credits_required"` means the subscription's included usage is exhausted and the session cannot continue without usage credits.
- An `assistant` message can carry `error: "rate_limit"` (or `billing_error`, `authentication_failed`, ...), and the result carries `api_error_status` (the HTTP status) and `terminal_reason` (for example `"api_error"`) [sdk-ts].

**Observed** (E1, limit not reached): one `rate_limit_event` per run with `status: "allowed"`, `resetsAt` (Unix **seconds**), `rateLimitType: "five_hour"`, `overageStatus`, `isUsingOverage`, and `unifiedWindows` with `five_hour` and `seven_day` entries, each holding `utilization` (0–1), `resetsAt` and `status`. Synthetic example:

```json
{"type":"rate_limit_event","rate_limit_info":{"status":"allowed","resetsAt":1900000000,
 "rateLimitType":"five_hour","isUsingOverage":false,
 "unifiedWindows":{"five_hour":{"utilization":0.25,"resetsAt":1900000000,"status":"allowed"},
                   "seven_day":{"utilization":0.40,"resetsAt":1900400000,"status":"allowed"}}}}
```

The fields beyond the documented type are undocumented. They would let the bridge show remaining plan usage on the phone, but only in `stream-json` mode.

**Unknown** (cannot be triggered safely): the exact result object when a plan limit is hit in `-p` mode: which `subtype`, whether `is_error` is true, whether `result` holds the "You've hit your ... limit" text, and the exit code. Handling that does not depend on the answer:

- treat `is_error: true` **or** a non-zero exit as failure;
- surface `result` or `errors[]` verbatim to the phone;
- treat `api_error_status == 429`, an `assistant` `error` of `rate_limit`, or a `rate_limit_event` with `status: "rejected"` as "limit reached", and show `resetsAt`.

### Policy statements relevant to an automated personal tool

**Documented** [legal], quoted:

- "Advertised usage limits for Pro, Max, and Ultra plans assume ordinary, individual usage of Claude Code and the Agent SDK."
- "OAuth authentication is intended exclusively for purchasers of Claude Free, Pro, Max, Ultra, Team, and Enterprise subscription plans and is designed to support ordinary use of Claude Code and other native Anthropic applications."
- "Developers building products or services that interact with Claude's capabilities, including those using the Agent SDK, should use API key authentication through Claude Console or a supported cloud provider. Anthropic does not permit third-party developers to offer Claude.ai login into their own applications, or to route requests through Free, Pro, Max, or Ultra plan credentials on behalf of their users. Moreover, developers may not collect, store, or intermediate Claude.ai credentials or session tokens".
- "Nor does it prevent an end user from signing in to the unmodified Claude Code binary with their own Claude subscription".
- "Anthropic reserves the right to take measures to enforce these restrictions and may do so without prior notice."
- Claude Code usage is subject to the Anthropic Usage Policy; Pro and Max users are under the Consumer Terms of Service.

Facts about the LifeOS design that bear on these statements (no conclusion is
drawn here; see Open questions):

- The bridge runs the unmodified `claude` binary, on the owner's machine, under the owner's own login, for the owner only.
- The bridge never reads, stores or forwards the Claude credentials. The phone authenticates to the bridge with the bridge's own bearer token.
- The repository is public. Anyone who runs it uses their own Claude login on their own machine; the project does not offer Claude login or share one account between people.
- Volume is the judgement call: scheduled or high-frequency automated runs move away from "ordinary, individual usage" in a way that occasional prompts from a phone do not.

---

## Recommended invocation for the bridge

### Common rules

- Start the child with `cwd` = target directory, stdin = the prompt (then closed), stdout and stderr captured separately, and an allow-listed environment (section 6).
- Always pass the permission flags; nothing about permissions is inherited from the session.
- Parse stdout as JSON only if it is non-empty. Failures before the run starts produce stderr only.
- Never log `result`, `permission_denials[].tool_input`, stderr text or transcript lines by default: they contain prompt contents and file contents.

### `/ask`, read-only

```bash
cd "$WORKDIR" && printf '%s' "$PROMPT" | claude -p \
  --output-format json \
  --permission-mode dontAsk \
  --permission-prompts none \
  --tools "Read,Glob,Grep" \
  --setting-sources user \
  --strict-mcp-config \
  --disable-slash-commands \
  --settings "$BRIDGE_SETTINGS_JSON" \
  --max-turns 25
```

Optional: `--model <alias>`, `--add-dir <dir>...`, `--append-system-prompt "<bridge context>"`, `--max-budget-usd <n>`, `--session-id <uuid>` (so the bridge knows the ID before the run finishes).

Streaming variant: replace `--output-format json` with
`--output-format stream-json --verbose --include-partial-messages` and read stdout line by line:

- `system/init` → store `session_id`; optionally verify `permissionMode` and `tools`.
- `stream_event` where `event.type == "content_block_delta"` and `event.delta.type == "text_delta"` → forward `event.delta.text`.
- `system/permission_denied` → optional live notice.
- `result` → final object, same fields as below. If the stream ends without a `result` line, treat the run as failed.

### Resume

```bash
cd "$SESSION_CWD" && printf '%s' "$PROMPT" | claude -p \
  --resume "$SESSION_ID" \
  --output-format json \
  <the same permission, settings and MCP flags as for /ask>
```

Before starting:

1. Look up the session with `get_session_info()` / `list_sessions()` (or the bridge's own record of sessions it created) to get `cwd`.
2. Check that `cwd` still exists and is inside the paths the owner allowed; otherwise refuse.
3. Take the bridge's per-session lock.
4. Run `claude agents --json`; refuse with "session in use" if an entry has the same `sessionId` (section 4).
5. Pass `--add-dir`, `--mcp-config` and `--settings` again if the original run used them.

Use `--fork-session` instead of refusing only if a diverging copy is acceptable.

### JSON fields to parse

| Field | Use |
|---|---|
| `is_error` | success or failure (with the exit code) |
| `subtype` | failure category |
| `result` | answer text (success arm) |
| `errors` | messages (error arms) |
| `session_id` | store for resume; with `--fork-session` it is a new ID |
| `permission_denials` | report "N actions were not permitted", listing `tool_name` only |
| `num_turns`, `duration_ms` | diagnostics |
| `total_cost_usd`, `modelUsage` | estimate only; cumulative across resumes |
| `terminal_reason`, `api_error_status`, `stop_reason` | error classification |

### Error cases to handle

| Case | How it appears | Source |
|---|---|---|
| Invalid flag, bad UUID, missing prompt | exit 1, empty stdout, message on stderr | Observed |
| Session not found (deleted by retention, wrong ID) | exit 1, stderr `No conversation found with session ID: <id>`. stdout is empty with `json` output, and holds one `error_during_execution` result line with `stream-json` output | Observed, [sessions] |
| Session held by a running background session | exit 1, stderr says it is running in the background | [sessions] |
| Tool call denied | exit 0, `is_error: false`, non-empty `permission_denials` | Observed |
| Turn limit | `subtype: "error_max_turns"` | [sdk-ts] |
| Budget cap | `subtype: "error_max_budget_usd"` | [sdk-ts], [cli] |
| Other in-run failure | `subtype: "error_during_execution"`, `errors[]`; or `is_error: true` on the success arm with `api_error_status` | [sdk-ts] |
| Not logged in / login expired | failure "printed as the result on stdout", non-zero exit; `claude auth status` exits 1 | [headless], [cli] |
| Plan limit or rate limit | exact shape unknown; see section 7 | |
| Bridge timeout | bridge sends SIGINT, then SIGTERM; exit 143 after SIGTERM and no result line | [headless] |
| stdin larger than 10 MB | non-zero exit with an error | [headless] |
| stdout is not valid JSON | treat as failure; keep stderr for diagnostics | |
| Working directory missing | bridge refuses before starting | |

---

## Open questions

1. **Subscription billing of headless runs may change.** The change announced for Agent SDK and `claude -p` usage is paused, not withdrawn ("for now"). Re-check [support-sdk] before relying on plan usage for scheduled or frequent bridge runs.
2. **Is a personal phone-to-computer bridge "ordinary, individual usage" under a subscription login?** The quoted policy text allows the owner to sign in to the unmodified binary with their own subscription and restricts products that route other users' requests through plan credentials. Where a single-user automation sits, and at what volume, is a decision for the owner (possibly with a question to Anthropic), including whether to use an API key instead.
3. **`--bare` is announced as the future default for `-p`**, and bare mode does not read the subscription login. When that ships, a subscription-authenticated bridge needs whatever opt-out is provided. The opt-out is not documented yet.
4. **Result shape on a plan limit in `-p` mode** (subtype, `is_error`, text, exit code): unknown.
5. **Exact exit codes** for in-run failures other than SIGTERM (143): unknown.
6. **Does `list_sessions()` return `-p`-created sessions, and which `cwd` does it report** after a session has been resumed from another directory? Needs a test once the Python SDK is a bridge dependency.
7. **In-use detection coverage:** is the `sessionId` in `claude agents --json` always the resumed session's ID, and are desktop-app and IDE-held sessions listed? Needs a test with a real interactive session, with the owner's consent.
8. **`--restricted` as the base for levels 1 and 2:** it matches the goal (no command tools, file tools confined, repository and user settings ignored) but was not tested, including with a subscription login.
9. **Auto mode availability** for the owner's account and chosen model in `-p` runs: not tested.
10. **Usage credits:** if enabled on the account, can a headless run spend them without confirmation after a plan limit?
11. **`--setting-sources ""`** is accepted by the CLI but undocumented; its exact effect was not isolated.
12. **Effect of inherited `CLAUDE_CODE_*` markers on a `-p` child** was not tested; the document recommends removing them.
13. **`--session-id` with an ID that already exists**, and **`--input-format stream-json` message schema** for a long-lived child process: not verified; neither is needed for the first bridge version.
14. **Flags and commands that differ between `claude --help` (2.1.328) and the CLI reference.** The flag names of both were compared mechanically.
    - In the CLI reference but not listed as options in `--help`, and used in this document: `--max-turns`, `--permission-prompt-tool`, `--system-prompt-file`, `--append-system-prompt-file`. All four pass option parsing on 2.1.328. Other flag names appear on the CLI reference page but not in the top-level `--help` (for example `--channels`, `--init`, `--remote`, `--teammate-mode`); some of them belong to subcommands, and none is used in this document.
    - In `--help` but not on the CLI reference page: `--brief`, `--file`. Neither is used here.
    - The `claude daemon` command (`status`, `logs`, `stop`) is documented [agent-view] and works, but is not in the command list of `claude --help`.
    - Re-check all of this after CLI updates.
15. **How does the bridge confirm its `--settings` deny file was applied?** Invalid settings are ignored silently in `-p` mode and the `init` event was not seen to list active rules. A startup self-test (ask for a read of a denied path and expect a `permission_denials` entry) would cost one small run.
