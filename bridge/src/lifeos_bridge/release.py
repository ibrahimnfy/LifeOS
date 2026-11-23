"""Release builds of the app: register one, offer it to the paired phones (#138).

``lifeos-bridge release`` takes the signed release APK from the app's build
output (optionally building it first with Gradle, which reads the signing
key and its password from ``~/.config/lifeos/release/`` itself; the bridge
never sees them), and registers it as the current release:

- version code and name from Gradle's ``output-metadata.json`` next to the
  APK (or given on the command line for another file);
- size, SHA-256 of the file, and SHA-256 of the signing certificate, read
  from the APK Signature Scheme v2/v3 block (an unsigned APK is refused);
- the first registered certificate is pinned: a release signed with another
  key is refused unless the owner says ``--new-certificate``;
- a private copy in ``~/.config/lifeos/app-release/`` (the build output may
  change later), the record in ``app-release.json``, an audit record, and a
  sealed push hint ``update`` to the paired phones.

The phone asks ``GET /app/release`` and fetches the APK with
``POST /app/release/offer`` and the sealed file transfer (``files.py``).
"""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import struct
import subprocess
import urllib.request
from datetime import UTC, datetime
from pathlib import Path

from . import audit, store
from .claude import ClaudeError
from .config import ConfigError, config_dir, load_settings
from .paths import repo_dir

STATE_FILE = "app-release.json"
FOLDER = "app-release"
MAX_BYTES = 200 * 1024 * 1024  # an APK may be larger than the 50 MB of ordinary file offers
SESSION = "app-release"  # the name the download is asked with, as for any offer
SIG_BLOCK_MAGIC = b"APK Sig Block 42"
SIGNATURE_SCHEMES = (0xF05368C0, 0x7109871A)  # v3, v2
BUILD_TIMEOUT = 1800


class ReleaseError(Exception):
    """The release cannot be registered; the message says why (no secrets in it)."""


# ---- reading the APK -----------------------------------------------------------------


def _blocks(data: bytes):
    """Length-prefixed (uint32, little-endian) items of a sequence."""
    at = 0
    while at < len(data):
        if at + 4 > len(data):
            raise ReleaseError("the APK signing block is damaged")
        (size,) = struct.unpack_from("<I", data, at)
        at += 4
        if at + size > len(data):
            raise ReleaseError("the APK signing block is damaged")
        yield data[at : at + size]
        at += size


def signing_certificate(apk: Path) -> bytes:
    """The DER certificate of the first signer of the v3 (or v2) signature scheme."""
    data = apk.read_bytes()
    end = data.rfind(b"PK\x05\x06")  # end of central directory
    if end < 0 or end + 22 > len(data):
        raise ReleaseError("not an APK (no zip directory)")
    (directory,) = struct.unpack_from("<I", data, end + 16)
    if directory < 32 or data[directory - 16 : directory] != SIG_BLOCK_MAGIC:
        raise ReleaseError("the APK is not signed with the v2 or v3 scheme")
    (size,) = struct.unpack_from("<Q", data, directory - 24)
    start = directory - size - 8
    if start < 0:
        raise ReleaseError("the APK signing block is damaged")
    pairs, at, schemes = data[start + 8 : directory - 24], 0, {}
    while at + 12 <= len(pairs):
        (length,) = struct.unpack_from("<Q", pairs, at)
        (kind,) = struct.unpack_from("<I", pairs, at + 8)
        schemes[kind] = pairs[at + 12 : at + 8 + length]
        at += 8 + length
    for scheme in SIGNATURE_SCHEMES:
        if scheme in schemes:
            signer = next(_blocks(next(_blocks(schemes[scheme]))))
            signed_data = next(_blocks(signer))
            parts = _blocks(signed_data)
            next(parts)  # digests
            certificates = next(parts)
            return next(_blocks(certificates))
    raise ReleaseError("the APK is not signed with the v2 or v3 scheme")


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def build_output() -> Path:
    root = repo_dir()
    if root is None:
        raise ReleaseError("the repository folder was not found")
    return root / "app" / "app" / "build" / "outputs" / "apk" / "release"


def build(
    timeout: int = BUILD_TIMEOUT, *, env: dict[str, str] | None = None, args: tuple[str, ...] = ()
) -> None:
    """``./gradlew :app:assembleRelease``; Gradle reads the signing key itself.

    ``env`` and ``args``: the environment and extra Gradle arguments of an update the
    bridge applies itself (``updates.py``); the command line uses its own environment.
    """
    root = repo_dir()
    if root is None:
        raise ReleaseError("the repository folder was not found")
    done = subprocess.run(  # noqa: S603
        [str(root / "app" / "gradlew"), *args, ":app:assembleRelease"],
        cwd=root / "app", timeout=timeout, check=False, capture_output=True, text=True,
        env=env,
    )  # fmt: skip
    if done.returncode != 0:
        raise ReleaseError(
            "the release build failed (run ./gradlew :app:assembleRelease to see why)"
        )


def from_build_output(folder: Path) -> tuple[Path, int, str]:
    """The APK of Gradle's release output with its version code and name."""
    try:
        meta = json.loads((folder / "output-metadata.json").read_text())
        element = meta["elements"][0]
        apk = folder / element["outputFile"]
        return apk, int(element["versionCode"]), str(element["versionName"])
    except (OSError, ValueError, KeyError, IndexError, TypeError) as exc:
        raise ReleaseError("no release build found; build it first (--build)") from exc


# ---- the registered release ------------------------------------------------------------


def state_path() -> Path:
    return config_dir() / STATE_FILE


def current() -> dict | None:
    data = store.read_json(state_path(), {})
    return data.get("current") if isinstance(data.get("current"), dict) else None


def register(
    apk: Path, version_code: int, version_name: str, *, new_certificate: bool = False,
    now: datetime | None = None,
) -> dict:  # fmt: skip
    """Check, copy and register one APK as the current release; wake the phones."""
    if apk.is_symlink() or not apk.is_file():
        raise ReleaseError("the APK file was not found")
    size = apk.stat().st_size
    if size > MAX_BYTES:
        raise ReleaseError(f"the APK is larger than {MAX_BYTES // (1024 * 1024)} MB")
    if version_code <= 0 or not version_name or len(version_name) > 64:
        raise ReleaseError("a version code above 0 and a version name are needed")
    certificate = hashlib.sha256(signing_certificate(apk)).hexdigest()
    data = store.read_json(state_path(), {})
    pinned = data.get("certificate")
    if pinned and pinned != certificate and not new_certificate:
        raise ReleaseError("the APK is signed with another key than the earlier releases")
    previous = data.get("current") or {}
    if previous and version_code <= int(previous.get("version_code") or 0):
        raise ReleaseError(f"version code {version_code} is not newer than the current release")
    folder = config_dir() / FOLDER
    folder.mkdir(mode=0o700, parents=True, exist_ok=True)
    name = f"lifeos-{version_code}.apk"
    temporary = folder / (name + ".part")
    shutil.copyfile(apk, temporary)
    os.chmod(temporary, 0o600)
    digest = _sha256(temporary)
    release = {
        "version_code": version_code,
        "version_name": version_name,
        "size": temporary.stat().st_size,
        "sha256": digest,
        "cert_sha256": certificate,
        "file": name,
        "registered_at": (now or datetime.now(UTC)).isoformat(timespec="seconds"),
    }
    try:
        audit.record("AppRelease", {k: release[k] for k in
                                    ("version_code", "version_name", "size", "sha256",
                                     "cert_sha256")})  # fmt: skip
    except audit.AuditUnavailable as exc:
        temporary.unlink(missing_ok=True)
        raise ReleaseError("the audit log cannot be written; nothing was registered") from exc
    temporary.replace(folder / name)
    store.write_json(state_path(), {"certificate": certificate, "current": release})
    for old in folder.glob("lifeos-*.apk"):  # only the current release is kept
        if old.name != name:
            old.unlink(missing_ok=True)
    release["phones_reached"] = wake_phones(version_code)
    return release


def wake_phones(version_code: int) -> int | None:
    """Ask the running bridge to send the sealed ``update`` hint; None when it is not running.

    Never sent from this process: a push advances the phone's counter in its
    device record, which only the running bridge may write while it answers
    the phone. Without a running bridge the app finds the release when it opens.
    """
    try:
        settings = load_settings()
        body = json.dumps({"event": "update", "ref": str(version_code)}).encode()
        request = urllib.request.Request(
            f"http://{settings.host}:{settings.port}/push/notify", data=body, method="POST",
            headers={"Authorization": f"Bearer {settings.token}",
                     "Content-Type": "application/json"},
        )  # fmt: skip
        with urllib.request.urlopen(request, timeout=10) as response:  # noqa: S310
            return int(json.loads(response.read() or b"{}").get("phones_reached") or 0)
    except (ConfigError, OSError, ValueError):
        return None


def public(release: dict) -> dict:
    """What the phone sees: no path."""
    return {k: release[k] for k in ("version_code", "version_name", "size", "sha256",
                                    "cert_sha256", "registered_at")}  # fmt: skip


def apk_path(release: dict) -> Path:
    path = config_dir() / FOLDER / release["file"]
    if path.is_symlink() or not path.is_file() or path.stat().st_size != release["size"]:
        raise ClaudeError("no_release", 404, "the registered release file is missing")
    return path


def view() -> dict:
    release = current()
    if release is None:
        raise ClaudeError("no_release", 404, "no release has been registered")
    return public(release)


# ---- command line --------------------------------------------------------------------------


def command(args: list[str]) -> int:
    """``lifeos-bridge release [--build] [--apk FILE --version-code N --version-name X]
    [--new-certificate]``."""
    import argparse

    parser = argparse.ArgumentParser(prog="lifeos-bridge release")
    parser.add_argument("--build", action="store_true", help="run the Gradle release build first")
    parser.add_argument("--apk", type=Path, help="an APK other than the build output")
    parser.add_argument("--version-code", type=int)
    parser.add_argument("--version-name")
    parser.add_argument("--new-certificate", action="store_true",
                        help="accept a release signed with another key")  # fmt: skip
    options = parser.parse_args(args)
    try:
        if options.build:
            print("Building the release (Gradle reads the signing key itself) ...")
            build()
        if options.apk is not None:
            if options.version_code is None or not options.version_name:
                raise ReleaseError("--apk needs --version-code and --version-name")
            apk, code, name = options.apk, options.version_code, options.version_name
        else:
            apk, code, name = from_build_output(build_output())
        release = register(apk, code, name, new_certificate=options.new_certificate)
    except (ReleaseError, OSError, subprocess.SubprocessError) as error:
        print(f"lifeos-bridge release: {error}")
        return 1
    reached = release.get("phones_reached")
    told = (
        "the bridge is not running: the app finds it when it opens"
        if reached is None
        else f"{reached} paired phone(s) were told"
    )
    print(
        f"Registered release {release['version_name']} (code {release['version_code']}), "
        f"{release['size']} bytes, sha256 {release['sha256'][:16]}…, "
        f"certificate {release['cert_sha256'][:16]}…; {told}."
    )
    return 0
