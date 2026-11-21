"""A folder's MCP servers, approved one by one by the owner (#108; decision #85).

An MCP server is a program (or a remote address) that Claude Code starts by
itself: **it runs outside the sandbox**, with the network and the owner's
whole home folder. So nothing here is automatic:

- **Listing** reads ``<folder>/.mcp.json`` only (no parent folders, no
  personal or project settings) and starts nothing. Commands, arguments and
  addresses are shown; the values of environment variables and headers are
  not (they may hold secrets), only their names.
- **Approval** per server, through the approval card (``approvals``,
  ``kind: "mcp_server"``), requested by the owner from the app. It is stored
  with a hash of the server's complete definition, so any change (command,
  arguments, environment, address) needs a new approval.
- **Use**: capable runs of that folder get exactly the approved servers
  whose definition is unchanged (``--mcp-config``, with
  ``--strict-mcp-config`` still on, so nothing else loads), and permission to
  call their tools. Every call still passes the gate and is in the audit
  log; every run that gets servers is in the audit log too. Read-only runs
  never get any. Hooks and project settings stay off.
- Runs cannot change ``.mcp.json`` (``capable.build_policy``), and a changed
  file only means that the server is no longer passed on.
"""

from __future__ import annotations

import contextlib
import hashlib
import json
from datetime import UTC, datetime
from pathlib import Path

from . import approvals, audit, store
from .claude import ClaudeError
from .config import config_dir

KIND = "mcp_server"
FILE_NAME = "mcp-approvals.json"
MAX_BYTES = 64 * 1024
OUTSIDE_SANDBOX = (
    "An approved MCP server runs outside the sandbox: it can use the network and read and "
    "change files anywhere in the home folder. Approve only a server you trust."
)


def _now() -> str:
    return datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z")


def definitions(folder: Path) -> dict[str, dict]:
    """The servers of ``<folder>/.mcp.json``; empty when there is none or it is unreadable."""
    path = folder / ".mcp.json"
    try:
        if path.is_symlink() or not path.is_file() or path.stat().st_size > MAX_BYTES:
            return {}
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    servers = data.get("mcpServers") if isinstance(data, dict) else None
    if not isinstance(servers, dict):
        return {}
    return {
        name: spec
        for name, spec in servers.items()
        if isinstance(name, str)
        and isinstance(spec, dict)
        and name.replace("-", "").replace("_", "").isalnum()
    }


def definition_hash(spec: dict) -> str:
    text = json.dumps(spec, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(text.encode()).hexdigest()


def describe(name: str, spec: dict) -> dict:
    """What the owner is shown: everything except secret values."""
    args = spec.get("args")
    return {
        "name": name,
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


def listing(folder: Path) -> list[dict]:
    approved = _store().get(_key(folder), {})
    found = []
    for name, spec in sorted(definitions(folder).items()):
        item = describe(name, spec)
        stored = approved.get(name)
        if stored is None:
            item["state"] = "not_approved"
        else:
            item["state"] = "approved" if stored.get("hash") == item["hash"] else "changed"
            item["approved_at"] = stored.get("approved_at")
        found.append(item)
    return found


def request(session_id: str, folder: Path, name: str) -> dict:
    """An approval card for one server, showing exactly what would be approved."""
    spec = definitions(folder).get(name)
    if spec is None:
        raise ClaudeError("mcp_server_not_found", 404, "this folder has no MCP server of that name")
    shown = describe(name, spec)
    entry = approvals.add(
        KIND, session_id, _key(folder),
        {"server": shown, "warning": OUTSIDE_SANDBOX, "definition_hash": shown["hash"]},
    )  # fmt: skip
    try:
        audit.record("mcp_server_approval_requested", _detail(entry))
    except audit.AuditUnavailable as exc:
        approvals.take(session_id, entry["id"])
        raise ClaudeError("audit_unavailable", 503, "the audit log could not be written") from exc
    return approvals.public(entry)


def _detail(entry: dict, **extra: object) -> dict:
    server = entry["server"]
    return {"session": entry["session_id"], "folder": entry["folder"], "name": server["name"],
            "hash": server["hash"], "command": server["command"], "url": server["url"],
            **extra}  # fmt: skip


def answer(entry: dict, approve: bool) -> dict:
    """The owner's answer. Approves only the definition that was shown, if still unchanged."""
    folder = Path(entry["folder"])
    name = entry["server"]["name"]
    current = definitions(folder).get(name)
    unchanged = current is not None and definition_hash(current) == entry["definition_hash"]
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
    data.setdefault(_key(folder), {})[name] = {
        "hash": entry["definition_hash"],
        "approved_at": _now(),
    }
    store.write_json(config_dir() / FILE_NAME, data)
    return {"approved": True, "name": name}


def revoke(folder: Path, name: str, session_id: str) -> bool:
    data = _store()
    servers = data.get(_key(folder), {})
    if name not in servers:
        return False
    del servers[name]
    if not servers:
        data.pop(_key(folder), None)
    store.write_json(config_dir() / FILE_NAME, data)
    # Revoking takes something away: it happens even when the log cannot be written.
    with contextlib.suppress(audit.AuditUnavailable):
        audit.record(
            "mcp_server_revoked", {"session": session_id, "folder": _key(folder), "name": name}
        )
    return True


def for_run(folder: Path) -> dict[str, dict]:
    """The approved servers of ``folder`` whose definition is exactly the approved one."""
    approved = _store().get(_key(folder), {})
    return {
        name: spec
        for name, spec in definitions(folder).items()
        if name in approved and approved[name].get("hash") == definition_hash(spec)
    }


def run_config(servers: dict[str, dict]) -> str:
    return json.dumps({"mcpServers": servers}, sort_keys=True)


def allow_rules(servers: dict[str, dict]) -> list[str]:
    return [f"mcp__{name}__*" for name in sorted(servers)]
