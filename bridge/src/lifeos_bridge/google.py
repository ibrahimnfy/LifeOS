"""Access to the owner's Firebase project: settings files and access tokens.

The two files live in ``~/.config/lifeos/firebase/`` and are never copied
anywhere. All HTTPS goes through Python's standard library with its default
certificate checks, so the system's trust store decides; verification is
never switched off.
"""

from __future__ import annotations

import json
import urllib.error
import urllib.request
from pathlib import Path

import google.auth.transport
from google.oauth2 import service_account

from .config import config_dir

DATABASE_SCOPES = (
    "https://www.googleapis.com/auth/firebase.database",
    "https://www.googleapis.com/auth/userinfo.email",
)
MESSAGING_SCOPES = ("https://www.googleapis.com/auth/firebase.messaging",)


class FirebaseConfigError(Exception):
    """The Firebase files are missing or unusable. The message never quotes their content."""


def firebase_dir() -> Path:
    return config_dir() / "firebase"


class _Response(google.auth.transport.Response):
    def __init__(self, status: int, headers: dict, data: bytes) -> None:
        self._status, self._headers, self._data = status, headers, data

    @property
    def status(self) -> int:
        return self._status

    @property
    def headers(self) -> dict:
        return self._headers

    @property
    def data(self) -> bytes:
        return self._data


class UrllibRequest(google.auth.transport.Request):
    """Transport for the auth library built on the standard library."""

    def __call__(self, url, method="GET", body=None, headers=None, timeout=30, **kwargs):
        if not url.startswith("https://"):
            raise ValueError("only https is allowed")
        request = urllib.request.Request(url, data=body, method=method, headers=headers or {})  # noqa: S310
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:  # noqa: S310
                return _Response(response.status, dict(response.headers), response.read())
        except urllib.error.HTTPError as error:
            return _Response(error.code, dict(error.headers), error.read())


def read_project() -> dict:
    """``{"project_id", "database_url", "api_key"}`` from the app settings file."""
    try:
        data = json.loads((firebase_dir() / "google-services.json").read_text(encoding="utf-8"))
        info = data["project_info"]
        project = {
            "project_id": str(info["project_id"]),
            "database_url": str(info["firebase_url"]).rstrip("/"),
            "api_key": str(data["client"][0]["api_key"][0]["current_key"]),
        }
    except (OSError, ValueError, KeyError, IndexError, TypeError) as exc:
        raise FirebaseConfigError(
            "the app settings file is missing or incomplete (see the Firebase set-up issue)"
        ) from exc
    if not project["database_url"].startswith("https://"):
        raise FirebaseConfigError("the app settings file has no database address")
    return project


def access_token(scopes: tuple[str, ...]) -> str:
    """A short-lived access token for the service account. Never log the result."""
    path = firebase_dir() / "service-account.json"
    try:
        if path.stat().st_mode & 0o077:
            raise FirebaseConfigError("the key file must not be readable by other users")
        credentials = service_account.Credentials.from_service_account_file(
            str(path), scopes=list(scopes)
        )
    except (OSError, ValueError) as exc:
        raise FirebaseConfigError("the key file is missing or unusable") from exc
    credentials.refresh(UrllibRequest())
    return str(credentials.token)
