import { REMOTE_TOKEN } from "./config";

const http = require("http");
const WebSocket = require("ws");

const PORT = process.env.PORT || 3000;
const DEVICE_TOKEN = process.env.DEVICE_TOKEN;

if (!DEVICE_TOKEN) {
  throw new Error("DEVICE_TOKEN environment variable is required");
}

const server = http.createServer((req, res) => {
  res.writeHead(200, {
    "Content-Type": "text/plain"
  });

  res.end("Remote control relay is running\n");
});

const wss = new WebSocket.Server({
  server
});

const clients = {
  phone: null,
  laptop: null
};

function send(ws, message) {
  if (!ws || ws.readyState !== WebSocket.OPEN) {
    return false;
  }

  ws.send(JSON.stringify(message));
  return true;
}

function registerClient(ws, device, token) {
  if (token !== DEVICE_TOKEN) {
    send(ws, {
      type: "error",
      message: "Invalid device token"
    });

    ws.close();
    return false;
  }

  if (device !== "phone" && device !== "laptop") {
    send(ws, {
      type: "error",
      message: "Invalid device type"
    });

    ws.close();
    return false;
  }

  // Replace an existing connection for this device.
  if (clients[device]) {
    clients[device].close();
  }

  clients[device] = ws;
  ws.device = device;

  console.log(`${device} connected`);

  send(ws, {
    type: "registered",
    device
  });

  return true;
}

wss.on("connection", (ws) => {
  ws.device = null;
  ws.registered = false;

  ws.on("message", (raw) => {
    let message;

    try {
      message = JSON.parse(raw.toString());
    } catch {
      send(ws, {
        type: "error",
        message: "Invalid JSON"
      });

      return;
    }

    /*
     * First message must register the device.
     *
     * {
     *   "type": "register",
     *   "device": "phone",
     *   "token": "..."
     * }
     */
    if (!ws.registered) {
      if (message.type !== "register") {
        send(ws, {
          type: "error",
          message: "First message must be a register message"
        });

        ws.close();
        return;
      }

      const registered = registerClient(
        ws,
        message.device,
        message.token
      );

      if (registered) {
        ws.registered = true;
      }

      return;
    }

    /*
     * PHONE → LAPTOP
     *
     * {
     *   "type": "command",
     *   "id": "...",
     *   "command": "ls -la"
     * }
     */
    if (message.type === "command") {
      if (ws.device !== "phone") {
        return;
      }

      if (typeof message.command !== "string") {
        send(ws, {
          type: "error",
          message: "Invalid command"
        });

        return;
      }

      const delivered = send(clients.laptop, message);

      if (delivered) {
        send(ws, {
          type: "ack",
          id: message.id
        });
      } else {
        send(ws, {
          type: "error",
          id: message.id,
          message: "Laptop is not connected"
        });
      }

      return;
    }

    /*
     * LAPTOP → PHONE
     *
     * {
     *   "type": "output",
     *   "id": "...",
     *   "data": "hello\\n"
     * }
     *
     * Same mechanism handles:
     *   output
     *   stderr
     *   exit
     *   error
     */
    if (
      message.type === "output" ||
      message.type === "stderr" ||
      message.type === "exit" ||
      message.type === "error"
    ) {
      if (ws.device !== "laptop") {
        return;
      }

      send(clients.phone, message);
      return;
    }

    send(ws, {
      type: "error",
      message: `Unknown message type: ${message.type}`
    });
  });

  ws.on("close", () => {
    if (ws.device && clients[ws.device] === ws) {
      clients[ws.device] = null;
    }

    console.log(`${ws.device || "unregistered"} disconnected`);
  });

  ws.on("error", (error) => {
    console.error(
      `${ws.device || "unknown"} websocket error:`,
      error.message
    );
  });
});

server.listen(PORT, () => {
  console.log(`Relay listening on port ${PORT}`);
});





