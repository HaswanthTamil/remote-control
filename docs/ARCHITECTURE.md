## Architecture

This project implements a simple relay-based remote control:

- Phone <-> WebSocket relay (Node.js in `relay-server/`) <-> Laptop agent (Python in `remote_control_laptop/`).

Laptop agent details

- The laptop agent now spawns a single persistent interactive `bash` shell using a PTY.
- Commands sent over the relay are written into that PTY so that shell state (cwd, env, aliases, functions) persists between commands.
- A sentinel string is emitted after each command to determine exit status reliably; terminal output is streamed back to the phone.
- The PTY and shell persist across WebSocket reconnects.

Protocol notes

- Commands: `{ "type": "command", "id": "...", "command": "..." }` — agent responds with `ack`, streams `output` messages and finally `exit` with the same `id`.
- Signals: `{ "type": "signal", "id": "...", "signal": "SIGINT" }` — agent will send a Ctrl-C to the PTY and attempt to signal the shell process.

Files

- `remote_control_laptop/agent.py` — laptop-side agent (PTY-backed shell)
- `relay-server/` — Node.js relay that forwards messages between clients
