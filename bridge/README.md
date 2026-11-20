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

## The Agent SDK package

`claude-agent-sdk` (installed with `uv add`, version 0.2.186) is used by the
optional SDK runner (`LIFEOS_RUNNER=sdk`; see `docs/bridge.md`, "Runner").
What it brings:

- About 362 MB on disk in the virtual environment. Almost all of it is a copy
  of the Claude Code binary bundled in the package (`_bundled/claude`, a
  slightly newer patch version than a typical installed one). **The bridge
  never runs that copy.** It starts the installed `claude` through its own
  transport, and `sdk_runner.resolve_cli` refuses a path inside the package.
- Python dependencies it pulls in: `anyio`, `mcp`, `sse-starlette` and their
  dependencies. The bridge passes no MCP servers.
- The SDK's own process launcher is not used. It would pass the bridge's whole
  environment to the child and fall back to the bundled binary. The bridge
  keeps its allow-listed environment and its own command line, and uses the
  SDK for the protocol: the client, messages and interrupt.
