"""Configuration loading.

Personal settings live outside the repository in ``~/.config/lifeos/env``
(``KEY=value`` lines, also valid as a shell file). Real environment variables
override the file. ``LIFEOS_CONFIG_DIR`` points somewhere else, e.g. in tests.
"""

from __future__ import annotations

import ipaddress
import os
import re
import stat
from collections.abc import Mapping
from dataclasses import dataclass, field
from pathlib import Path

CONFIG_DIR_VAR = "LIFEOS_CONFIG_DIR"
ENV_FILE_NAME = "env"
ALLOWED_PATHS_FILE_NAME = "allowed_paths"
MIN_TOKEN_LENGTH = 32
DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 8787
DEFAULT_ASK_TIMEOUT = 300
DEFAULT_MAX_CONCURRENT = 2
DEFAULT_MAX_TURNS = 25
RUNNERS = ("cli", "sdk")
DEFAULT_SDK_IDLE_MINUTES = 15
DEFAULT_SDK_MAX_LIVE = 3


class ConfigError(Exception):
    """The configuration is missing, unsafe or invalid."""


@dataclass(frozen=True)
class Settings:
    token: str
    host: str = DEFAULT_HOST
    port: int = DEFAULT_PORT
    # Folder in which prompts from the phone run. Checked when a prompt arrives.
    ask_dir: Path = field(default_factory=lambda: Path.home() / "brain")
    ask_timeout: int = DEFAULT_ASK_TIMEOUT
    max_concurrent: int = DEFAULT_MAX_CONCURRENT
    max_turns: int = DEFAULT_MAX_TURNS
    claude_bin: str = "claude"
    # How conversations run: "cli" starts `claude -p` per message; "sdk" keeps
    # one Agent SDK client per active session (read-only runs only).
    runner: str = "cli"
    # SDK runner: a live session closes this long after its last message, and
    # at most this many are live at once (the longest-idle one is closed first).
    sdk_idle_seconds: int = DEFAULT_SDK_IDLE_MINUTES * 60
    sdk_max_live: int = DEFAULT_SDK_MAX_LIVE
    # Fixed model for every run (overrides the per-session choice). Used by the
    # guardrail probes; leave unset in normal use.
    claude_model: str | None = None
    # What a session uses unless it names its own model and effort.
    default_model: str | None = None
    default_effort: str | None = None
    # Model of the bridge's own runs (coach, context map); None = the default.
    background_model: str | None = None
    # Model names the app offers. The CLI validates the choice when a run starts.
    models: tuple[str, ...] = ("opus", "sonnet", "haiku", "fable")
    max_file_mb: int = 50
    # Extra folders the owner allowed. The file is re-read on every use, so
    # changes made through the bridge apply without a restart.
    allowed_paths: tuple[Path, ...] = ()
    allowed_paths_file: Path | None = None
    # Overrides scripts/notify.sh (used by tests).
    notify_script: Path | None = None

    @property
    def allowed_roots(self) -> tuple[Path, ...]:
        """Folders whose sessions may be listed and resumed: ask folder + allowed paths."""
        from .paths import refusal_reason  # local import: paths needs nothing from here

        roots: list[Path] = [self.ask_dir.resolve()]
        listed = read_allowed_paths(self.allowed_paths_file) if self.allowed_paths_file else ()
        for path in (*self.allowed_paths, *listed):
            # The fixed limits apply here too, so a hand-edited file cannot widen them.
            if refusal_reason(path) is not None:
                continue
            resolved = path.expanduser().resolve()
            if resolved not in roots:
                roots.append(resolved)
        return tuple(roots)

    def __repr__(self) -> str:  # never leak the token into logs or tracebacks
        return f"Settings(token=<hidden>, host={self.host!r}, port={self.port!r})"


def config_dir(environ: Mapping[str, str] | None = None) -> Path:
    environ = os.environ if environ is None else environ
    override = environ.get(CONFIG_DIR_VAR)
    return Path(override) if override else Path.home() / ".config" / "lifeos"


def parse_env_text(text: str) -> dict[str, str]:
    """Parse ``KEY=value`` lines. Tolerates comments, ``export`` and quotes."""
    values: dict[str, str] = {}
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[len("export ") :].lstrip()
        key, separator, value = line.partition("=")
        key = key.strip()
        if not separator or not key.replace("_", "").isalnum():
            continue
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        values[key] = value
    return values


def read_env_file(path: Path) -> dict[str, str]:
    """Read the env file; refuse it when other users could read it."""
    if not path.exists():
        return {}
    mode = stat.S_IMODE(path.stat().st_mode)
    if mode & 0o077:
        raise ConfigError(
            f"{path} is readable by other users (mode {mode:o}); run: chmod 600 {path}"
        )
    return parse_env_text(path.read_text(encoding="utf-8"))


def validate_host(host: str) -> str:
    """Allow only the loopback interface: the phone arrives through the relay."""
    if host == "localhost":
        return host
    try:
        address = ipaddress.ip_address(host)
    except ValueError as exc:
        raise ConfigError(
            f"LIFEOS_BRIDGE_HOST must be an IP address or 'localhost', got {host!r}"
        ) from exc
    if address.is_loopback:
        return host
    raise ConfigError(
        f"LIFEOS_BRIDGE_HOST={host} is refused: the bridge binds only to the loopback "
        "interface (127.0.0.1, ::1 or localhost); the phone reaches it through the relay"
    )


def load_settings(environ: Mapping[str, str] | None = None) -> Settings:
    environ = os.environ if environ is None else environ
    env_file = config_dir(environ) / ENV_FILE_NAME
    values = read_env_file(env_file)
    values.update({k: v for k, v in environ.items() if k.startswith("LIFEOS_")})

    token = values.get("LIFEOS_BRIDGE_TOKEN", "")
    if not token:
        raise ConfigError(
            f"LIFEOS_BRIDGE_TOKEN is not set in {env_file}; create it with scripts/init-config.sh"
        )
    if len(token) < MIN_TOKEN_LENGTH:
        raise ConfigError(f"LIFEOS_BRIDGE_TOKEN must be at least {MIN_TOKEN_LENGTH} characters")

    host = validate_host(values.get("LIFEOS_BRIDGE_HOST") or DEFAULT_HOST)

    port = _int_setting(values, "LIFEOS_BRIDGE_PORT", DEFAULT_PORT, 1024, 65535)

    raw_ask_dir = values.get("LIFEOS_ASK_DIR") or "~/brain"
    ask_dir = Path(raw_ask_dir).expanduser()
    if not ask_dir.is_absolute():
        raise ConfigError(
            f"LIFEOS_ASK_DIR must be an absolute path or start with ~, got {raw_ask_dir!r}"
        )

    runner = (values.get("LIFEOS_RUNNER") or "cli").strip().lower()
    if runner not in RUNNERS:
        raise ConfigError(f"LIFEOS_RUNNER must be one of: {', '.join(RUNNERS)}")

    return Settings(
        token=token,
        host=host,
        port=port,
        ask_dir=ask_dir,
        ask_timeout=_int_setting(values, "LIFEOS_ASK_TIMEOUT", DEFAULT_ASK_TIMEOUT, 5, 3600),
        max_concurrent=_int_setting(values, "LIFEOS_MAX_CONCURRENT", DEFAULT_MAX_CONCURRENT, 1, 16),
        max_turns=_int_setting(values, "LIFEOS_MAX_TURNS", DEFAULT_MAX_TURNS, 1, 200),
        claude_bin=values.get("LIFEOS_CLAUDE_BIN") or "claude",
        runner=runner,
        sdk_idle_seconds=60
        * _int_setting(values, "LIFEOS_SDK_IDLE_MINUTES", DEFAULT_SDK_IDLE_MINUTES, 1, 1440),
        sdk_max_live=_int_setting(values, "LIFEOS_SDK_MAX_LIVE", DEFAULT_SDK_MAX_LIVE, 1, 16),
        claude_model=values.get("LIFEOS_CLAUDE_MODEL") or None,
        default_model=values.get("LIFEOS_DEFAULT_MODEL") or "opus",
        default_effort=values.get("LIFEOS_DEFAULT_EFFORT") or "high",
        background_model=values.get("LIFEOS_BACKGROUND_MODEL") or None,
        models=tuple(
            name
            for name in re.split(
                r"[,\s]+", values.get("LIFEOS_MODELS") or "opus,sonnet,haiku,fable"
            )
            if name
        ),
        max_file_mb=_int_setting(values, "LIFEOS_MAX_FILE_MB", 50, 1, 200),
        allowed_paths_file=config_dir(environ) / ALLOWED_PATHS_FILE_NAME,
    )


def read_allowed_paths(path: Path) -> tuple[Path, ...]:
    """One absolute folder per line; ``#`` comments; ``~`` allowed. Others are ignored."""
    if not path.exists():
        return ()
    folders: list[Path] = []
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        folder = Path(line).expanduser()
        # The filesystem root or a relative path would allow far too much.
        if folder.is_absolute() and folder != Path(folder.anchor):
            folders.append(folder)
    return tuple(folders)


def _int_setting(values: Mapping[str, str], name: str, default: int, low: int, high: int) -> int:
    raw = values.get(name) or str(default)
    try:
        number = int(raw)
    except ValueError as exc:
        raise ConfigError(f"{name} must be a number, got {raw!r}") from exc
    if not low <= number <= high:
        raise ConfigError(f"{name} must be between {low} and {high}")
    return number
