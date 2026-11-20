"""Sign in to the university Moodle through the browser and store a token.

``scripts/moodle-login`` follows the browser flow of the official mobile app
(docs/research/moodle-access.md): it prints a link, the owner signs in on
the institution's own page, the browser ends on an address that starts with
``moodlemobile://token=``, and the owner pastes that address here. The
helper never sees the password. The pasted value is read without echo,
verified against the one-time passport, and only the token is stored, in
``~/.config/lifeos/env`` (mode 600). Nothing secret is printed or logged.
"""

from __future__ import annotations

import argparse
import base64
import binascii
import getpass
import hashlib
import hmac
import json
import os
import secrets
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from .config import ENV_FILE_NAME, config_dir, read_env_file

SERVICE = "moodle_mobile_app"
SCHEME = "moodlemobile"
NEEDED_FUNCTIONS = (
    "core_enrol_get_users_courses",
    "core_course_get_contents",
    "mod_assign_get_assignments",
    "core_calendar_get_action_events_by_timesort",
)


class LoginError(Exception):
    """Something went wrong; the message is safe to show (no address, no token)."""


def _post(url: str, data: bytes, content_type: str) -> object:
    if not url.startswith("https://"):
        raise LoginError("the Moodle address must start with https://")
    request = urllib.request.Request(  # noqa: S310
        url, data=data, headers={"Content-Type": content_type, "User-Agent": "lifeos-bridge"}
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:  # noqa: S310
            return json.loads(response.read())
    except (urllib.error.URLError, OSError, ValueError) as exc:
        raise LoginError(f"the Moodle site could not be reached ({type(exc).__name__})") from exc


def site_url() -> str:
    url = read_env_file(config_dir() / ENV_FILE_NAME).get("MOODLE_URL", "").strip().rstrip("/")
    if not url:
        raise LoginError("MOODLE_URL is not set in the private env file")
    return url


def public_config(url: str) -> dict:
    """What the site tells any client, without credentials."""
    body = json.dumps([{"index": 0, "methodname": "tool_mobile_get_public_config", "args": {}}])
    answer = _post(
        f"{url}/lib/ajax/service-nologin.php?info=tool_mobile_get_public_config",
        body.encode(),
        "application/json",
    )
    try:
        item = answer[0]  # type: ignore[index]
        if item.get("error") or not isinstance(item.get("data"), dict):
            raise LoginError("the site did not return its mobile configuration")
        return item["data"]
    except (IndexError, KeyError, TypeError, AttributeError) as exc:
        raise LoginError("the site did not return its mobile configuration") from exc


def providers(config: dict) -> list[dict]:
    """Sign-in buttons the site offers: ``{"name", "issuer"}``; issuer is None if unsupported."""
    found = []
    for item in config.get("identityproviders") or []:
        if not isinstance(item, dict):
            continue
        parts = urllib.parse.urlsplit(str(item.get("url") or ""))
        issuer = urllib.parse.parse_qs(parts.query).get("id", [None])[0]
        is_oauth = parts.path.endswith("/auth/oauth2/login.php") and (issuer or "").isdigit()
        found.append({"name": str(item.get("name") or "?"), "issuer": issuer if is_oauth else None})
    return found


def launch_link(config: dict, passport: str, issuer: str | None) -> str:
    if not (config.get("enablewebservices") and config.get("enablemobilewebservice")):
        raise LoginError("the site has its mobile web service switched off")
    base = str(config.get("launchurl") or "")
    if not base.startswith("https://"):
        raise LoginError("the site did not advertise a sign-in address")
    query = {"service": SERVICE, "passport": passport, "urlscheme": SCHEME}
    if issuer:
        query["oauthsso"] = issuer
    return base + "?" + urllib.parse.urlencode(query)


def token_from_redirect(pasted: str, wwwroot: str, passport: str) -> str:
    """Verify the pasted address and return the token. The private token is discarded."""
    value = pasted.strip().strip("'\"")
    marker = "token="
    if marker not in value:
        raise LoginError("that is not the expected address: it must contain `token=`")
    encoded = value.split(marker, 1)[1].split("&")[0].strip()
    try:
        decoded = base64.b64decode(encoded + "=" * (-len(encoded) % 4), validate=False).decode()
    except (binascii.Error, UnicodeDecodeError, ValueError) as exc:
        raise LoginError("the pasted value could not be decoded; copy the whole line") from exc
    parts = decoded.split(":::")
    if len(parts) < 2 or not parts[1]:
        raise LoginError("the pasted value is incomplete; copy the whole line")
    roots = {wwwroot, wwwroot.replace("https://", "http://", 1)}
    expected = {hashlib.md5((root + passport).encode()).hexdigest() for root in roots}  # noqa: S324
    if not any(hmac.compare_digest(parts[0], candidate) for candidate in expected):
        raise LoginError(
            "the value does not belong to this sign-in attempt (start again with a new link)"
        )
    return parts[1]


def store_token(token: str) -> None:
    """Write ``MOODLE_TOKEN`` into the private env file, keeping every other line."""
    path: Path = config_dir() / ENV_FILE_NAME
    lines = path.read_text(encoding="utf-8").splitlines() if path.exists() else []
    kept = [
        line
        for line in lines
        if not line.strip().startswith(("MOODLE_TOKEN=", "export MOODLE_TOKEN="))
    ]
    kept.append(f"MOODLE_TOKEN={token}")
    temporary = path.with_name(path.name + ".tmp")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
        handle.write("\n".join(kept) + "\n")
        handle.flush()
        os.fsync(handle.fileno())
    temporary.replace(path)
    path.chmod(0o600)


def check_token(url: str, token: str) -> dict:
    """Ask the site who the token belongs to; return only non-identifying facts."""
    body = urllib.parse.urlencode(
        {
            "wstoken": token,
            "wsfunction": "core_webservice_get_site_info",
            "moodlewsrestformat": "json",
        }
    ).encode()
    info = _post(f"{url}/webservice/rest/server.php", body, "application/x-www-form-urlencoded")
    if not isinstance(info, dict) or info.get("exception") or "functions" not in info:
        code = info.get("errorcode") if isinstance(info, dict) else None
        raise LoginError(
            "the stored token is not accepted"
            + (" (it has expired or was revoked: sign in again)" if code == "invalidtoken" else "")
        )
    names = {f.get("name") for f in info.get("functions") or [] if isinstance(f, dict)}
    return {
        "release": str(info.get("release") or "").split(" ")[0],
        "functions": len(names),
        "missing": [name for name in NEEDED_FUNCTIONS if name not in names],
        "downloads": bool(info.get("downloadfiles")),
    }


def report(url: str, token: str) -> None:
    facts = check_token(url, token)
    print(f"The token works. Moodle {facts['release']}; {facts['functions']} functions available;"
          f" file download {'allowed' if facts['downloads'] else 'not allowed'}.")  # fmt: skip
    if facts["missing"]:
        print("Not available on this site: " + ", ".join(facts["missing"]))


HOW_TO_COPY = """
How to get the line to paste:
  1. Open a new browser tab and press F12 (developer tools), choose "Network",
     and tick "Preserve log" / "Persist logs".
  2. Paste the link below into that tab and sign in as you always do.
  3. At the end the page stays blank or shows "address not understood": that
     is expected. In the Network list click the last entry named "launch.php",
     open "Headers" and find the response header "Location". Its value starts
     with  moodlemobile://token=  . Copy that whole value.
     (If the address bar itself shows moodlemobile://token=..., copy it there.)
  4. Come back here and paste it. It is not shown while you paste.
"""


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="moodle-login", description=__doc__.split("\n\n")[0])
    parser.add_argument("--check", action="store_true", help="only test the stored token")
    args = parser.parse_args(argv)
    try:
        url = site_url()
        if args.check:
            token = read_env_file(config_dir() / ENV_FILE_NAME).get("MOODLE_TOKEN", "")
            if not token:
                raise LoginError("no token is stored yet; run scripts/moodle-login")
            report(url, token)
            return 0
        config = public_config(url)
        wwwroot = str(config.get("wwwroot") or url).rstrip("/")
        choices = providers(config)
        issuer = None
        if choices:
            print("Sign-in buttons of the site:")
            for number, choice in enumerate(choices, 1):
                note = "" if choice["issuer"] else "   (browser flow may not be supported)"
                print(f"  {number}. {choice['name']}{note}")
            print("  0. none of these (the site's own login form)")
            picked = input("Which one do you use? Number: ").strip()
            if picked.isdigit() and 1 <= int(picked) <= len(choices):
                issuer = choices[int(picked) - 1]["issuer"]
        passport = secrets.token_hex(12)
        link = launch_link(config, passport, issuer)
        print(HOW_TO_COPY)
        print("The link (valid for this attempt only):\n")
        print("  " + link + "\n")
        pasted = getpass.getpass("Paste the moodlemobile://token=... value and press Enter: ")
        token = token_from_redirect(pasted, wwwroot, passport)
        store_token(token)
        print("Saved the token in the private env file (mode 600).")
        report(url, token)
        return 0
    except LoginError as error:
        print(f"moodle-login: {error}", file=sys.stderr)
        return 1
    except (KeyboardInterrupt, EOFError):
        print("\nCancelled; nothing was stored.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
