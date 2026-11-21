"""MCP servers, approved one by one by the owner (#108, decision #85; personal ones #162).

An MCP server is a program (or a remote address) that Claude Code starts by
itself: **it runs outside the sandbox**, with the network and the owner's
whole home folder. So nothing here is automatic:

- **Two places are read**, and nothing is started:
  - the session folder's own ``<folder>/.mcp.json`` (scope ``folder``; no
    parent folders, no project settings);
  - the owner's personal, user-scope servers (scope ``personal``): the
    top-level ``mcpServers`` of ``~/.claude.json``, where the official
    Claude Code documentation says user-scope servers are stored. Only that
    key is read: nothing of the per-project entries of that file (local
    scope), no claude.ai connectors, no plugins.
  Only plain stdio and http definitions count, in both places (``plain``;
  for a folder's servers since #171): any other holds something the approval
  card does not show, for example a helper program for headers. It is listed
  as ``unsupported`` and can never be approved or passed on.
  Commands, arguments and addresses are shown; the values of environment
  variables and headers are not (they may hold secrets), only their names.
- **Approval** per server, through the approval card (``approvals``,
  ``kind: "mcp_server"``), requested by the owner from the app. It is stored
  with a hash of the server's complete definition, so any change (command,
  arguments, environment, address) needs a new approval. A folder's server
  is approved for that folder; a personal one once, for every folder.
- **Use**: capable runs get exactly the approved servers whose definition is
  unchanged: the folder's own and the owner's personal ones (the folder's
  wins a shared name). They are passed with ``--mcp-config`` as a file in the
  private settings folder (never on the command line: definitions may hold
  secrets), with ``--strict-mcp-config`` still on, so nothing else loads, and
  with permission to call their tools. Every call still passes the gate and
  is in the audit log; every run that gets servers is in the audit log too.
  Read-only runs never get any. Hooks and project settings stay off.
- Runs cannot change ``.mcp.json`` (``capable.build_policy``) or the owner's
  personal file (outside every allowed folder), and a changed definition
  only means that the server is no longer passed on.
"""

from __future__ import annotations

import contextlib
import hashlib
import json
import os
import threading
import time
from datetime import UTC, datetime
from pathlib import Path

from . import approvals, audit, store
from .claude import ClaudeError
from .config import config_dir

KIND = "mcp_server"
FOLDER, PERSONAL = "folder", "personal"
SCOPES = (FOLDER, PERSONAL)
FILE_NAME = "mcp-approvals.json"
USED_FILE = "mcp-used.json"  # which servers an answer had, for the history (#157)
RUN_DIR = "mcp-run"  # the --mcp-config files of runs
RUN_KEEP_SECONDS = 24 * 3600
USED_PER_SESSION = 200
USED_SESSIONS = 200
_used_lock = threading.Lock()
MAX_BYTES = 64 * 1024
# The owner's personal file also holds Claude Code's own state and can be large.
PERSONAL_MAX_BYTES = 32 * 1024 * 1024
# What a definition may consist of, in a folder's file and in the personal one.
# Anything else (a helper command for headers, sign-in settings, another transport)
# is not shown on the card, so such a server is never offered for approval.
PLAIN_KEYS = {
    "stdio": frozenset({"type", "command", "args", "env", "timeout"}),
    "http": frozenset({"type", "url", "headers", "timeout"}),
}
OUTSIDE_SANDBOX = (
    "An approved MCP server runs outside the sandbox: it can use the network and read and "
    "change files anywhere in the home folder. Approve only a server you trust."
)


def _now() -> str:
    return datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z")


def _named(servers: object) -> dict[str, dict]:
    if not isinstance(servers, dict):
        return {}
    return {
        name: spec
        for name, spec in servers.items()
        if isinstance(name, str)
        and isinstance(spec, dict)
        and name.replace("-", "").replace("_", "").isalnum()
    }


def definitions(folder: Path) -> dict[str, dict]:
    """The servers of ``<folder>/.mcp.json``; empty when there is none or it is unreadable."""
    path = folder / ".mcp.json"
    try:
        if path.is_symlink() or not path.is_file() or path.stat().st_size > MAX_BYTES:
            return {}
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    return _named(data.get("mcpServers") if isinstance(data, dict) else None)


def personal_definitions(path: Path | None) -> dict[str, dict]:
    """The owner's user-scope servers: the top-level ``mcpServers`` of ``path``, nothing else."""
    if path is None:
        return {}
    try:
        if path.is_symlink() or not path.is_file() or path.stat().st_size > PERSONAL_MAX_BYTES:
            return {}
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    return _named(data.get("mcpServers") if isinstance(data, dict) else None)


def _texts(value: object) -> bool:
    return isinstance(value, dict) and all(
        isinstance(k, str) and isinstance(v, str) for k, v in value.items()
    )


def plain(spec: dict) -> bool:
    """A stdio or http definition made only of what the approval card shows."""
    kind = spec.get("type", "stdio")
    if not isinstance(kind, str) or kind not in PLAIN_KEYS or not set(spec) <= PLAIN_KEYS[kind]:
        return False
    timeout = spec.get("timeout")
    if timeout is not None and (isinstance(timeout, bool) or not isinstance(timeout, int | float)):
        return False
    if kind == "http":
        url = spec.get("url")
        return (
            isinstance(url, str)
            and url.startswith(("https://", "http://"))
            and ("headers" not in spec or _texts(spec["headers"]))
        )
    args = spec.get("args", [])
    return (
        isinstance(spec.get("command"), str)
        and bool(spec["command"])
        and isinstance(args, list)
        and all(isinstance(arg, str) for arg in args)
        and ("env" not in spec or _texts(spec["env"]))
    )


def definition_hash(spec: dict) -> str:
    text = json.dumps(spec, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(text.encode()).hexdigest()


def describe(name: str, spec: dict, scope: str = FOLDER) -> dict:
    """What the owner is shown: everything except secret values."""
    args = spec.get("args")
    return {
        "name": name,
        "scope": scope,
        "type": str(spec.get("type") or ("stdio" if spec.get("command") else "http")),
        "command": spec.get("command") if isinstance(spec.get("command"), str) else None,
        "args": [str(a) for a in args] if isinstance(args, list) else [],
        "url": spec.get("url") if isinstance(spec.get("url"), str) else None,
        "env_names": sorted(spec["env"]) if isinstance(spec.get("env"), dict) else [],
        "header_names": sorted(spec["headers"]) if isinstance(spec.get("headers"), dict) else [],
        "hash": definition_hash(spec),
        "outside_sandbox": True,
    }


def _store() -> dict:
    data = store.read_json(config_dir() / FILE_NAME, {})
    return data if isinstance(data, dict) else {}


def _key(folder: Path) -> str:
    return str(folder.resolve())


def _personal_key(path: Path) -> str:
    """Where approvals of personal servers are kept: per personal file, so the scratch
    file of the guardrail check can never touch an approval of the owner's real one."""
    return f"{PERSONAL}:{path.resolve()}"


def _source(scope: str, folder: Path, personal_file: Path | None) -> tuple[str, dict[str, dict]]:
    """``(key of the approvals, definitions)`` of one scope."""
    if scope == PERSONAL:
        if personal_file is None:
            return "", {}
        return _personal_key(personal_file), personal_definitions(personal_file)
    return _key(folder), definitions(folder)


def _listed(scope: str, key: str, found: dict[str, dict]) -> list[dict]:
    approved = _store().get(key, {})
    items = []
    for name, spec in sorted(found.items()):
        item = describe(name, spec, scope)
        stored = approved.get(name)
        if not plain(spec):
            item["state"] = "unsupported"  # never approvable, never passed on
        elif stored is None:
            item["state"] = "not_approved"
        else:
            item["state"] = "approved" if stored.get("hash") == item["hash"] else "changed"
            item["approved_at"] = stored.get("approved_at")
        items.append(item)
    return items


def listing(folder: Path, personal_file: Path | None = None) -> list[dict]:
    """The folder's servers, then the owner's personal ones (``scope`` says which)."""
    return _listed(FOLDER, *_source(FOLDER, folder, None)) + (
        _listed(PERSONAL, *_source(PERSONAL, folder, personal_file)) if personal_file else []
    )


def resolve_scope(
    folder: Path, name: str, scope: str | None, personal_file: Path | None = None
) -> str:
    """The scope a request means: the one named, else the folder's own, else personal."""
    if scope is not None:
        if scope not in SCOPES:
            raise ClaudeError("bad_request", 400, "scope must be folder or personal")
        return scope
    if name not in definitions(folder) and name in personal_definitions(personal_file):
        return PERSONAL
    return FOLDER


def request(
    session_id: str,
    folder: Path,
    name: str,
    scope: str = FOLDER,
    personal_file: Path | None = None,
) -> dict:
    """An approval card for one server, showing exactly what would be approved."""
    _, found = _source(scope, folder, personal_file)
    spec = found.get(name)
    if spec is None:
        raise ClaudeError("mcp_server_not_found", 404, "there is no MCP server of that name")
    if not plain(spec):
        raise ClaudeError(
            "mcp_server_unsupported",
            409,
            "only plain stdio and http definitions can be approved; this one has settings "
            "the approval card cannot show",
        )
    shown = describe(name, spec, scope)
    with approvals.LOCK:
        now = time.time()
        for waiting in approvals.load().values():
            if (
                waiting.get("kind") == KIND
                and waiting.get("session_id") == session_id
                and waiting.get("folder") == _key(folder)
                and waiting.get("scope", FOLDER) == scope
                and (waiting.get("server") or {}).get("name") == name
                and waiting.get("definition_hash") == shown["hash"]
                and waiting.get("expires", 0) > now
            ):
                # The same definition waits already: that card, and no second push.
                return approvals.public(waiting) | {"already_waiting": True}
        entry = approvals.add(
            KIND, session_id, _key(folder),
            {"server": shown, "warning": OUTSIDE_SANDBOX, "definition_hash": shown["hash"],
             "scope": scope},
        )  # fmt: skip
        try:
            audit.record("mcp_server_approval_requested", _detail(entry))
        except audit.AuditUnavailable as exc:
            approvals.take(session_id, entry["id"])
            raise ClaudeError(
                "audit_unavailable", 503, "the audit log could not be written"
            ) from exc
    return approvals.public(entry) | {"already_waiting": False}


def _detail(entry: dict, **extra: object) -> dict:
    server = entry["server"]
    return {"session": entry["session_id"], "folder": entry["folder"], "name": server["name"],
            "scope": entry.get("scope", FOLDER), "hash": server["hash"],
            "command": server["command"], "url": server["url"], **extra}  # fmt: skip


def answer(entry: dict, approve: bool, personal_file: Path | None = None) -> dict:
    """The owner's answer. Approves only the definition that was shown, if still unchanged."""
    folder = Path(entry["folder"])
    name = entry["server"]["name"]
    scope = entry.get("scope", FOLDER)
    key, found = _source(scope, folder, personal_file)
    current = found.get(name)
    unchanged = (
        current is not None
        and definition_hash(current) == entry["definition_hash"]
        and plain(current)
    )
    if not approve or not unchanged:
        why = "owner" if not approve else "changed"
        with contextlib.suppress(audit.AuditUnavailable):
            audit.record("mcp_server_refused", _detail(entry, why=why))
        return {"approved": False, "reason": None if not approve else "the definition changed"}
    try:
        audit.record("mcp_server_approved", _detail(entry))  # before it is stored
    except audit.AuditUnavailable as exc:
        raise ClaudeError(
            "audit_unavailable", 503, "the audit log could not be written; nothing was approved"
        ) from exc
    data = _store()
    data.setdefault(key, {})[name] = {
        "hash": entry["definition_hash"],
        "approved_at": _now(),
    }
    store.write_json(config_dir() / FILE_NAME, data)
    return {"approved": True, "name": name}


def revoke(
    folder: Path,
    name: str,
    session_id: str,
    scope: str = FOLDER,
    personal_file: Path | None = None,
) -> bool:
    if scope == PERSONAL and personal_file is None:
        return False
    key = _personal_key(personal_file) if scope == PERSONAL else _key(folder)
    data = _store()
    servers = data.get(key, {})
    if name not in servers:
        return False
    del servers[name]
    if not servers:
        data.pop(key, None)
    store.write_json(config_dir() / FILE_NAME, data)
    # Revoking takes something away: it happens even when the log cannot be written.
    with contextlib.suppress(audit.AuditUnavailable):
        audit.record(
            "mcp_server_revoked",
            {"session": session_id, "folder": _key(folder), "name": name, "scope": scope},
        )
    return True


def _approved(key: str, found: dict[str, dict]) -> dict[str, dict]:
    approved = _store().get(key, {})
    return {
        name: spec
        for name, spec in found.items()
        if name in approved
        and approved[name].get("hash") == definition_hash(spec)
        and plain(spec)  # also an approval from before only plain ones could be approved
    }


def personal_for_run(personal_file: Path | None) -> dict[str, dict]:
    """The owner's approved personal servers whose definition is exactly the approved one."""
    if personal_file is None:
        return {}
    return _approved(_personal_key(personal_file), personal_definitions(personal_file))


def for_run(folder: Path, personal_file: Path | None = None) -> dict[str, dict]:
    """The approved, unchanged servers of a capable run in ``folder``.

    The folder's own, and (when ``personal_file`` is given) the owner's
    personal ones; the folder's wins a shared name.
    """
    return personal_for_run(personal_file) | _approved(_key(folder), definitions(folder))


def scopes_for_run(folder: Path, servers: dict[str, dict]) -> dict[str, str]:
    """Name → scope of the servers ``for_run`` returned (for the answer and the audit log)."""
    own = _approved(_key(folder), definitions(folder))
    return {name: FOLDER if name in own else PERSONAL for name in sorted(servers)}


def remember_used(session_id: str, exchange_id: str, names: list[str]) -> None:
    """Which servers an answer had (#157), so its history can show them again.

    Only names, keyed by the history's exchange id; the last ``USED_PER_SESSION``
    answers of the last ``USED_SESSIONS`` sessions.
    """
    with _used_lock:
        data = store.read_json(config_dir() / USED_FILE, {})
        data = data if isinstance(data, dict) else {}
        answers = data.pop(session_id, None)
        answers = answers if isinstance(answers, dict) else {}
        answers.pop(exchange_id, None)
        answers[exchange_id] = sorted(names)
        data[session_id] = dict(list(answers.items())[-USED_PER_SESSION:])
        store.write_json(config_dir() / USED_FILE, dict(list(data.items())[-USED_SESSIONS:]))


def used_in(session_id: str) -> dict[str, list[str]]:
    """Exchange id → names of the servers that answer had."""
    data = store.read_json(config_dir() / USED_FILE, {})
    answers = data.get(session_id) if isinstance(data, dict) else None
    return answers if isinstance(answers, dict) else {}


def run_config(servers: dict[str, dict]) -> str:
    return json.dumps({"mcpServers": servers}, sort_keys=True)


def run_config_file(servers: dict[str, dict]) -> Path:
    """The ``--mcp-config`` file of a run, in the private settings folder (mode 600).

    Definitions may hold secrets (environment variables, headers), so they are
    never put on a command line, where other programs could read them. One
    file per content; files not used for a day are removed.
    """
    text = run_config(servers)
    folder = config_dir() / RUN_DIR
    folder.mkdir(mode=0o700, parents=True, exist_ok=True)
    path = folder / (hashlib.sha256(text.encode()).hexdigest()[:32] + ".json")
    now = time.time()
    if not path.is_file():
        building = folder / f".{path.name}.{os.getpid()}"
        descriptor = os.open(building, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            handle.write(text)
        os.replace(building, path)
    with contextlib.suppress(OSError):
        os.utime(path, (now, now))  # in use
        for old in folder.iterdir():
            if old != path and now - old.stat().st_mtime > RUN_KEEP_SECONDS:
                old.unlink()
    return path


def allow_rules(servers: dict[str, dict]) -> list[str]:
    return [f"mcp__{name}__*" for name in sorted(servers)]
