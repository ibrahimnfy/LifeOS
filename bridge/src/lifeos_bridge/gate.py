"""Gate program for capable runs.

Claude Code runs this as a ``PreToolUse`` hook before every tool call of a
capable run (``python -m lifeos_bridge.gate --audit-dir DIR``). It

1. writes the call to the local audit log *before* the tool runs, and blocks
   the call if the log cannot be written;
2. refuses actions that are always denied, and actions that need the owner's
   approval, with an explanation the model passes on. With ``--ask-host``
   (SDK runner only: the bridge itself answers permission prompts) an
   approval action is not refused here but answered with ``ask``: Claude Code
   then asks the bridge, which asks the owner and allows the call only after
   an explicit yes (``sdk_runner``).

Exit status 0 lets the call continue; 2 blocks it and the text on standard
error is shown to the model. Anything unexpected blocks (fail closed).

The command patterns below recognise the *obvious* cases only. They are an
explanation layer; real enforcement is the OS sandbox the commands run in
(see docs/permission-policy.md). Since the open network of #182 the sandbox no
longer backs the approvals for pushing and installing: an approval card is
what stands between a run and a push, an install or a GitHub change.

WebFetch runs in Claude Code's own process, outside the sandbox: a fetch whose
host is not a public address (the computer's own services, a private network)
is refused here (``url_refusal``).

Standard library only: the bridge runs a copy of this file (``program.pin``).
"""

from __future__ import annotations

import argparse
import ipaddress
import json
import os
import re
import socket
import sys
import urllib.parse
from datetime import UTC, datetime
from pathlib import Path

ALLOW, BLOCK = 0, 2

APPROVAL_NOTE = (
    "This needs the owner's explicit approval, and approving from the phone is not "
    "available yet. Do not try to reach the same result another way. Tell the owner "
    "exactly what you wanted to run and why."
)
NEVER_NOTE = (
    "This is never allowed for prompts from the phone. Do not try another way; tell the owner."
)


def _commands(*names: str) -> re.Pattern[str]:
    """Match any of ``names`` where a command can start."""
    joined = "|".join(re.escape(n).replace(r"\ ", r"\s+") for n in names)
    return re.compile(
        rf"(?:^|[;&|(){{}}`\n]|\$\(|\b(?:xargs|exec|env|nohup|time)\s)\s*(?:\w+=\S*\s+)*"
        rf"(?:{joined})(?=\s|$|;|&|\|)"
    )


# (category, human-readable reason, note, pattern)
RULES: list[tuple[str, str, str, re.Pattern[str]]] = [
    (
        "privilege",
        "running commands as another user or as administrator",
        NEVER_NOTE,
        _commands("sudo", "su", "pkexec", "doas", "run0"),
    ),
    (
        "system_settings",
        "changing system or desktop settings",
        NEVER_NOTE,
        _commands(
            "systemctl",
            "gsettings",
            "dconf",
            "timedatectl",
            "hostnamectl",
            "localectl",
            "nmcli",
            "ufw",
            "iptables",
            "nft",
            "sysctl",
            "crontab",
            "update-alternatives",
            "loginctl",
            "systemd-run",
            "dbus-send",
            "gdbus",
            "busctl",
            "chsh",
            "passwd",
            "usermod",
            "mount",
            "umount",
        ),
    ),
    (
        "deletion",
        "permanently deleting files",
        APPROVAL_NOTE,
        _commands(
            "rm",
            "rmdir",
            "unlink",
            "shred",
            "truncate",
            "wipe",
            "dd",
            "mkfs",
            "git clean",
            "git rm",
            "trash-empty",
        ),
    ),
    (
        "deletion",
        "permanently deleting files",
        APPROVAL_NOTE,
        re.compile(r"\bfind\b[^;&|]*\s-(?:delete|exec\s+rm)\b"),
    ),
    (
        "force",
        "a force or history-rewriting operation",
        APPROVAL_NOTE,
        _commands(
            "git reset",
            "git rebase",
            "git filter-branch",
            "git filter-repo",
            "git stash drop",
            "git stash clear",
            "git reflog expire",
            "git gc",
            "git update-ref",
            "git checkout --",
            "git restore",
            "git commit --amend",
        ),
    ),
    (
        "force",
        "a force or history-rewriting operation",
        APPROVAL_NOTE,
        re.compile(r"\bgit\b[^;&|]*\s(?:--force(?:-with-lease)?|-f|-D|--hard|--delete)(?=\s|$)"),
    ),
    (
        "push",
        "pushing to a remote repository",
        APPROVAL_NOTE,
        re.compile(r"\bgit\b[^;&|]*\spush(?=\s|$)"),
    ),
    (
        # The open network (#182): gh works with the owner's GitHub account, so what
        # publishes or changes something there is asked for like a push.
        "push",
        "changing something on GitHub (a pull request, issue, release, repository, ...)",
        APPROVAL_NOTE,
        re.compile(
            r"\bgh\b[^;&|]*\s(?:"
            r"pr\s+(?:create|merge|close|reopen|comment|review|edit|ready|lock|unlock)"
            r"|issue\s+(?:create|close|reopen|comment|edit|delete|transfer|lock|unlock|pin|unpin)"
            r"|release\s+(?:create|delete|edit|upload)"
            r"|repo\s+(?:create|delete|edit|fork|rename|archive|unarchive|sync|deploy-key)"
            r"|gist\s+(?:create|delete|edit)|secret|variable|ssh-key|gpg-key|auth"
            r"|workflow\s+(?:run|enable|disable)|run\s+(?:cancel|rerun|delete)"
            r"|label\s+(?:create|delete|edit|clone)|cache\s+delete"
            r"|api\b[^;&|]*\s(?:-X|--method|-f|-F|--field|--raw-field|--input)"
            r"|api\s+graphql)(?=\s|$)"
        ),
    ),
    (
        "install",
        "installing packages",
        APPROVAL_NOTE,
        re.compile(
            r"\b(?:apt|apt-get|dpkg|snap|flatpak|brew|pip|pip3|pipx|uv|npm|pnpm|yarn|bun|cargo|gem|go|"
            r"conda|mamba|poetry)\b[^;&|]*\s(?:install|add|i|-i|--install|sync|upgrade|update)(?=\s|$)"
        ),
    ),
    (
        "message",
        "sending a message or e-mail",
        APPROVAL_NOTE,
        _commands(
            "mail",
            "mailx",
            "sendmail",
            "mutt",
            "msmtp",
            "swaks",
            "signal-cli",
            "telegram-cli",
            "notify-send",
            "wall",
            "write",
        ),
    ),
]

# The open network of #182: commands that use the network (curl, wget, gh, git fetch, ...)
# are no longer a category of their own. Messages and pushes still are.
LOCAL_FETCH = ("local_network", "fetching the computer's own services or a private network")


def _public(address: str) -> bool:
    try:
        parsed = ipaddress.ip_address(address.split("%")[0])
    except ValueError:
        return False
    if isinstance(parsed, ipaddress.IPv6Address) and parsed.ipv4_mapped is not None:
        parsed = parsed.ipv4_mapped
    return parsed.is_global


def url_refusal(url: str, resolve=socket.getaddrinfo) -> str | None:
    """Why WebFetch must not fetch ``url`` (its host is not a public address), or ``None``.

    Found with a real run (#182): without it WebFetch reached a listener on 127.0.0.1.
    Every address the host resolves to must be public; ``127.1``, ``2130706433`` and
    names that resolve to the computer itself resolve to loopback and are refused.
    """
    try:
        host = urllib.parse.urlsplit(str(url).strip()).hostname
    except ValueError:
        host = None
    if not host:
        return "the address names no host"
    host = host.rstrip(".")
    if host == "localhost" or host.endswith(".localhost"):
        return "the computer's own services are never fetched"
    try:
        found = {item[4][0] for item in resolve(host, None)}
    except (OSError, UnicodeError):
        return "the host could not be resolved"
    if not found or not all(_public(str(address)) for address in found):
        return "the host is the computer itself or a private network"
    return None


def classify(command: str) -> tuple[str, str, str] | None:
    """Return ``(category, reason, note)`` for a refused command, else ``None``."""
    for category, reason, note, pattern in RULES:
        if pattern.search(command):
            return category, reason, note
    return None


def _detail(tool: str, tool_input: object) -> dict:
    data = tool_input if isinstance(tool_input, dict) else {}
    if tool == "Bash":
        return {"command": str(data.get("command", ""))}
    keys = {
        "WebFetch": ("url",),
        "Read": ("file_path",),
        "Edit": ("file_path",),
        "Write": ("file_path",),
        "NotebookEdit": ("notebook_path",),
        "Glob": ("pattern", "path"),
        "Grep": ("pattern", "path", "glob"),
    }.get(tool)
    if keys is None:
        return {"input_keys": sorted(data)}
    # File contents are deliberately not copied into the log.
    return {key: str(data[key]) for key in keys if key in data}


def write_audit(audit_dir: Path, entry: dict) -> None:
    audit_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
    path = audit_dir / f"{entry['ts'][:7]}.jsonl"
    line = (json.dumps(entry, ensure_ascii=False) + "\n").encode()
    descriptor = os.open(path, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
    try:
        os.write(descriptor, line)
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


ASK_REASON = "This needs the owner's confirmation on the phone before it runs."


def ask_output(reason: str) -> str:
    """Hook answer that sends the call to the permission host (never approves it)."""
    return json.dumps(
        {
            "hookSpecificOutput": {
                "hookEventName": "PreToolUse",
                "permissionDecision": "ask",
                "permissionDecisionReason": f"{ASK_REASON} ({reason})",
            }
        }
    )


def decide(payload: dict, audit_dir: Path, *, ask_host: bool = False) -> tuple[int, str]:
    """``(status, text)``: BLOCK puts ``text`` on stderr; ALLOW with text prints the JSON."""
    tool = payload.get("tool_name")
    if not isinstance(tool, str) or not tool:
        return BLOCK, "Blocked: the tool call could not be identified."
    detail = _detail(tool, payload.get("tool_input"))
    refusal = classify(detail["command"]) if tool == "Bash" else None
    if tool == "WebFetch":
        why = url_refusal(detail.get("url", ""))
        if why is not None:
            refusal = (LOCAL_FETCH[0], f"{LOCAL_FETCH[1]}: {why}", NEVER_NOTE)
    entry = {
        "ts": datetime.now(UTC).isoformat(timespec="milliseconds"),
        "session_id": str(payload.get("session_id", "")),
        "cwd": str(payload.get("cwd", "")),
        "tool": tool,
        "detail": detail,
        "decision": "refused" if refusal else "allowed",
    }
    ask = bool(refusal) and ask_host and refusal[2] == APPROVAL_NOTE
    if ask:
        entry["decision"] = "asked"
    if refusal:
        entry["category"] = refusal[0]
    try:
        write_audit(audit_dir, entry)
    except OSError:
        return BLOCK, (
            "Blocked: the audit log could not be written, so nothing may run. "
            "Stop and tell the owner that the audit log is unavailable."
        )
    if ask:
        return ALLOW, ask_output(refusal[1])
    if refusal:
        return BLOCK, f"Refused: {refusal[1]}. {refusal[2]}"
    return ALLOW, ""


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="lifeos-gate")
    parser.add_argument("--audit-dir", required=True, type=Path)
    parser.add_argument("--ask-host", action="store_true")
    args = parser.parse_args(argv)
    try:
        payload = json.loads(sys.stdin.read())
        if not isinstance(payload, dict):
            raise ValueError
    except ValueError:
        print("Blocked: the gate received an unreadable request.", file=sys.stderr)
        return BLOCK
    status, message = decide(payload, args.audit_dir, ask_host=args.ask_host)
    if message:
        print(message, file=sys.stderr if status == BLOCK else sys.stdout)
    return status


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception:  # anything unexpected blocks
        print("Blocked: the gate failed.", file=sys.stderr)
        sys.exit(BLOCK)
