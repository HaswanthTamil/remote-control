import { PAIR_TOKEN, SERVER_URL } from "../config.js";
import {
  clearPaired,
  deviceId,
  isPaired,
  loadKeyPair,
  markPaired,
  publicKeyHex,
  sign,
} from "./auth.js";

const form = document.getElementById("commandForm");
const input = document.getElementById("commandInput");
const terminal = document.querySelector(".terminal");

// Accept http(s):// in config the same way as ws(s)://.
const relayUrl =
  SERVER_URL.startsWith("https://")
    ? "wss://" + SERVER_URL.slice("https://".length)
    : SERVER_URL.startsWith("http://")
      ? "ws://" + SERVER_URL.slice("http://".length)
      : SERVER_URL;
const relayHost = (() => {
  try {
    return new URL(relayUrl).host;
  } catch {
    return relayUrl;
  }
})();

let socket = null;
let reconnectTimer = null;
let reconnectDelay = 1000;
let authenticated = false;

let keyPair = null;
let phoneDeviceId = null;
let phonePublicKey = null;

function addLine(text, muted = false) {
  const line = document.createElement("div");
  line.className = "terminal-line" + (muted ? " muted" : "");
  line.textContent = text;

  terminal.appendChild(line);
  terminal.scrollTop = terminal.scrollHeight;
}

function addOutput(data, type = "stdout") {
  const line = document.createElement("div");
  line.className = `terminal-line ${type}`;

  line.textContent = data;

  terminal.appendChild(line);
  terminal.scrollTop = terminal.scrollHeight;
}

function connect() {
  if (
    socket &&
    (socket.readyState === WebSocket.OPEN ||
      socket.readyState === WebSocket.CONNECTING)
  ) {
    return;
  }

  addLine("→ Connecting...", true);

  socket = new WebSocket(relayUrl);

  socket.addEventListener("open", () => {
    addLine("→ Connected, authenticating...", true);

    // A brand-new phone (not yet in the relay keystore) needs the one-time
    // pair token; once the relay confirms registration we stop sending it.
    const register = {
      type: "register",
      device: "phone",
      device_id: phoneDeviceId,
      public_key: phonePublicKey,
    };
    if (!isPaired(relayHost) && PAIR_TOKEN) {
      register.pair_token = PAIR_TOKEN;
    }

    socket.send(JSON.stringify(register));
  });

  socket.addEventListener("message", async (event) => {
    let message;

    try {
      message = JSON.parse(event.data);
    } catch {
      addLine(event.data);
      return;
    }

    switch (message.type) {
      case "challenge":
        // Sign the fresh, one-time nonce bound to this device id.
        socket.send(
          JSON.stringify({
            type: "auth",
            device_id: phoneDeviceId,
            signature: await sign(keyPair, `${phoneDeviceId}:${message.nonce}`),
          }),
        );
        break;

      case "registered":
        authenticated = true;
        reconnectDelay = 1000;
        markPaired(relayHost, phoneDeviceId);
        addLine("→ Authenticated", true);
        input.disabled = false;
        input.focus();
        break;

      case "output":
        addOutput(message.data, "stdout");
        break;

      case "stderr":
        addOutput(message.data, "stderr");
        break;

      case "exit":
        addLine(
          `--- process exited with code ${message.code ?? "unknown"} ---`,
          true,
        );
        break;

      case "error":
        addLine(`✗ ${message.message}`);
        // Self-healing: if the relay doesn't recognize a device we thought was
        // paired (empty/new keystore after a redeploy), forget the pairing so
        // the next reconnect retries with PAIR_TOKEN.
        if (
          typeof message.message === "string" &&
          message.message.toLowerCase().includes("not paired") &&
          isPaired(relayHost)
        ) {
          addLine("✗ Pairing state stale; clearing marker and re-pairing", true);
          clearPaired(relayHost);
        }
        break;

      case "ack":
        addLine("→ Command received", true);
        break;

      default:
        console.warn("Unknown server message:", message);
    }
  });

  socket.addEventListener("close", (event) => {
    authenticated = false;
    socket = null;

    // The relay replaced this socket with a newer connection from the same
    // device (another tab/window). That tab is now the active one, so don't
    // reconnect and fight it.
    if (event.code === 4001) {
      addLine("✗ Superseded by a newer connection. Not reconnecting.", true);
      input.disabled = true;
      return;
    }

    addLine("✗ Disconnected. Reconnecting...", true);

    scheduleReconnect();
  });

  socket.addEventListener("error", (error) => {
    console.error("WebSocket error:", error);
  });
}

function scheduleReconnect() {
  if (reconnectTimer) return;

  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    connect();

    reconnectDelay = Math.min(reconnectDelay * 2, 30000);
  }, reconnectDelay);
}

function sendCommand(command) {
  if (!authenticated) {
    addLine("✗ Not authenticated yet");
    return;
  }
  if (!socket || socket.readyState !== WebSocket.OPEN) {
    addLine("✗ Not connected to laptop");
    return;
  }

  const id = crypto.randomUUID();

  socket.send(
    JSON.stringify({
      type: "command",
      id,
      command,
    }),
  );

  addLine(`$ ${command}`);
}

form.addEventListener("submit", (event) => {
  event.preventDefault();

  const command = input.value.trim();

  if (!command) {
    return;
  }

  sendCommand(command);

  input.value = "";
  input.focus();
});

(async () => {
  keyPair = await loadKeyPair();
  phoneDeviceId = await deviceId(keyPair);
  phonePublicKey = await publicKeyHex(keyPair);

  if (!isPaired(relayHost) && !PAIR_TOKEN) {
    addLine("✗ Set PAIR_TOKEN in config.js to pair this phone", true);
    return;
  }

  input.disabled = true;
  input.placeholder = "Pairing...";
  connect();
  input.focus();
})();