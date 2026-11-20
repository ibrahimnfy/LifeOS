"""Sign in to the university Moodle through the browser and store a token.

``scripts/moodle-login`` follows the browser flow of the official mobile app
(docs/research/moodle-access.md): it opens the sign-in link in the default
browser, the owner signs in on the institution's own page, and the browser
ends on an address that starts with ``moodlemobile://token=``. A temporary
address handler passes that address to the waiting script; if that does not
work the owner can still paste it. The helper never sees the password. The
value is verified against the one-time passport, and only the token is
stored, in ``~/.config/lifeos/env`` (mode 600). Nothing secret is printed or
logged.

**Written outside the project's usual folders, while the script runs only:**
the desktop entry ``lifeos-moodle-signin.desktop`` in the user's
``applications`` folder (``~/.local/share/applications``) and, through
``xdg-mime``, the user's default handler for ``moodlemobile://`` addresses
(``~/.config/mimeapps.list``). This is acceptable only because the owner
runs this script themselves and it cleans up: when the script ends, however
it ends, the previous default is restored (or the association removed) and
the desktop entry is deleted. ``--no-handler`` skips all of it. No ``sudo``.
"""

from __future__ import annotations

import argparse
import base64
import binascii
import contextlib
import getpass
import hashlib
import hmac
import json
import os
import secrets
import select
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Iterator
from datetime import date
from pathlib import Path

from . import audit
from .config import CONFIG_DIR_VAR, ENV_FILE_NAME, config_dir, read_env_file
from .paths import repo_dir

SERVICE = "moodle_mobile_app"
SCHEME = "moodlemobile"
NEEDED_FUNCTIONS = (
    "core_enrol_get_users_courses",
    "core_course_get_contents",
    "mod_assign_get_assignments",
    "core_calendar_get_action_events_by_timesort",
)


MIME_TYPE = f"x-scheme-handler/{SCHEME}"
DESKTOP_FILE = "lifeos-moodle-signin.desktop"
HANDLER_NAME = "LifeOS Moodle sign-in"
CALLBACK_FILE = "moodle-callback"
MAX_CALLBACK_CHARS = 8192
# State of the daily token check (moodle_watch); removed after a new sign-in
# so that the next check runs at once.
WATCH_FILE = "moodle-watch.json"
POLL_SECONDS = 0.5
WAIT_SECONDS = 300
ISSUER_KEY = "MOODLE_LOGIN_ISSUER"
SAVED_KEY = "MOODLE_TOKEN_SAVED"
NO_ISSUER = "none"


class LoginError(Exception):
    """Something went wrong; the message is safe to show (no address, no token)."""


class TokenRejected(LoginError):
    """The site answered, and refused the token. ``code`` is Moodle's error code."""

    def __init__(self, message: str, code: str | None) -> None:
        super().__init__(message)
        self.code = code

    @property
    def expired(self) -> bool:
        return self.code == "invalidtoken"


def _post(url: str, data: bytes, content_type: str) -> object:
    if not url.startswith("https://"):
        raise LoginError("the Moodle address must start with https://")
    request = urllib.request.Request(  # noqa: S310
        url, data=data, headers={"Content-Type": content_type, "User-Agent": "lifeos-bridge"}
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:  # noqa: S310
            return json.loads(response.read())
    except urllib.error.HTTPError as exc:
        # Some sites send Moodle's JSON error with an error status.
        with contextlib.suppress(OSError, ValueError):
            answer = json.loads(exc.read())
            if isinstance(answer, dict) and answer.get("exception"):
                return answer
        raise LoginError("the Moodle site could not be reached (HTTPError)") from None
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


def store_values(values: dict[str, str]) -> None:
    """Write ``KEY=value`` lines into the private env file, keeping every other line."""
    path: Path = config_dir() / ENV_FILE_NAME
    lines = path.read_text(encoding="utf-8").splitlines() if path.exists() else []
    prefixes = tuple(f"{prefix}{key}=" for key in values for prefix in ("", "export "))
    kept = [line for line in lines if not line.strip().startswith(prefixes)]
    kept.extend(f"{key}={value}" for key, value in values.items())
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
        handle.write("\n".join(kept) + "\n")
        handle.flush()
        os.fsync(handle.fileno())
    temporary.replace(path)
    path.chmod(0o600)


def store_value(key: str, value: str) -> None:
    store_values({key: value})


def store_token(token: str, issuer: str | None = None, today: date | None = None) -> None:
    """Store the token with the day it was saved, and the sign-in button if one is given.

    The day feeds the expiry estimate of ``moodle_watch``; the state of that
    watch is dropped so the new token is checked at the next opportunity.
    """
    values = {"MOODLE_TOKEN": token, SAVED_KEY: (today or date.today()).isoformat()}
    if issuer is not None:
        values[ISSUER_KEY] = issuer
    store_values(values)
    (config_dir() / WATCH_FILE).unlink(missing_ok=True)
    audit.try_record("MoodleSignIn", {"stored": "token"})  # never the token itself


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
        raise TokenRejected(
            "the stored token is not accepted"
            + (" (it has expired or was revoked: sign in again)" if code == "invalidtoken" else ""),
            code if isinstance(code, str) else None,
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


# ---- commands of the desktop: one seam each, replaced in tests ---------------


def _run(args: list[str], text: str | None = None) -> str | None:
    """Run a desktop command and wait. Returns its output, or None when it failed.

    With ``text`` (sent to its input) the output is not collected: clipboard
    tools stay in the background and would keep a pipe open.
    """
    collect = text is None
    source = {"stdin": subprocess.DEVNULL} if collect else {"input": text}
    try:
        done = subprocess.run(  # noqa: S603
            args,
            stdout=subprocess.PIPE if collect else subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            text=True,
            timeout=10,
            check=False,
            **source,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    return (done.stdout or "") if done.returncode == 0 else None


def _spawn(args: list[str]) -> bool:
    """Start a desktop command without waiting for it (a browser may stay open)."""
    try:
        subprocess.Popen(  # noqa: S603
            args,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            start_new_session=True,
        )
    except (OSError, subprocess.SubprocessError):
        return False
    return True


def open_browser(link: str) -> bool:
    opener = shutil.which("xdg-open")
    return bool(opener) and _spawn([opener, link])


def copy_to_clipboard(text: str) -> bool:
    """Put ``text`` on the clipboard when a clipboard tool is installed."""
    for tool, arguments in (("wl-copy", []), ("xclip", ["-selection", "clipboard"])):
        path = shutil.which(tool)
        if path and _run([path, *arguments], text) is not None:
            return True
    return False


# ---- the temporary address handler -----------------------------------------


def applications_dir() -> Path:
    base = os.environ.get("XDG_DATA_HOME") or str(Path.home() / ".local" / "share")
    return Path(base) / "applications"


def _mimeapps_files() -> list[Path]:
    config = Path(os.environ.get("XDG_CONFIG_HOME") or str(Path.home() / ".config"))
    return [config / "mimeapps.list", applications_dir() / "mimeapps.list"]


def _quoted(value: str) -> str:
    """One argument of an ``Exec`` line: as it is when plain, else quoted (desktop entry rules)."""
    if value and all(char.isalnum() or char in "/._-=+:,@" for char in value):
        return value
    escaped = "".join("\\" + char if char in '"`$\\' else char for char in value)
    return '"' + escaped.replace("%", "%%") + '"'


def desktop_entry(script: Path) -> str:
    command = _quoted(str(script)) + " %u"
    override = os.environ.get(CONFIG_DIR_VAR)
    if override:  # the handler starts without this terminal's environment
        command = f"env {_quoted(f'{CONFIG_DIR_VAR}={override}')} {command}"
    return (
        "[Desktop Entry]\n"
        "Type=Application\n"
        f"Name={HANDLER_NAME}\n"
        f"Exec={command}\n"
        f"MimeType={MIME_TYPE};\n"
        "NoDisplay=true\n"
        "Terminal=false\n"
    )


def _forget_association() -> None:
    """Remove our own default from the user's lists (``xdg-mime`` cannot unset one)."""
    for path in _mimeapps_files():
        try:
            lines = path.read_text(encoding="utf-8").splitlines()
        except OSError:
            continue
        ours = {f"{MIME_TYPE}={DESKTOP_FILE}", f"{MIME_TYPE}={DESKTOP_FILE};"}
        kept = [line for line in lines if line.strip() not in ours]
        if kept == lines:
            continue
        if all(not line.strip() or line.strip() == "[Default Applications]" for line in kept):
            path.unlink(missing_ok=True)  # the file held nothing but our line
            continue
        temporary = path.with_name(path.name + ".lifeos-tmp")
        temporary.write_text("\n".join(kept) + "\n", encoding="utf-8")
        temporary.replace(path)


@contextlib.contextmanager
def signin_handler() -> Iterator[bool]:
    """Register the address handler for the time of the ``with`` block.

    Yields whether it is registered. On leaving, for whatever reason, the
    previous default is restored and the desktop entry is deleted.
    """
    tool = shutil.which("xdg-mime")
    repo = repo_dir()
    script = repo / "scripts" / "moodle-callback" if repo else None
    if not tool or script is None or not script.is_file():
        yield False
        return
    entry = applications_dir() / DESKTOP_FILE
    previous = (_run([tool, "query", "default", MIME_TYPE]) or "").strip()
    if previous == DESKTOP_FILE:  # left behind by a run that was killed
        previous = ""
    registered = False
    try:
        entry.parent.mkdir(parents=True, exist_ok=True)
        entry.write_text(desktop_entry(script), encoding="utf-8")
        registered = _run([tool, "default", DESKTOP_FILE, MIME_TYPE]) is not None
        yield registered
    finally:
        with contextlib.suppress(OSError):
            if previous:
                _run([tool, "default", previous, MIME_TYPE])
            else:
                _forget_association()
        with contextlib.suppress(OSError):
            entry.unlink(missing_ok=True)


# ---- the address the browser hands over -------------------------------------


def callback_path() -> Path:
    return config_dir() / CALLBACK_FILE


def receive_callback(value: str) -> int:
    """``scripts/moodle-callback``: keep the received address for the waiting script.

    Prints nothing about the value. Anything that is not a sign-in address of
    the expected scheme is refused.
    """
    if not value.startswith(f"{SCHEME}://") or len(value) > MAX_CALLBACK_CHARS:
        print("moodle-callback: not a Moodle sign-in address; ignored", file=sys.stderr)
        return 2
    path = callback_path()
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    temporary = path.with_name(f"{path.name}.{os.getpid()}.tmp")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
        handle.write(value)
    temporary.replace(path)
    return 0


def _take_callback() -> str | None:
    """Read the handed-over address and delete the file at once."""
    path = callback_path()
    try:
        value = path.read_text(encoding="utf-8")
    except OSError:
        return None
    path.unlink(missing_ok=True)
    return value


def _enter_pressed(seconds: float) -> bool:
    """Wait up to ``seconds``; True when the owner pressed Enter meanwhile."""
    try:
        ready = select.select([sys.stdin], [], [], seconds)[0]
    except (OSError, ValueError, TypeError):  # no usable terminal input
        time.sleep(seconds)
        return False
    if not ready:
        return False
    if sys.stdin.readline() == "":  # input is closed: keep waiting for the browser
        time.sleep(seconds)
        return False
    return True


def wait_for_callback() -> str | None:
    """Poll for the address from the browser. None after the time limit or on Enter."""
    print("Waiting for the sign-in in your browser… (Ctrl-C to cancel)", flush=True)
    deadline = time.monotonic() + WAIT_SECONDS
    while time.monotonic() < deadline:
        value = _take_callback()
        if value is not None:
            return value
        if _enter_pressed(POLL_SECONDS):
            return _take_callback()
    return _take_callback()


# ---- the sign-in --------------------------------------------------------------

WITH_HANDLER = f"""
What happens next:
  1. Your browser opens the sign-in page. Sign in as you always do.
  2. At the end the browser asks whether to open the link with
     "{HANDLER_NAME}". Allow it.
  3. Come back here: the sign-in is picked up by itself.
"""

WITHOUT_HANDLER = """
What happens next:
  1. Your browser opens the sign-in page. Sign in as you always do.
  2. At the end the browser stops at an address that starts with
     moodlemobile://token=  . Copy that whole address
     (docs/bridge.md, "Moodle sign-in", explains where to find it).
  3. Come back here and paste it. It is not shown while you paste.
"""

REGISTERED = (
    f'Registered until this script ends: the address handler "{HANDLER_NAME}"\n'
    "(one file in ~/.local/share/applications and the default for moodlemobile://\n"
    "addresses). Both are removed again when the script ends."
)


def ask_issuer(choices: list[dict]) -> str | None:
    print("Sign-in buttons of the site:")
    for number, choice in enumerate(choices, 1):
        note = "" if choice["issuer"] else "   (browser flow may not be supported)"
        print(f"  {number}. {choice['name']}{note}")
    print("  0. none of these (the site's own login form)")
    picked = input("Which one do you use? Number: ").strip()
    if picked.isdigit() and 1 <= int(picked) <= len(choices):
        return choices[int(picked) - 1]["issuer"]
    return None


def chosen_issuer(choices: list[dict], ask_again: bool) -> str | None:
    """The remembered sign-in button, or the owner's choice when none fits."""
    if not choices:
        return None
    remembered = read_env_file(config_dir() / ENV_FILE_NAME).get(ISSUER_KEY, "").strip()
    offered = {choice["issuer"] for choice in choices if choice["issuer"]}
    if not ask_again and (remembered == NO_ISSUER or remembered in offered):
        print("Using the sign-in button chosen last time (--choose asks again).")
        return None if remembered == NO_ISSUER else remembered
    return ask_issuer(choices)


def show_link(link: str) -> None:
    """Open the link in the browser; the printed copy is only a fallback."""
    opened = open_browser(link)
    print("The sign-in page was opened in your browser." if opened
          else "The browser could not be opened from here.")  # fmt: skip
    if copy_to_clipboard(link):
        print("The link is also on the clipboard.")
    print("\nThe link, only needed if the browser did not open (one line, this attempt only):")
    print(link + "\n", flush=True)


def obtain_redirect(link: str, use_handler: bool) -> str:
    """The address the sign-in ends on: from the handler, else pasted by the owner."""
    received = None
    with contextlib.ExitStack() as stack:
        stack.callback(lambda: callback_path().unlink(missing_ok=True))
        registered = stack.enter_context(signin_handler()) if use_handler else False
        callback_path().unlink(missing_ok=True)  # nothing from an earlier attempt
        print(WITH_HANDLER if registered else WITHOUT_HANDLER)
        if registered:
            print(REGISTERED + "\n")
        elif use_handler:
            print("The address handler could not be registered; paste the address instead.\n")
        show_link(link)
        if registered:
            received = wait_for_callback()
            if received is None:
                print("Nothing arrived from the browser. You can paste the address instead.")
        if received is None:
            received = getpass.getpass("Paste the moodlemobile://token=... value and press Enter: ")
    return received


def sign_in(url: str, use_handler: bool, ask_again: bool) -> None:
    config = public_config(url)
    wwwroot = str(config.get("wwwroot") or url).rstrip("/")
    issuer = chosen_issuer(providers(config), ask_again)
    passport = secrets.token_hex(12)
    link = launch_link(config, passport, issuer)
    token = token_from_redirect(obtain_redirect(link, use_handler), wwwroot, passport)
    store_token(token, issuer or NO_ISSUER)
    print("Saved the token in the private env file (mode 600).")
    report(url, token)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="moodle-login", description=__doc__.split("\n\n")[0])
    parser.add_argument("--check", action="store_true", help="only test the stored token")
    parser.add_argument("--choose", action="store_true", help="ask for the sign-in button again")
    parser.add_argument(
        "--no-handler", action="store_true", help="register nothing; paste the address by hand"
    )
    parser.add_argument("--callback", metavar="ADDRESS", help=argparse.SUPPRESS)
    try:
        args = parser.parse_args(argv)
    except SystemExit as stop:  # argparse may echo an argument; the callback value never is
        return int(stop.code or 0)
    if args.callback is not None:
        return receive_callback(args.callback)
    try:
        url = site_url()
        if args.check:
            token = read_env_file(config_dir() / ENV_FILE_NAME).get("MOODLE_TOKEN", "")
            if not token:
                raise LoginError("no token is stored yet; run scripts/moodle-login")
            report(url, token)
            return 0
        sign_in(url, not args.no_handler, args.choose)
        return 0
    except LoginError as error:
        print(f"moodle-login: {error}", file=sys.stderr)
        return 1
    except (KeyboardInterrupt, EOFError):
        print("\nCancelled; nothing was stored.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
