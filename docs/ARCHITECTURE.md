## Architecture

```
phone (browser, WebSocket)  <->  relay (Node.js, ws)  <->  laptop agent (Python, PTY bash)
```

### Authentication (Ed25519 challenge-response, no shared secret)

Every connection authenticates before any message is routed.

```
client                                   relay
  | register { device, device_id,        |
  |   public_key, pair_token? }          |
  |------------------------------------->|
  |                                      | device_id == sha256(public_key)?
  |                                      | key already in devices.json? else pair_token check
  |   challenge { nonce (random 32B) }   |
  |<-------------------------------------|
  | auth { device_id,                    |
  |   signature = sign(device_id:nonce) }|
  |------------------------------------->| verify Ed25519
  |   registered { device }              |
  |<-------------------------------------|
```

- `public_key` and `signature` travel as hex of the raw 32-byte / 64-byte values.
- Private keys exist only on the laptop (`remote_control_laptop/keys/#{id_ed25519}`, chmod 0600)
  and in the browser (`localStorage`, PKCS#8 DER). Node never sees them.
- The relay stores only public keys in `relay-server/devices.json` (gitignored).
- Each connection gets a fresh nonce; it is consumed on successful auth, so a
  captured handshake cannot be replayed. Auth must complete within 10 s.

### Heartbeat and availability

- Relay: pings every 20 s; `isAlive=false` connections are terminated on the
  next tick (≈40 s worst case). Incomplete auth connections time out after 10 s.
- Laptop agent: continues reconnecting with capped exponential backoff; sends
  its own pings (30 s interval / 10 s timeout) via websocket-client so it
  notices a dead server even without a close frame.
- Phone: browser WebSocket auto-pongs; the page reconnects with capped
  exponential backoff.
- Device slots (`clients.phone`, `clients.laptop`) only hold authenticated
  connections and are freed on close, so a dead peer frees its slot.

### Laptop agent (PTY-backed shell)

- Spawns one interactive `bash` in a PTY at start; shell state persists across
  all commands and WebSocket reconnects.
- Terminal echo is disabled once at spawn (`stty -echo`) so command lines and
  sentinels are never echoed into the stream.
- Each command is wrapped as `command; printf "\n<SENTINEL><id>:$?\n"` — the
  sentinel line carries the exit code. A reader thread scans the PTY stream
  for sentinels and emits `output` / `exit` messages. ANSI/OSC control
  sequences are stripped and `\r\n` normalised before forwarding.
- `_reader_loop` and the PTY are deliberately independent of the socket: a
  reconnect never resets the shell.

### Relay routing (authenticated connections only)

- `phone -> laptop`: `command` (relay replies `ack` or `error` if the laptop
  is offline).
- `laptop -> phone`: `output`, `stderr`, `exit`, `error`, `ack` are forwarded.
- `signal { id, signal }` is passed through to the laptop agent.

### Files

- `relay-server/index.js` — Node relay: keystore, Ed25519 verification, heartbeat, routing.
- `relay-server/devices.json` — runtime keystore of registered public keys (generated, gitignored).
- `remote_control_laptop/agent.py` — laptop agent (PTY-backed shell + reconnect).
- `remote_control_laptop/auth.py` — Ed25519 keypair generation/storage + signing.
- `remote_control_phone/js/auth.js` — browser WebCrypto Ed25519 identity.
- `remote_control_phone/js/terminal.js` — phone terminal UI + auth handshake.
- `run.sh` — starts relay and laptop agent together.