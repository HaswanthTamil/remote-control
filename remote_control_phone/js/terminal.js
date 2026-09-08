import { REMOTE_TOKEN, SERVER_URL } from "../config";

const form = document.getElementById("commandForm");
const input = document.getElementById("commandInput");
const terminal = document.querySelector(".terminal");

let socket = null;
let reconnectTimer = null;
let reconnectDelay = 1000;

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

  socket = new WebSocket(SERVER_URL);

  socket.addEventListener("open", () => {
    reconnectDelay = 1000;

    addLine("→ Connected", true);

    socket.send(
      JSON.stringify({
        type: "register",
        device: "phone",
        token: REMOTE_TOKEN,
      }),
    );
  });

  socket.addEventListener("message", (event) => {
    let message;

    try {
      message = JSON.parse(event.data);
    } catch {
      addLine(event.data);
      return;
    }

    switch (message.type) {
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
        break;

      case "ack":
        addLine("→ Command received", true);
        break;

      default:
        console.warn("Unknown server message:", message);
    }
  });

  socket.addEventListener("close", () => {
    socket = null;

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

connect();
input.focus();
