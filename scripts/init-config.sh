#!/usr/bin/env bash
# Create ~/.config/lifeos/env with a fresh random bridge token.
#
# Safe to re-run: an existing file is kept, and a token is only added when
# none is set. The token is never printed.
set -euo pipefail
umask 077

config_dir="${LIFEOS_CONFIG_DIR:-$HOME/.config/lifeos}"
env_file="$config_dir/env"
example="$(cd "$(dirname "$0")/.." && pwd)/config/env.example"

mkdir -p "$config_dir"
chmod 700 "$config_dir"

if [ ! -f "$env_file" ]; then
  cp "$example" "$env_file"
  echo "created $env_file from the template"
fi
chmod 600 "$env_file"

if grep -Eq '^(export )?LIFEOS_BRIDGE_TOKEN=.+' "$env_file"; then
  echo "LIFEOS_BRIDGE_TOKEN is already set; nothing changed"
else
  token="$(python3 -c 'import secrets; print(secrets.token_urlsafe(48))')"
  # Drop an empty placeholder line, then append the real value.
  sed -i -E '/^(export )?LIFEOS_BRIDGE_TOKEN=$/d' "$env_file"
  printf 'LIFEOS_BRIDGE_TOKEN=%s\n' "$token" >> "$env_file"
  echo "generated a new LIFEOS_BRIDGE_TOKEN in $env_file (not shown)"
fi
