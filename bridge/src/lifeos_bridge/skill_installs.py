"""Install a skill into a session's folder only after the owner approved it (#92).

1. **Proposal.** In a capable run the assistant writes a line of its own,
   ``[[skill-install: https://…]]``, naming a ``SKILL.md`` file or a ``.zip``
   or ``.tar.gz`` of a skill folder. The bridge takes the line out of the
   answer, records the proposal (audit log) and shows it as an approval
   card (``approvals``). Anything else in the answer stays as it was.
2. **Quarantine.** The bridge (never the run) downloads it over HTTPS into
   ``skill-quarantine/<id>/`` in the private settings folder: no links, no
   absolute or ``..`` paths, size and count limits. Nothing there is loaded
   or executed; the private settings folder is out of reach of every run.
3. **Approval.** The card shows the source, the file list with sizes, the
   skill's description, warnings (scripts, shell lines, requested tools) and
   the target ``<folder>/.claude/skills/<name>/``. Only ``{"approve": true}``
   copies the files there (as plain files, never executable); a refusal or
   the end of the waiting time removes the quarantine. Every step is in the
   audit log.

A capable run cannot install a skill by itself: the sandbox denies commands
writes to ``.claude`` folders, and the run's permission rules deny Claude
Code's file tools there (``capable.build_policy``; probed by the check).
"""

from __future__ import annotations

import contextlib
import io
import re
import shutil
import tarfile
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path, PurePosixPath

from . import approvals, audit, commands
from .claude import ClaudeError
from .config import config_dir

KIND = "skill_install"
QUARANTINE = "skill-quarantine"
MARKER = re.compile(r"^[ \t]*\[\[skill-install:\s*(\S+?)\s*\]\][ \t]*$", re.MULTILINE)
INSTRUCTIONS = (
    "In the capable mode you may propose installing a skill into this folder: write a line of "
    "its own in the form [[skill-install: https://…]] naming a SKILL.md file or a .zip of the "
    "skill folder. The bridge downloads it, the owner sees its files and decides; never write "
    "into .claude yourself."
)
MAX_DOWNLOAD = 5 * 1024 * 1024
MAX_FILE = 1024 * 1024
MAX_FILES = 100
TIMEOUT = 30
NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")


class _HttpsOnly(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if urllib.parse.urlsplit(newurl).scheme != "https":
            raise ClaudeError("source_refused", 400, "the source may only redirect to HTTPS")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def fetch(url: str) -> bytes:
    """Download ``url`` (HTTPS only, at most ``MAX_DOWNLOAD`` bytes)."""
    opener = urllib.request.build_opener(_HttpsOnly)
    request = urllib.request.Request(url, headers={"User-Agent": "lifeos-bridge"})  # noqa: S310
    with opener.open(request, timeout=TIMEOUT) as response:
        data = response.read(MAX_DOWNLOAD + 1)
    if len(data) > MAX_DOWNLOAD:
        raise ClaudeError("source_too_large", 400, "the download is larger than 5 MB")
    return data


def source_ok(url: str) -> bool:
    parts = urllib.parse.urlsplit(url)
    return parts.scheme == "https" and bool(parts.hostname) and len(url) <= 2000


def _safe(name: str) -> str | None:
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts or not path.parts or len(path.parts) > 8:
        return None
    return path.as_posix()


def unpack(url: str, data: bytes) -> dict[str, bytes]:
    """The skill's files (relative path → content); ``SKILL.md`` at the top."""
    path = urllib.parse.urlsplit(url).path.lower()
    files: dict[str, bytes] = {}
    if path.endswith(".zip"):
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            for info in archive.infolist():
                is_link = (info.external_attr >> 16) & 0o170000 == 0o120000
                if info.is_dir() or is_link:
                    continue
                name = _safe(info.filename)
                if name is None or info.file_size > MAX_FILE:
                    raise ClaudeError("source_refused", 400, "the archive holds an unsafe entry")
                files[name] = archive.read(info)
    elif path.endswith((".tar.gz", ".tgz")):
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
            for member in archive.getmembers():
                if member.isdir():
                    continue
                name = _safe(member.name)
                if not member.isfile() or name is None or member.size > MAX_FILE:
                    raise ClaudeError("source_refused", 400, "the archive holds an unsafe entry")
                handle = archive.extractfile(member)
                files[name] = handle.read() if handle else b""
    else:
        files["SKILL.md"] = data
    if len(files) > MAX_FILES:
        raise ClaudeError("source_refused", 400, "the skill holds too many files")
    tops = {name.split("/")[0] for name in files}
    if "SKILL.md" not in files and len(tops) == 1:  # one folder around everything
        top = next(iter(tops)) + "/"
        files = {name.removeprefix(top): content for name, content in files.items()}
    if "SKILL.md" not in files:
        raise ClaudeError("source_refused", 400, "there is no SKILL.md at the top")
    return files


def skill_name(url: str, files: dict[str, bytes]) -> str:
    fields, _ = commands.split_frontmatter(files["SKILL.md"].decode("utf-8", errors="replace"))
    name = fields.get("name") or PurePosixPath(urllib.parse.urlsplit(url).path).stem
    if name.upper() == "SKILL":
        name = PurePosixPath(urllib.parse.urlsplit(url).path).parent.name
    if not NAME.fullmatch(name or ""):
        raise ClaudeError("source_refused", 400, "the skill has no usable name")
    return name


def target_for(folder: Path, name: str) -> Path:
    return folder / ".claude" / "skills" / name


def _record(action: str, entry: dict, **extra: object) -> None:
    """Install records are not about a run's tool calls: no session id at the top level."""
    detail = {"session": entry["session_id"], "source": entry["source"],
              "name": entry.get("name"), "target": entry.get("target"), **extra}  # fmt: skip
    audit.record(action, detail)


def from_answer(answer: str, session_id: str, folder: Path) -> tuple[str, list[dict]]:
    """Take the proposal lines out of a capable run's answer; one card per proposal."""
    cards = []
    for url in MARKER.findall(answer):
        if not source_ok(url):
            cards.append({"kind": KIND, "source": url[:200], "state": "refused",
                          "error": "only HTTPS sources can be installed",
                          "error_code": "source_not_https"})  # fmt: skip
            continue
        entry = approvals.add(
            KIND, session_id, str(folder), {"source": url, "state": "downloading"}
        )
        try:
            _record("skill_install_proposed", entry)
        except audit.AuditUnavailable:
            approvals.take(session_id, entry["id"])
            cards.append({"kind": KIND, "source": url, "state": "refused",
                          "error": "the audit log could not be written",
                          "error_code": "audit_unavailable"})  # fmt: skip
            continue
        cards.append(approvals.public(entry))
    text = MARKER.sub("", answer).strip("\n") if cards else answer
    return text, cards


def prepare(entry_id: str, fetcher=None) -> dict | None:
    """Download and quarantine one proposal (in the background); the card becomes ready."""
    entry = approvals.load().get(entry_id)
    if entry is None or entry.get("state") != "downloading":
        return entry
    quarantine = config_dir() / QUARANTINE / entry_id
    try:
        files = unpack(entry["source"], (fetcher or fetch)(entry["source"]))
        name = skill_name(entry["source"], files)
        target = target_for(Path(entry["folder"]), name)
        if target.exists():
            raise ClaudeError("skill_exists", 409, "a skill of that name is already installed")
        quarantine.mkdir(parents=True, mode=0o700)
        for relative, content in files.items():
            path = quarantine / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
        text = files["SKILL.md"].decode("utf-8", errors="replace")
        scripts = any(not n.lower().endswith(".md") for n in files)
        described = commands.describe(name, "skill", text, has_scripts=scripts)
        changes = {
            "state": "ready",
            "name": name,
            "target": str(target),
            "description": described["description"],
            "warnings": described["warnings"],
            "files": [{"path": n, "size": len(c)} for n, c in sorted(files.items())],
            "total_size": sum(len(c) for c in files.values()),
            "quarantine": str(quarantine),
        }
    except Exception as error:  # any failure is shown on the card, never raised
        shutil.rmtree(quarantine, ignore_errors=True)
        code, message = failure(error)
        changes = {"state": "failed", "error": message, "error_code": code}
    updated = approvals.update(entry_id, changes)
    if updated is None:  # refused while it was downloading: nothing is kept
        shutil.rmtree(quarantine, ignore_errors=True)
        return None
    with contextlib.suppress(audit.AuditUnavailable):
        _record(f"skill_install_{changes['state']}", updated)
    return updated


def failure(error: Exception) -> tuple[str, str]:
    """A stable code and the sentence shown on a failed card."""
    if isinstance(error, ClaudeError):
        return error.code, error.message
    if isinstance(error, (zipfile.BadZipFile, tarfile.TarError, EOFError)):
        return "source_unreadable", "the archive could not be read"
    return "download_failed", "the download failed"


def answer(entry: dict, approve: bool) -> dict:
    """The owner's answer (``approvals.settle``: the card is taken out only if this returns).

    A refused answer (``still_downloading``, ``skill_exists``,
    ``audit_unavailable``, ``install_failed``) raises and keeps the card and its
    quarantine, so the owner can answer again.
    """
    quarantine = Path(entry["quarantine"]) if entry.get("quarantine") else None
    if approve and entry.get("state") == "downloading":
        raise ClaudeError(
            "still_downloading", 409, "the skill is still being downloaded; try again in a moment"
        )
    if not approve or entry.get("state") != "ready" or quarantine is None:
        with contextlib.suppress(audit.AuditUnavailable):
            why = "owner" if not approve else "not_ready"
            _record("skill_install_refused", entry, why=why)
        if quarantine is not None:
            shutil.rmtree(quarantine, ignore_errors=True)
        return {"installed": False}
    target = Path(entry["target"])
    if target.exists():
        with contextlib.suppress(audit.AuditUnavailable):
            _record("skill_install_refused", entry, why="exists")
        raise ClaudeError("skill_exists", 409, "a skill of that name is already installed")
    try:
        _record("skill_install_approved", entry)  # before anything is written
    except audit.AuditUnavailable as exc:
        raise ClaudeError(
            "audit_unavailable", 503, "the audit log could not be written; nothing installed"
        ) from exc
    staging = target.with_name(f".{target.name}.installing")
    try:
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.rmtree(staging, ignore_errors=True)
        staging.mkdir()
        for source in sorted(quarantine.rglob("*")):
            if source.is_file() and not source.is_symlink():
                path = staging / source.relative_to(quarantine)
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(source.read_bytes())
                path.chmod(0o644)  # plain files: nothing becomes executable
        staging.rename(target)
    except OSError as exc:
        shutil.rmtree(staging, ignore_errors=True)
        raise ClaudeError(
            "install_failed", 500, "the skill could not be written; nothing installed"
        ) from exc
    shutil.rmtree(quarantine, ignore_errors=True)
    return {"installed": True, "name": entry["name"], "target": str(target)}


def expire(entry: dict) -> None:
    if entry.get("quarantine"):
        shutil.rmtree(entry["quarantine"], ignore_errors=True)
    with contextlib.suppress(audit.AuditUnavailable):
        _record("skill_install_expired", entry)
