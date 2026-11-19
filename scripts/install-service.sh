#!/usr/bin/env bash
# Install, update or remove the LifeOS bridge as a systemd user service.
#
#   scripts/install-service.sh              install or update, enable and start
#   scripts/install-service.sh --uninstall  stop, disable and remove
#
# Run this yourself; it writes to ~/.config/systemd/user/. No sudo needed.
set -euo pipefail

unit_name="lifeos-bridge.service"
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
bridge_dir="$repo_dir/bridge"
unit_dir="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
unit_file="$unit_dir/$unit_name"
config_dir="${LIFEOS_CONFIG_DIR:-$HOME/.config/lifeos}"

if [ "${1:-}" = "--uninstall" ]; then
  systemctl --user disable --now "$unit_name" 2>/dev/null || true
  rm -f "$unit_file"
  systemctl --user daemon-reload
  echo "removed $unit_name"
  exit 0
fi

if [ ! -x "$bridge_dir/.venv/bin/lifeos-bridge" ]; then
  if command -v uv >/dev/null 2>&1; then
    (cd "$bridge_dir" && uv sync --no-dev)
  else
    echo "error: $bridge_dir/.venv is missing and 'uv' is not installed." >&2
    echo "Install uv (https://docs.astral.sh/uv/), then run: cd bridge && uv sync" >&2
    exit 1
  fi
fi

if [ ! -f "$config_dir/env" ]; then
  "$repo_dir/scripts/init-config.sh"
fi

case "$bridge_dir" in
  *[[:space:]]*) echo "error: the repository path must not contain spaces" >&2; exit 1 ;;
esac

mkdir -p "$unit_dir"
sed "s|@BRIDGE_DIR@|$bridge_dir|g" "$bridge_dir/systemd/lifeos-bridge.service.in" > "$unit_file"
systemctl --user daemon-reload
systemctl --user enable "$unit_name"
systemctl --user restart "$unit_name"
sleep 1
systemctl --user --no-pager --lines=5 status "$unit_name" || true

if [ "$(loginctl show-user "$USER" -p Linger --value 2>/dev/null)" != "yes" ]; then
  echo
  echo "Note: the bridge currently stops when you log out and does not start at boot."
  echo "To keep it running, enable lingering once:  loginctl enable-linger \"\$USER\""
fi
