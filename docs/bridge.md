# Bridge server

The bridge is a small HTTP server on the owner's computer. The phone app
talks to it over the private network; it runs Claude Code headless and
returns the answers.

## Security defaults

- Listens on `127.0.0.1` by default. Only localhost or the machine's
  Tailscale address are accepted as bind addresses; wildcard (`0.0.0.0`,
  `::`) and public addresses are refused at start-up.
- Every route except `GET /health` requires `Authorization: Bearer <token>`.
  Unknown paths are rejected with `401` too (default deny).
- The token lives in `~/.config/lifeos/env`. The bridge refuses to start
  without one, with one shorter than 32 characters, or when that file is
  readable by other users.
- Prompt contents are never logged. The access log holds method, path and
  status only.
- Interactive API docs and schema endpoints are disabled.

## Setup

Requirements: Python 3.12 or newer and [`uv`](https://docs.astral.sh/uv/).

```
scripts/init-config.sh        # creates ~/.config/lifeos/env with a random token
cd bridge
uv sync
```

Settings are documented in [`config/env.example`](../config/env.example).
Real environment variables override the file. `LIFEOS_CONFIG_DIR` points the
bridge (and the scripts) at a different config folder.

## Run

```
cd bridge
uv run lifeos-bridge
```

## Endpoints

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/health` | no | Liveness check; returns `{"status":"ok"}` and nothing else |
| GET | `/whoami` | yes | Lets a client verify its token; returns the bridge version |

## Manual check

With the bridge running in another terminal:

```
source ~/.config/lifeos/env
curl -s http://127.0.0.1:8787/health                      # {"status":"ok"}
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8787/whoami    # 401
curl -s -H "Authorization: Bearer $LIFEOS_BRIDGE_TOKEN" http://127.0.0.1:8787/whoami
                                                          # {"status":"ok","version":"..."}
```

Start-up refusals to try (each prints a configuration error and exits):

```
LIFEOS_BRIDGE_HOST=0.0.0.0 uv run lifeos-bridge
LIFEOS_BRIDGE_TOKEN=short uv run lifeos-bridge
```

## Development

```
cd bridge
uv run pytest
uv run ruff check . && uv run ruff format --check .
```

Tests never read the real `~/.config/lifeos`; they use a temporary folder.
