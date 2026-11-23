"""The open network of capable runs (owner, #182), and what stays closed.

Capable runs started from the phone reach the internet like the terminal: web
research (the WebFetch and WebSearch tools), package installs, ``gh`` and
``git`` over HTTPS. Commands still run in Claude Code's sandbox; only its
network part is opened. Measured with real runs on Claude Code 2.1.330
(docs/permission-policy.md, section 9.16):

- **Every host name, no IP address.** The sandbox proxy's allow-list is one
  wildcard per top-level domain of the IANA list (``tlds.txt``), so every host
  name passes. An IP address never matches it, in any spelling (``127.1``,
  ``2130706433``, ``0x7f.1``, ``[::1]``, ``::ffff:127.0.0.1``), and the proxy
  itself refuses a host name that resolves to a local address. A bare ``*`` or a
  deny list cannot do this: with ``*`` every spelling of a loopback address
  reached the computer's own services (the bridge, the adb server), and the deny
  list compares spellings only (no address ranges).
- **Direct connections** stay impossible: a sandboxed command has a network
  namespace of its own, with only the proxy.
- **WebFetch** runs outside the sandbox, so the gate refuses a fetch whose host is
  not a public address (``gate.url_refusal``).
- **GitHub credentials are never readable by a run.** The owner's GitHub token
  (``gh auth token``, read by the bridge outside the sandbox) is passed to the run
  as variables the sandbox *masks*: commands see a placeholder, and the sandbox
  proxy, which terminates TLS for this, puts the real value into requests to
  GitHub's hosts only. ``gh`` gets ``GH_TOKEN``; ``git`` gets a pre-encoded HTTP
  header for ``https://github.com/`` (git sends a credential helper's password
  base64-encoded, which the proxy cannot substitute), and SSH addresses of GitHub
  are rewritten to HTTPS (SSH keys stay hidden, and SSH cannot pass the proxy).
- Everything the sandbox hid before stays hidden: the private settings folder
  (bridge token, pairing and push keys), SSH keys, the rest of the home folder.

Approval cards for deleting, pushing and installing are unchanged (``gate``); an
approved command simply runs inside the sandbox, with this network.
"""

from __future__ import annotations

import base64
import functools
import re
import shutil
import subprocess
from pathlib import Path

TLD_FILE = Path(__file__).with_name("tlds.txt")
WEB_TOOLS = ("WebFetch", "WebSearch")
# The hosts that receive the owner's GitHub token (the proxy substitutes it nowhere else).
GH_HOSTS = ("api.github.com", "uploads.github.com", "github.com")
GIT_HOSTS = ("github.com",)
TOKEN_SHAPE = re.compile(r"^[A-Za-z0-9_]{20,255}$")
HEADER_VARIABLE = "GIT_CONFIG_VALUE_0"


@functools.cache
def top_level_domains() -> tuple[str, ...]:
    names = []
    for line in TLD_FILE.read_text(encoding="ascii").splitlines():
        line = line.strip().lower()
        if line and not line.startswith("#"):
            names.append(line)
    return tuple(names)


def allowed_domains() -> list[str]:
    """Every host name, as one wildcard per top-level domain; never an IP address."""
    return [f"*.{name}" for name in top_level_domains()]


def github_token(gh: str | None = None, timeout: float = 10.0) -> str | None:
    """The owner's GitHub token, read outside the sandbox; ``None`` when there is none.

    Never logged, never written anywhere: it goes only into the environment of the
    Claude Code process, whose sandbox masks it (``sandbox_settings``).
    """
    gh = gh or shutil.which("gh")
    if gh is None:
        return None
    try:
        done = subprocess.run(  # noqa: S603
            [gh, "auth", "token", "--hostname", "github.com"],
            capture_output=True, text=True, timeout=timeout, check=False,
            stdin=subprocess.DEVNULL,
        )  # fmt: skip
    except (OSError, subprocess.SubprocessError):
        return None
    token = done.stdout.strip()
    return token if done.returncode == 0 and TOKEN_SHAPE.match(token) else None


def run_env(token: str | None) -> dict[str, str]:
    """Variables of the Claude Code process of an open-network run.

    Without a token: only no terminal prompts for git. With one: ``GH_TOKEN`` for
    ``gh`` and the header for ``git`` (both masked by the sandbox), and GitHub's SSH
    addresses rewritten to HTTPS.
    """
    env = {"GIT_TERMINAL_PROMPT": "0"}
    if token is None:
        return env
    basic = base64.b64encode(f"x-access-token:{token}".encode()).decode()
    entries = [
        ("http.https://github.com/.extraHeader", f"Authorization: Basic {basic}"),
        ("url.https://github.com/.insteadOf", "git@github.com:"),
        ("url.https://github.com/.insteadOf", "ssh://git@github.com/"),
    ]
    env |= {"GH_TOKEN": token, "GIT_CONFIG_COUNT": str(len(entries))}
    for index, (key, value) in enumerate(entries):
        env[f"GIT_CONFIG_KEY_{index}"] = key
        env[f"GIT_CONFIG_VALUE_{index}"] = value
    return env


def sandbox_settings(*, github: bool) -> dict:
    """The ``network`` (and, with a GitHub token, ``credentials``) part of the sandbox."""
    network: dict = {
        "allowedDomains": allowed_domains(),
        "strictAllowlist": True,
        "allowAllUnixSockets": False,
    }
    if not github:
        return {"network": network}
    network["tlsTerminate"] = {}  # needed by the masks: the proxy must see the requests
    credentials = {
        "envVars": [
            {"name": "GH_TOKEN", "mode": "mask", "injectHosts": list(GH_HOSTS)},
            {
                "name": HEADER_VARIABLE,
                "mode": "mask",
                "extract": r"Basic (\S+)",
                "onExtractNoMatch": "deny",
                "injectHosts": list(GIT_HOSTS),
            },
        ]
    }
    return {"network": network, "credentials": credentials}


CLOSED = {"allowedDomains": [], "strictAllowlist": True, "allowAllUnixSockets": False}
