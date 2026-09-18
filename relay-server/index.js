import "dotenv/config";
import http from "http";
import fs from "fs";
import path from "path";
import crypto from "crypto";
import { fileURLToPath } from "url";
import WebSocket, { WebSocketServer } from "ws";
import { createClient } from "@libsql/client";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const PORT = parseInt(process.env.PORT || "3000", 10);
const PAIR_TOKEN = process.env.PAIR_TOKEN;
const HEARTBEAT_INTERVAL = parseInt(process.env.HEARTBEAT_INTERVAL || "20000", 10);
const AUTH_TIMEOUT = parseInt(process.env.AUTH_TIMEOUT_MS || "10000", 10);
const KEYS_FILE = process.env.KEYS_FILE || path.join(__dirname, "devices.json");
// SQLite is auto-selected when a Turso URL is present, so a Vercel deploy only
// needs TURSO_URL + TURSO_AUTH_TOKEN (no separate KEYSTORE flag required).
const KEYSTORE = process.env.KEYSTORE || (process.env.TURSO_URL ? "sqlite" : "file"); // "file" | "sqlite"
const TURSO_URL = process.env.TURSO_URL; // https://<db>-<org>.turso.io or file:/path/to.db
const TURSO_AUTH_TOKEN = process.env.TURSO_AUTH_TOKEN;

if (!PAIR_TOKEN) {
  throw new Error("PAIR_TOKEN environment variable is required");
}

/* ------------------------------------------------------------------ */
/*  Device keystore backend: device_id -> { public_key, device,        */
/*  paired_at }.                                                       */
/*                                                                     */
/*  "file"   = devices.json (default; works on Docker/VPS with a       */
/*             volume mounted at KEYS_FILE).                           */
/*  "sqlite" = Turso/libSQL (works on Vercel's read-only filesystem,   */
/*             registered devices survive redeploys).                  */
/* ------------------------------------------------------------------ */

function createFileKeystore() {
  let devices = {};

  const load = () => {
    try {
      const parsed = JSON.parse(fs.readFileSync(KEYS_FILE, "utf8"));
      if (parsed && typeof parsed.devices === "object" && parsed.devices) {
        devices = parsed.devices;
      }
    } catch (err) {
      if (err.code !== "ENOENT") {
        console.error("Could not read keystore:", err.message);
      }
    }
  };

  const save = () => {
    const tmp = `${KEYS_FILE}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify({ version: 1, devices }, null, 2));
    fs.renameSync(tmp, KEYS_FILE);
  };

  load();

  return {
    get: async (id) => devices[id] || null,
    put: async (id, record) => {
      devices[id] = record;
      save();
    },
    count: async () => Object.keys(devices).length,
  };
}

function createSqliteKeystore() {
  const db = createClient({ url: TURSO_URL, authToken: TURSO_AUTH_TOKEN });

  return {
    raw: async (sql, args) => {
      if (args) {
        return db.execute({ sql, args });
      }
      return db.execute(sql);
    },
    get: async (id) => {
      const res = await db.execute({
        sql: "SELECT public_key, device, paired_at FROM devices WHERE device_id = ?",
        args: [id],
      });
      if (res.rows.length === 0) {
        return null;
      }
      return {
        public_key: String(res.rows[0].public_key),
        device: String(res.rows[0].device),
        paired_at: String(res.rows[0].paired_at),
      };
    },
    put: async (id, record) => {
      await db.execute({
        sql:
          "INSERT INTO devices (device_id, public_key, device, paired_at) VALUES (?, ?, ?, ?) " +
          "ON CONFLICT(device_id) DO UPDATE SET public_key=excluded.public_key, " +
          "device=excluded.device, paired_at=excluded.paired_at",
        args: [id, record.public_key, record.device, record.paired_at],
      });
    },
    count: async () => {
      const res = await db.execute("SELECT COUNT(*) AS n FROM devices");
      return Number(res.rows[0].n);
    },
  };
}

async function createKeystore() {
  if (KEYSTORE === "sqlite") {
    if (!TURSO_URL) {
      throw new Error(
        "KEYSTORE=sqlite requires TURSO_URL (and TURSO_AUTH_TOKEN for a remote Turso database)",
      );
    }
    const store = createSqliteKeystore();
    // Idempotent bootstrap/migration (also shipped as schema.sql so it can be
    // applied manually with `turso db shell`). CREATE TABLE IF NOT EXISTS is
    // safe to re-run on every boot.
    let schema = "CREATE TABLE IF NOT EXISTS devices (" +
      "device_id TEXT PRIMARY KEY, " +
      "public_key TEXT NOT NULL, " +
      "device TEXT NOT NULL, " +
      "paired_at TEXT NOT NULL)";
    try {
      schema = fs.readFileSync(path.join(__dirname, "schema.sql"), "utf8");
    } catch {
      // schema.sql missing -> use the inline default above
    }
    await store.raw(schema);
    return store;
  }
  return createFileKeystore();
}

const keystore = await createKeystore().catch((err) => {
  console.error(err.message);
  process.exit(1);
});
console.log(`Keystore backend: ${KEYSTORE}`);

/* ------------------------------------------------------------------ */
/*  Crypto helpers (Ed25519 public-key auth)                           */
/* ------------------------------------------------------------------ */

const HEX_RE = /^[0-9a-fA-F]+$/;

function fingerprint(publicKeyHex) {
  return crypto
    .createHash("sha256")
    .update(Buffer.from(publicKeyHex, "hex"))
    .digest("hex");
}

function verifyEd25519(publicKeyHex, message, signatureHex) {
  try {
    if (
      typeof publicKeyHex !== "string" ||
      !HEX_RE.test(publicKeyHex) ||
      publicKeyHex.length !== 64
    ) {
      return false;
    }
    if (
      typeof signatureHex !== "string" ||
      !HEX_RE.test(signatureHex) ||
      signatureHex.length !== 128
    ) {
      return false;
    }

    const x = Buffer.from(publicKeyHex, "hex").toString("base64url");
    const pub = crypto.createPublicKey({
      key: { kty: "OKP", crv: "Ed25519", x },
      format: "jwk",
    });

    return crypto.verify(
      null,
      Buffer.from(message, "utf8"),
      pub,
      Buffer.from(signatureHex, "hex"),
    );
  } catch {
    return false;
  }
}

function newNonce() {
  return crypto.randomBytes(32).toString("hex");
}

/* ------------------------------------------------------------------ */
/*  Relay plumbing                                                     */
/* ------------------------------------------------------------------ */

const server = http.createServer((req, res) => {
  res.writeHead(200, { "Content-Type": "text/plain" });
  res.end("Remote control relay is running\n");
});

const wss = new WebSocketServer({ server });

// Routable authenticated connections.
const clients = {
  phone: null,
  laptop: null,
};

function send(ws, message) {
  if (!ws || ws.readyState !== WebSocket.OPEN) {
    return false;
  }

  ws.send(JSON.stringify(message));
  return true;
}

function reject(ws, message) {
  send(ws, { type: "error", message });
}

function failAuth(ws, message) {
  reject(ws, message);
  ws.terminate();
}

/* ------------------------------------------------------------------ */
/*  Authentication state machine                                       */
/*                                                                     */
/*  WS client phases:                                                  */
/*    "init"        -> register with device_id + public_key (+pair)    */
/*    "challenged"  -> verify signature over fresh nonce               */
/*    "ready"       -> routing only                                    */
/* ------------------------------------------------------------------ */

async function handleRegister(ws, message) {
  const { device, device_id, public_key, pair_token } = message;

  if (device !== "phone" && device !== "laptop") {
    return failAuth(ws, "Invalid device type");
  }
  if (
    typeof device_id !== "string" ||
    !HEX_RE.test(device_id) ||
    device_id.length !== 64
  ) {
    return failAuth(ws, "Invalid device_id");
  }
  if (typeof public_key !== "string" || !HEX_RE.test(public_key) || public_key.length !== 64) {
    return failAuth(ws, "Invalid public_key");
  }

  // Bind identity to the key: the device id is the key fingerprint.
  const fp = fingerprint(public_key);
  if (fp !== device_id) {
    return failAuth(ws, "device_id does not match public_key");
  }

  let known;
  try {
    known = await keystore.get(device_id);
  } catch (err) {
    console.error("keystore read error:", err.message);
    return failAuth(ws, "Keystore unavailable");
  }

  if (!known) {
    // One-time pairing: a fresh key is accepted only with the bootstrap token.
    if (typeof pair_token !== "string" || pair_token !== PAIR_TOKEN) {
      return failAuth(ws, "Device not paired and pair_token invalid");
    }

    try {
      await keystore.put(device_id, {
        public_key,
        device,
        paired_at: new Date().toISOString(),
      });
    } catch (err) {
      console.error("keystore write error:", err.message);
      return failAuth(ws, "Keystore unavailable");
    }
    console.log(`paired new ${device}: ${device_id.slice(0, 16)}...`);
  } else if (known.public_key !== public_key) {
    return failAuth(ws, "public_key does not match registered key");
  }

  ws.auth = {
    device_id,
    public_key,
    device,
    nonce: newNonce(),
  };
  ws.phase = "challenged";

  // Fail connections that never finish authentication.
  ws.authTimer = setTimeout(() => {
    if (ws.phase !== "ready") {
      ws.terminate();
    }
  }, AUTH_TIMEOUT);

  send(ws, { type: "challenge", nonce: ws.auth.nonce });
}

function handleAuth(ws, message) {
  const auth = ws.auth;

  if (!auth || message.device_id !== auth.device_id) {
    return failAuth(ws, "auth does not match pending registration");
  }
  if (typeof message.signature !== "string") {
    return failAuth(ws, "Invalid signature");
  }

  // Bind the signature to both the device and this session's fresh nonce.
  const signedPayload = `${auth.device_id}:${auth.nonce}`;

  if (!verifyEd25519(auth.public_key, signedPayload, message.signature)) {
    return failAuth(ws, "Invalid signature");
  }

  // Nonce is consumed: it cannot be replayed, not even on this connection.
  ws.phase = "ready";
  ws.device = auth.device;
  ws.authenticated = true;
  clearTimeout(ws.authTimer);
  ws.auth = null;

  // Replace any older connection for the same device. Use an application
  // close code so the loser can tell "I was superseded" apart from a real
  // drop, and stop its reconnect loop.
  if (clients[auth.device] && clients[auth.device] !== ws) {
    console.log(
      `[${new Date().toISOString().slice(11, 23)}] replacing old ${auth.device} connection (4001)`,
    );
    clients[auth.device].close(4001, "replaced by a newer connection");
  }
  clients[auth.device] = ws;

  console.log(`${auth.device} connected (${auth.device_id.slice(0, 16)}...)`);

  send(ws, { type: "registered", device: auth.device });
}

function handleRouting(ws, message) {
  /*
   * PHONE → LAPTOP
   * { "type": "command", "id": "...", "command": "ls -la" }
   */
  if (message.type === "command") {
    if (ws.device !== "phone") {
      return;
    }
    if (typeof message.command !== "string") {
      return send(ws, {
        type: "error",
        id: message.id,
        message: "Invalid command",
      });
    }

    const delivered = send(clients.laptop, message);
    if (delivered) {
      send(ws, { type: "ack", id: message.id });
    } else {
      send(ws, {
        type: "error",
        id: message.id,
        message: "Laptop is not connected",
      });
    }
    return;
  }

  /*
   * LAPTOP → PHONE
   * { "type": "output"|"stderr"|"exit"|"error"|"ack", ... }
   */
  if (
    message.type === "output" ||
    message.type === "stderr" ||
    message.type === "exit" ||
    message.type === "error" ||
    message.type === "ack"
  ) {
    if (ws.device !== "laptop") {
      return;
    }
    send(clients.phone, message);
    return;
  }

  send(ws, {
    type: "error",
    message: `Unknown message type: ${message.type}`,
  });
}

/* ------------------------------------------------------------------ */
/*  Connection handling + heartbeat                                    */
/* ------------------------------------------------------------------ */

wss.on("connection", (ws) => {
  ws.phase = "init";
  ws.device = null;
  ws.auth = null;
  ws.authTimer = null;
  ws.isAlive = true;

  ws.on("pong", () => {
    ws.isAlive = true;
  });

  ws.on("message", (raw) => {
    let message;

    try {
      message = JSON.parse(raw.toString());
    } catch {
      return reject(ws, "Invalid JSON");
    }

    if (typeof message !== "object" || message === null) {
      return reject(ws, "Invalid message");
    }

    switch (ws.phase) {
      case "init":
        if (message.type === "register") {
          handleRegister(ws, message).catch((err) => {
            console.error("register error:", err.message);
          });
          return;
        }
        return failAuth(ws, "First message must be a register message");

      case "challenged":
        if (message.type === "auth") {
          return handleAuth(ws, message);
        }
        return failAuth(ws, "Expected an auth message");

      case "ready":
        return handleRouting(ws, message);

      default:
        return failAuth(ws, "Invalid connection state");
    }
  });

  ws.on("close", (code, reason) => {
    clearTimeout(ws.authTimer);
    if (ws.device && clients[ws.device] === ws) {
      clients[ws.device] = null;
    }
    console.log(
      `[${new Date().toISOString().slice(11, 23)}] ${ws.device || "unregistered"} disconnected ` +
        `code=${ws._closeCode ?? code} reason=${(reason || "").slice(0, 40)} ` +
        `serverSentFrame=${ws._closeFrameSent === true} isAlive=${ws.isAlive}`,
    );
  });

  ws.on("error", (error) => {
    console.error(`relay websocket error (${ws.device || "unknown"}):`, error.message);
  });
});

const heartbeat = setInterval(() => {
  for (const ws of wss.clients) {
    if (ws.isAlive === false) {
      console.log(
        `[${new Date().toISOString().slice(11, 23)}] heartbeat terminating ${ws.device || "unregistered"}`,
      );
      ws.terminate();
      continue;
    }
    ws.isAlive = false;
    ws.ping();
  }
}, HEARTBEAT_INTERVAL);

wss.on("close", () => {
  clearInterval(heartbeat);
});

server.listen(PORT, async () => {
  console.log(`Relay listening on port ${PORT}`);
  try {
    console.log(`Authenticated devices: ${await keystore.count()}`);
  } catch (err) {
    console.error("keystore count error:", err.message);
  }
});