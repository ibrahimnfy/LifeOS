# LifeOS bridge

Small private HTTP server that runs Claude Code headless on the owner's
computer for the phone app. See [`docs/bridge.md`](../docs/bridge.md) for
setup, configuration and manual checks.

```
cd bridge
uv sync
uv run pytest
uv run ruff check . && uv run ruff format --check .
uv run lifeos-bridge
```
