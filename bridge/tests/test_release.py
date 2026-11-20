"""Release builds of the app (#138): a stand-in APK with a synthetic signing block."""

import dataclasses
import hashlib
import io
import json
import os
import struct
import subprocess
import zipfile

import pytest
from fastapi.testclient import TestClient

from lifeos_bridge import push, relay, release
from lifeos_bridge.__main__ import main as cli
from lifeos_bridge.app import create_app


def lp(data: bytes) -> bytes:
    return struct.pack("<I", len(data)) + data


def signed_apk(path, cert=b"CERT-DER-A", scheme=0xF05368C0, payload=b"y" * 1000):
    """A zip with an APK Signature Scheme block before its central directory."""
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr("AndroidManifest.xml", b"x")
        archive.writestr("classes.dex", payload)
    data = buffer.getvalue()
    if scheme is not None:
        end = data.rfind(b"PK\x05\x06")
        (directory,) = struct.unpack_from("<I", data, end + 16)
        signed_data = lp(b"") + lp(lp(cert)) + lp(b"")
        signer = lp(signed_data) + lp(b"") + lp(b"")
        value = lp(lp(signer))
        pair = struct.pack("<Q", 4 + len(value)) + struct.pack("<I", scheme) + value
        size = len(pair) + 8 + 16
        block = struct.pack("<Q", size) + pair + struct.pack("<Q", size) + release.SIG_BLOCK_MAGIC
        data = data[:directory] + block + data[directory:]
        end += len(block)
        data = data[: end + 16] + struct.pack("<I", directory + len(block)) + data[end + 20 :]
    path.write_bytes(data)
    return path


@pytest.fixture
def output(tmp_path, monkeypatch):
    """Gradle's release output folder, as the build would leave it."""
    folder = tmp_path / "apk-release"
    folder.mkdir()
    monkeypatch.setattr(release, "build_output", lambda: folder)

    def built(code, name, **apk):
        signed_apk(folder / "app-release.apk", **apk)
        meta = {"elements": [{"versionCode": code, "versionName": name,
                              "outputFile": "app-release.apk"}]}  # fmt: skip
        (folder / "output-metadata.json").write_text(json.dumps(meta))
        return folder / "app-release.apk"

    return built


@pytest.fixture
def hints(monkeypatch):
    sent = []
    monkeypatch.setattr(push, "send_hint", lambda event, ref="", **kw: sent.append((event, ref)))
    return sent


def audit_of(config, tool):
    return [
        json.loads(line)["detail"]
        for path in sorted((config / "audit").glob("*.jsonl"))
        for line in path.read_text().splitlines()
        if json.loads(line)["tool"] == tool
    ]


def test_the_command_registers_the_build_output(output, hints, isolated_config, capsys):
    apk = output(12, "0.1.12")
    assert cli(["release"]) == 0
    assert "Registered release 0.1.12 (code 12)" in capsys.readouterr().out
    current = release.current()
    assert current["version_code"] == 12 and current["size"] == apk.stat().st_size
    assert current["sha256"] == hashlib.sha256(apk.read_bytes()).hexdigest()
    assert current["cert_sha256"] == hashlib.sha256(b"CERT-DER-A").hexdigest()
    copy = isolated_config / release.FOLDER / "lifeos-12.apk"
    assert copy.read_bytes() == apk.read_bytes() and copy.stat().st_mode & 0o777 == 0o600
    assert hints == [("update", "12")]
    (record,) = audit_of(isolated_config, "AppRelease")
    assert record == {k: current[k] for k in ("version_code", "version_name", "size", "sha256",
                                              "cert_sha256")}  # fmt: skip
    # A newer build replaces it; the older copy is removed.
    output(13, "0.1.13")
    assert cli(["release"]) == 0
    assert sorted(p.name for p in (isolated_config / release.FOLDER).iterdir()) == ["lifeos-13.apk"]


def test_refusals(output, hints, tmp_path, isolated_config, capsys):
    output(5, "0.1.5", scheme=None)
    assert cli(["release"]) == 1 and "not signed" in capsys.readouterr().out
    output(5, "0.1.5")
    assert cli(["release"]) == 0
    assert cli(["release"]) == 1 and "not newer" in capsys.readouterr().out
    output(6, "0.1.6", cert=b"ANOTHER-KEY")
    assert cli(["release"]) == 1 and "another key" in capsys.readouterr().out
    assert cli(["release", "--new-certificate"]) == 0
    assert release.current()["cert_sha256"] == hashlib.sha256(b"ANOTHER-KEY").hexdigest()
    other = signed_apk(tmp_path / "other.apk", cert=b"ANOTHER-KEY")
    assert cli(["release", "--apk", str(other)]) == 1  # needs its version
    assert cli(["release", "--apk", str(other), "--version-code", "7",
                "--version-name", "0.1.7"]) == 0  # fmt: skip
    (isolated_config / "audit").rename(isolated_config / "audit-old")
    (isolated_config / "audit").write_text("not a folder")
    output(8, "0.1.8", cert=b"ANOTHER-KEY")
    assert cli(["release"]) == 1 and release.current()["version_code"] == 7
    assert not (isolated_config / release.FOLDER / "lifeos-8.apk").exists()
    assert hints == [("update", "5"), ("update", "6"), ("update", "7")]


def test_build_runs_gradle_without_any_secret(output, hints, monkeypatch, capsys):
    ran = []

    def gradle(command, **kwargs):
        ran.append((command, kwargs["cwd"]))
        output(20, "0.1.20")
        return subprocess.CompletedProcess(command, 0, "", "")

    monkeypatch.setattr(release.subprocess, "run", gradle)
    assert cli(["release", "--build"]) == 0
    ((command, cwd),) = ran
    assert command[0].endswith("app/gradlew") and command[1:] == [":app:assembleRelease"]
    assert cwd.name == "app" and release.current()["version_code"] == 20


def test_the_phone_asks_and_downloads_it_beyond_the_file_limit(
    output, hints, settings, auth, isolated_config
):
    client = TestClient(create_app(dataclasses.replace(settings, max_file_mb=1)))
    assert client.get("/app/release", headers=auth).status_code == 404
    output(30, "0.1.30", payload=os.urandom(2 * 1024 * 1024))  # larger than the 1 MB of files
    release.register(*release.from_build_output(release.build_output()))
    shown = client.get("/app/release", headers=auth).json()
    assert set(shown) == {"version_code", "version_name", "size", "sha256", "cert_sha256",
                          "registered_at"}  # fmt: skip
    assert shown["size"] > 1024 * 1024
    card = client.post("/app/release/offer", headers=auth).json()
    assert card["session_id"] == "app-release" and card["type"] == (
        "application/vnd.android.package-archive"
    )
    assert card["version_code"] == 30 and card["sha256"] == shown["sha256"]
    fetched = client.get(f"/files/{card['offer']}?session_id=app-release", headers=auth)
    assert hashlib.sha256(fetched.content).hexdigest() == shown["sha256"]
    assert relay.allowed("GET", "/app/release")  # the paired phone asks through the relay
    assert relay.allowed("POST", "/app/release/offer") and not relay.allowed("GET", "/apple")
    # The file was changed or removed on the computer: no offer.
    (isolated_config / release.FOLDER / "lifeos-30.apk").write_bytes(b"short")
    assert client.post("/app/release/offer", headers=auth).status_code == 404
    downloads = audit_of(isolated_config, "FileOffer")
    assert downloads[0]["release"] == 30
