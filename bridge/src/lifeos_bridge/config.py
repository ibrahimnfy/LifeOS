"""Configuration loading.

Personal settings live outside the repository in ``~/.config/lifeos/env``
(``KEY=value`` lines, also valid as a shell file). Real environment variables
override the file. ``LIFEOS_CONFIG_DIR`` points somewhere else, e.g. in tests.
"""

from __future__ import annotations

import ipaddress
import os
import stat
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path

CONFIG_DIR_VAR = "LIFEOS_CONFIG_DIR"
ENV_FILE_NAME = "env"
MIN_TOKEN_LENGTH = 32
DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 8787

# Tailscale hands out addresses from these ranges.
_TAILSCALE_NETWORKS = (
    ipaddress.ip_network("100.64.0.0/10"),
    ipaddress.ip_network("fd7a:115c:a1e0::/48"),
)


class ConfigError(Exception):
    """The configuration is missing, unsafe or invalid."""


@dataclass(frozen=True)
class Settings:
    token: str
    host: str = DEFAULT_HOST
    port: int = DEFAULT_PORT

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
    """Allow only localhost or a Tailscale address; never a wildcard."""
    if host == "localhost":
        return host
    try:
        address = ipaddress.ip_address(host)
    except ValueError as exc:
        raise ConfigError(
            f"LIFEOS_BRIDGE_HOST must be an IP address or 'localhost', got {host!r}"
        ) from exc
    if address.is_loopback or any(address in network for network in _TAILSCALE_NETWORKS):
        return host
    raise ConfigError(
        f"LIFEOS_BRIDGE_HOST={host} is refused: the bridge binds only to localhost "
        "or to this machine's Tailscale address, never to a public or wildcard address"
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

    raw_port = values.get("LIFEOS_BRIDGE_PORT") or str(DEFAULT_PORT)
    try:
        port = int(raw_port)
    except ValueError as exc:
        raise ConfigError(f"LIFEOS_BRIDGE_PORT must be a number, got {raw_port!r}") from exc
    if not 1024 <= port <= 65535:
        raise ConfigError("LIFEOS_BRIDGE_PORT must be between 1024 and 65535")

    return Settings(token=token, host=host, port=port)
