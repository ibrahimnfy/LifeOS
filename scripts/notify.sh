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
# The interface is stable: phone push notifications will later be added
# behind this same script.

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

exit 0
