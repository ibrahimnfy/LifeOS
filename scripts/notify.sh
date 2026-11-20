#!/usr/bin/env bash
# Notify the owner on this computer.
#
# Usage: scripts/notify.sh "<title>" "<message>" "<link>"
#
# Always prints one clearly visible line to stdout. Additionally shows a
# desktop notification when `notify-send` is available. Always exits 0, so a
# missing or failing notification never breaks the caller.
#
# Pass only the issue number, title and link — never personal data.
# The interface is stable. When the bridge is running, the paired phones are
# woken as well (set LIFEOS_NOTIFY_PUSH=off to skip that).

title="${1:-}"
message="${2:-}"
link="${3:-}"

if [ -z "$title" ] && [ -z "$message" ]; then
  echo "usage: $(basename "$0") \"<title>\" \"<message>\" \"<link>\"" >&2
  exit 0
fi

line="🔔 $title"
[ -n "$message" ] && line="$line — $message"
[ -n "$link" ] && line="$line — $link"
printf '%s\n' "$line"

if command -v notify-send >/dev/null 2>&1; then
  body="$message"
  [ -n "$link" ] && body="${body:+$body
}$link"
  notify-send --app-name="LifeOS" -- "$title" "$body" >/dev/null 2>&1 || true
fi

# Wake the paired phones too, when the bridge is running. The title and link
# travel only inside a sealed hint; the push itself has no readable content.
# Best effort: nothing here may fail the caller.
env_file="${LIFEOS_CONFIG_DIR:-$HOME/.config/lifeos}/env"
if [ "${LIFEOS_NOTIFY_PUSH:-on}" != "off" ] && [ -r "$env_file" ] && command -v curl >/dev/null 2>&1 \
   && command -v python3 >/dev/null 2>&1; then
  token="$(sed -n 's/^LIFEOS_BRIDGE_TOKEN=//p' "$env_file" | head -n 1)"
  port="$(sed -n 's/^LIFEOS_BRIDGE_PORT=//p' "$env_file" | head -n 1)"
  if [ -n "$token" ]; then
    body="$(python3 -c 'import json,sys; print(json.dumps({"title": sys.argv[1], "link": sys.argv[2]}))' "$title" "$link" 2>/dev/null)"
    # The token is passed on standard input, not on the command line.
    printf 'header = "Authorization: Bearer %s"\n' "$token" | curl -s -m 5 -K - \
      -H 'Content-Type: application/json' -d "$body" \
      "http://127.0.0.1:${port:-8787}/push/notify" >/dev/null 2>&1 || true
  fi
fi

exit 0
