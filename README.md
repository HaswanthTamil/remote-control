# Remote control for laptop from phone

Run commands on a laptop from a phone.

```
phone (WebSocket) <-> relay (Node.js) <-> laptop agent (Python, PTY bash)
```

## Security model

- **Ed25519 public-key auth, no shared secrets on the wire.**
  Both the laptop and the phone generate an Ed25519 keypair (private key
  never leaves the device) and register their **public** key with the relay.
  The relay challenges each connection with a fresh, one-time random nonce;
  the client signs it and the relay verifies the signature. Nonces are
  single-use per connection, so captured handshakes cannot be replayed.
- A connection only gets `registered` and is only routed after a successful
  signature check. Unauthenticated messages (including `command`) are
  rejected and the connection is closed.
- `device_id` is `sha256(raw public key).hexdigest()` — it is the key's
  fingerprint, computed identically in Node, Python, and the browser, so it
  binds identity to the key.
- **pairing:** the relay only accepts a brand-new public key if the one-time
  `PAIR_TOKEN` is presented. After the first successful registration the key
  is stored in `relay-server/devices.json` and the token is never sent again.

## First-time setup

### 1. Relay

```bash
cd relay-server
cp .env.example .env
```

Edit `.env`, set `PAIR_TOKEN` to a long random string, e.g.
`openssl rand -hex 32`.

```bash
npm install
npm run dev
```

**Or run the relay as a Docker image:**

```bash
docker run -d --name remote-control-relay \
  -e PAIR_TOKEN=your-cluster-secret \
  -p 3000:3000 \
  -v relay-keys:/data \
  haswanthtamil/relay-server:latest
```

or with compose:

```bash
docker compose up -d --build
```

- `PAIR_TOKEN` is required and is set via environment variables at deploy
  time (the local `relay-server/.env` is excluded from the image so the
  secret is never published). Railway: set a `PAIR_TOKEN` variable in the
  dashboard; it will pass through to the container.
- `PORT` defaults to 3000 (Railway injects its own `PORT` automatically).
- Registered device keys persist in the `relay-keys` volume (mounted at
  `/data`, non-root `node` user, root FS is read-only).
- Healthcheck: `GET /` returns 200 while running.
- The container owns `3000`; stop a locally-running relay first.

On Railway, deploy the `relay-server/` directory (Dockerfile) and attach a
volume at `/data` so registrations survive redeploys.

**Vercel:** the server's filesystem is read-only, so the JSON keystore cannot
be written. Setting `TURSO_URL` + `TURSO_AUTH_TOKEN` (a free Turso database)
automatically switches the keystore to SQLite; `relay-server/schema.sql` is
applied at startup (or run `turso db shell <db> < schema.sql` manually).
Device registrations then persist in the database. If the relay ever reports
"Device not paired" for a device that was previously paired (e.g. an empty
keystore after a redeploy), the laptop agent and phone now detect that
automatically and re-pair with `PAIR_TOKEN` on the next connection.

### 2. Laptop agent

```bash
cd remote_control_laptop
cp config.example.py config.py
```

Set `PAIR_TOKEN` in `config.py` to the same value as the relay's. On first
run the agent generates an Ed25519 keypair into `remote_control_laptop/keys/`
(chmod 0600, gitignored) and pairs automatically:

```bash
python3 agent.py
```

The agent prints its `device id` / public key. Once paired the relay answers
`registered`; you can then blank out `PAIR_TOKEN` in `config.py`.

The agent runs a persistent interactive `bash` in a PTY, so `cd`, `export`,
aliases, etc. persist between commands. If the relay is restarted, the agent
reconnects automatically (with backoff) and re-authenticates with its stored
key — no pairing token needed.

**Run exactly one agent instance.** The relay allows a single connection per
device; if two agents (or two phone tabs) are running, they fight for the
slot and you get a connect/disconnect loop. The relay now closes the loser
with close code `4001` ("replaced by a newer connection") and the phone page
stops reconnecting when it sees that code. If you see the loop, stop the
extra processes/tabs (refresh the browser tab to load the updated page).

### 4. Local dashboard (laptop)

The agent serves a small operator UI on the laptop at
`http://<DASHBOARD_HOST>:<DASHBOARD_PORT>` (default `http://127.0.0.1:8787`,
set in `remote_control_laptop/config.py` — note this config currently uses
`8080`). Open it in a browser tab on the laptop. It shows:

- Live logs (streamed from the agent)
- Device identity (device id / fingerprint, public key)
- Pairing + connection state (relay URL, registered?, reconnect backoff)
- System vitals (CPU, memory, disk, load, uptime)

It binds to loopback by default because it has no auth of its own — don't
expose `DASHBOARD_HOST` to a network.

### 3. Phone

Serve `remote_control_phone/` over HTTPS (web crypto `Ed25519` and module
scripts require a secure context), e.g.:

```bash
cd remote_control_phone
python3 -m http.server 8080     # local testing
# for a real phone, use a host that other devices can reach over HTTPS
```

Set `SERVER_URL` and the same `PAIR_TOKEN` in `remote_control_phone/config.js`.
On first open the page generates an Ed25519 keypair (stored in `localStorage`)
and pairs automatically. Afterward you can delete the `PAIR_TOKEN` value.

Only one phone and one laptop are connected at a time; a newer connection for
the same device replaces the old one.

## Heartbeat / disconnect detection

- The relay pings every 20 s and kills connections that miss their pong
  (≈40 s worst case), freeing up the device slot. It also drops connections
  that never finish the auth handshake (10 s).
- The laptop agent and phone reconnect automatically with capped exponential
  backoff. The laptop additionally pings the relay (30 s interval, 10 s
  timeout) to drop a dead server socket from its side.

## Running commands

Commands: `{ "type": "command", "id": "...", "command": "..." }` — the agent
answers `ack`, streams `output`/`stderr`, then `exit` with the same `id` and
the real exit code. Terminal echo is disabled for the PTY's lifetime, so
output is clean.

Signals: `{ "type": "signal", "id": "...", "signal": "SIGINT" }` sends Ctrl-C
to the PTY.

## Test procedure

1. Start the relay and the laptop agent (see above). Expect:
   - relay log: `paired new laptop: <id>...` then `laptop connected`
   - agent log: `[connected]`, `[registered as laptop]`
   - laptop dashboard (http://127.0.0.1:8787): online pill + vitals populate
2. Send a simple command from the phone: `echo hello`. Expect the output and
   a `--- process exited with code 0 ---` line.
3. Send a failing command, e.g. `ls /nonexistent_path_xyz`. Expect the error
   and exit code `2`.
4. Confirm shell state persists: `cd /tmp` then `pwd` → `/tmp`.
5. Kill and restart `node relay-server/index.js`. Within ~35 s the agent
   log shows `[connected]` + `[registered as laptop]` again and commands still
   work — no re-pairing required.
6. Kill a phone's connection server-side (restart the relay) — the phone page
   shows `✗ Disconnected. Reconnecting...` then `→ Authenticated`.

Automated smoke test (test tooling, not in this repo): run several commands
via a WebSocket client speaking the same register → challenge → auth
handshake and assert outputs and exit codes.