// Remote desktop page: live screen + pointer/keyboard control over the relay.
//
// Screen frames arrive from the laptop as raw binary WebSocket messages (JPEG)
// and are drawn to a canvas letterboxed with `object-fit: contain` semantics.
// Touch gestures on the stage map to normalized (0.0-1.0) pointer coordinates
// relative to whatever part of the laptop screen is actually shown:
//
//   move              -> pointer.move (absolute)
//   tap               -> left click
//   hold & drag       -> left-drag (down on hold, up on release)
//   two-finger swipe  -> pointer.scroll (vertical delta)
//
// The keyboard panel sends zwp virtual-keyboard events: KEY_* names, explicit
// down/up pairs, and independent modifier toggles (CTRL/ALT/SHIFT/SUPER).

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
let capturing = false;

let keyPair = null;
let phoneDeviceId = null;
let phonePublicKey = null;

const stage = document.getElementById("screenStage");
const canvas = document.getElementById("screenCanvas");
const ctx = canvas.getContext("2d");
const hint = document.getElementById("screenHint");
const badge = document.getElementById("screenBadge");
const toggle = document.getElementById("captureToggle");
const connDot = document.getElementById("connDot");
const connText = document.getElementById("connText");
const keyboardPanel = document.getElementById("keyboardPanel");

let imgBox = { x: 0, y: 0, w: 0, h: 0 };
let lastBitmap = null;

/* ------------------------------------------------------------------ */
/*  Connection (mirrors terminal.js)                                   */
/* ------------------------------------------------------------------ */

function setConn(state) {
  if (state === "ready") {
    connDot.style.background = "#61d095";
    connText.textContent = "Ready";
  } else if (state === "connecting") {
    connDot.style.background = "#e8b34b";
    connText.textContent = "Connecting…";
  } else {
    connDot.style.background = "#4a5260";
    connText.textContent = "Offline";
  }
}

function send(message) {
  if (!socket || socket.readyState !== WebSocket.OPEN) {
    return;
  }
  socket.send(JSON.stringify(message));
}

function connect() {
  if (
    socket &&
    (socket.readyState === WebSocket.OPEN ||
      socket.readyState === WebSocket.CONNECTING)
  ) {
    return;
  }

  setConn("connecting");
  socket = new WebSocket(relayUrl);
  socket.binaryType = "arraybuffer";

  socket.addEventListener("open", () => {
    const register = {
      type: "register",
      device: "phone",
      device_id: phoneDeviceId,
      public_key: phonePublicKey,
    };
    if (!isPaired(relayHost) && PAIR_TOKEN) {
      register.pair_token = PAIR_TOKEN;
    }
    send(register);
  });

  socket.addEventListener("message", async (event) => {
    if (typeof event.data === "string") {
      await handleText(await tryParse(event.data));
      return;
    }
    handleFrame(event.data);
  });

  socket.addEventListener("close", (event) => {
    authenticated = false;
    socket = null;
    setConn("offline");
    if (event.code === 4001) {
      hint.textContent = "Superseded by another window. Not reconnecting.";
      toggle.disabled = true;
      return;
    }
    hint.textContent = "Reconnecting…";
    scheduleReconnect();
  });

  socket.addEventListener("error", () => {
    console.error("WebSocket error");
  });
}

function tryParse(text) {
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

async function handleText(message) {
  if (typeof message === "string") {
    return;
  }

  switch (message.type) {
    case "challenge":
      send({
        type: "auth",
        device_id: phoneDeviceId,
        signature: await sign(keyPair, `${phoneDeviceId}:${message.nonce}`),
      });
      break;

    case "registered":
      authenticated = true;
      reconnectDelay = 1000;
      markPaired(relayHost, phoneDeviceId);
      setConn("ready");
      startCapture();
      break;

    case "screen.status":
      handleScreenStatus(message);
      break;

    case "error":
      if (
        typeof message.message === "string" &&
        message.message.toLowerCase().includes("not paired") &&
        isPaired(relayHost)
      ) {
        clearPaired(relayHost);
      }
      if (!capturing) {
        hint.textContent = message.message || "Error";
      }
      break;

    default:
      console.warn("Unknown server message:", message);
  }
}

function scheduleReconnect() {
  if (reconnectTimer) return;
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    connect();
    reconnectDelay = Math.min(reconnectDelay * 2, 30000);
  }, reconnectDelay);
}

/* ------------------------------------------------------------------ */
/*  Screen capture / frames                                            */
/* ------------------------------------------------------------------ */

function handleScreenStatus(message) {
  const active = Boolean(message.active);

  if (active) {
    capturing = true;
    hint.style.display = "none";
    toggle.textContent = "■";
    toggle.classList.add("capturing");
    toggle.title = "Stop capture";
    if (message.width && message.height && message.message) {
      badge.hidden = false;
      badge.textContent = `${message.width}×${message.height}`;
    }
  } else {
    capturing = false;
    toggle.textContent = "▶";
    toggle.classList.remove("capturing");
    toggle.title = "Start capture";
    hint.style.display = "grid";
    hint.textContent =
      message.message && message.message.includes("stopped")
        ? "Capture stopped"
        : "Not capturing";
  }
}

function startCapture() {
  if (!authenticated) return;
  send({ type: "screen.request", action: "start" });
  hint.textContent = "Starting capture…";
}

function stopCapture() {
  send({ type: "screen.request", action: "stop" });
}

function handleFrame(buffer) {
  const blob = new Blob([buffer], { type: "image/jpeg" });
  createImageBitmap(blob)
    .then((bitmap) => {
      if (lastBitmap && lastBitmap !== bitmap) {
        try {
          lastBitmap.close();
        } catch {
          /* ignore */
        }
      }
      lastBitmap = bitmap;
      hint.style.display = "none";
      draw(bitmap);
    })
    .catch(() => {
      // JPEG decode failure: drop this frame (newest-wins anyway).
    });
}

function draw(bitmap) {
  const cw = canvas.clientWidth;
  const ch = canvas.clientHeight;
  if (canvas.width !== cw || canvas.height !== ch) {
    canvas.width = cw;
    canvas.height = ch;
  }

  const bw = bitmap.width;
  const bh = bitmap.height;
  const scale = Math.min(cw / bw, ch / bh);
  const w = bw * scale;
  const h = bh * scale;
  const x = (cw - w) / 2;
  const y = (ch - h) / 2;

  imgBox = { x, y, w, h };

  ctx.clearRect(0, 0, cw, ch);
  ctx.drawImage(bitmap, x, y, w, h);

  badge.hidden = false;
  badge.textContent = `${Math.round(bw)}×${Math.round(bh)}`;
}

/* ------------------------------------------------------------------ */
/*  Touch / pointer control                                            */
/* ------------------------------------------------------------------ */

const DRAG_THRESHOLD = 8;
const TAP_TIMEOUT = 280;
const LONG_PRESS = 430;

let activeTouches = new Map(); // id -> {x, y, t}
let gesture = null; // null | "pending" | "drag"
let pointerHeld = false;
let pressTimer = null;
let moveQueued = false;
let queuedNormalized = null;

function sendPointer(x, y) {
  send({ type: "pointer.move", x, y });
}

function toNormalized(clientX, clientY) {
  const rect = stage.getBoundingClientRect();
  let tx = clientX - rect.left;
  let ty = clientY - rect.top;
  if (imgBox.w > 0) {
    tx = (tx - imgBox.x) / imgBox.w;
    ty = (ty - imgBox.y) / imgBox.h;
  } else {
    tx /= rect.width;
    ty /= rect.height;
  }
  return {
    x: Math.max(0, Math.min(1, tx)),
    y: Math.max(0, Math.min(1, ty)),
  };
}

function pressButton(button, down) {
  send({ type: "pointer.button", button, down });
  if (button === "left") pointerHeld = down;
}

function releaseHeldPointer() {
  if (pointerHeld) {
    pressButton("left", false);
    pointerHeld = false;
  }
}

function cancelPressTimer() {
  if (pressTimer) {
    clearTimeout(pressTimer);
    pressTimer = null;
  }
}

stage.addEventListener("touchstart", (e) => {
  e.preventDefault();
  for (const t of e.changedTouches) {
    activeTouches.set(t.identifier, { x: t.clientX, y: t.clientY, t: Date.now() });
  }
  cancelPressTimer();

  if (activeTouches.size >= 2) {
    releaseHeldPointer();
    gesture = null;
    return;
  }

  gesture = "pending";
  const id = e.changedTouches[0].identifier;
  const start = activeTouches.get(id);
  pressTimer = setTimeout(() => {
    pressTimer = null;
    if (gesture === "pending" && activeTouches.size === 1) {
      gesture = "drag";
      pressButton("left", true);
      const n = toNormalized(start.x, start.y);
      sendPointer(n.x, n.y);
    }
  }, LONG_PRESS);
});

stage.addEventListener("touchmove", (e) => {
  e.preventDefault();

  if (activeTouches.size >= 2) {
    let dy = 0;
    for (const t of e.changedTouches) {
      const rec = activeTouches.get(t.identifier);
      if (!rec) continue;
      dy += t.clientY - rec.y;
      rec.x = t.clientX;
      rec.y = t.clientY;
    }
    if (Math.abs(dy) > 0) {
      send({ type: "pointer.scroll", dx: 0, dy: dy * 1.5 });
    }
    releaseHeldPointer();
    gesture = null;
    return;
  }

  for (const t of e.changedTouches) {
    const rec = activeTouches.get(t.identifier);
    if (!rec) continue;
    const dx = t.clientX - rec.x;
    const dy = t.clientY - rec.y;
    rec.x = t.clientX;
    rec.y = t.clientY;

    if (gesture === "pending" && Math.hypot(dx, dy) > DRAG_THRESHOLD) {
      cancelPressTimer();
      gesture = "drag";
      pressButton("left", true);
    }

    if (gesture === "drag" || gesture === "pending") {
      const n = toNormalized(t.clientX, t.clientY);
      if (moveQueued) continue;
      moveQueued = true;
      queuedNormalized = n;
      setTimeout(() => {
        moveQueued = false;
        if (queuedNormalized) sendPointer(queuedNormalized.x, queuedNormalized.y);
      }, 16);
    }
  }
});

stage.addEventListener("touchend", (e) => {
  e.preventDefault();
  for (const t of e.changedTouches) {
    const rec = activeTouches.get(t.identifier);
    if (!rec) continue;
    const elapsed = Date.now() - rec.t;
    const moved = gesture === "drag";

    activeTouches.delete(t.identifier);

    if (activeTouches.size === 0) {
      cancelPressTimer();
      if (!moved && gesture === "pending" && elapsed < TAP_TIMEOUT) {
        const n = toNormalized(t.clientX, t.clientY);
        sendPointer(n.x, n.y);
        pressButton("left", true);
        setTimeout(() => pressButton("left", false), 60);
      } else if (pointerHeld) {
        const n = toNormalized(t.clientX, t.clientY);
        sendPointer(n.x, n.y);
        pressButton("left", false);
      }
      gesture = null;
    } else if (pointerHeld) {
      // One finger lifted during a drag with another still down: keep dragging.
      gesture = "drag";
    }
  }
});

const cancelAll = (e) => {
  e.preventDefault?.();
  activeTouches.clear();
  cancelPressTimer();
  releaseHeldPointer();
  gesture = null;
};
stage.addEventListener("touchcancel", cancelAll);

/* ------------------------------------------------------------------ */
/*  Click chips + keyboard                                             */
/* ------------------------------------------------------------------ */

function tapKey(keyName) {
  send({ type: "keyboard.key", key: keyName, down: true });
  setTimeout(() => send({ type: "keyboard.key", key: keyName, down: false }), 40);
}

function toggleModifier(button) {
  const mod = button.dataset.mod;
  const isHeld = button.classList.toggle("active");
  send({ type: "keyboard.key", key: mod, down: isHeld });
}

document.querySelectorAll("[data-click]").forEach((btn) => {
  btn.addEventListener("click", () => {
    const b = btn.dataset.click;
    pressButton(b, true);
    setTimeout(() => pressButton(b, false), 60);
  });
});

document.querySelectorAll("[data-key]").forEach((btn) => {
  btn.addEventListener("touchstart", (e) => {
    e.preventDefault();
    const key = btn.dataset.key;
    if (key === "BACKSPACE") {
      tapKey("BACKSPACE");
    } else {
      send({ type: "keyboard.key", key, down: true });
    }
  });
  btn.addEventListener("touchend", (e) => {
    e.preventDefault();
    if (btn.dataset.key === "BACKSPACE") return;
    send({ type: "keyboard.key", key: btn.dataset.key, down: false });
  });
});

document.querySelectorAll(".mod").forEach((btn) => {
  btn.addEventListener("click", () => toggleModifier(btn));
});

const typeForm = document.getElementById("typeForm");
const typeInput = document.getElementById("typeInput");

typeForm.addEventListener("submit", (e) => {
  e.preventDefault();
  const text = typeInput.value;
  typeInput.value = "";
  if (!text) return;
  typeString(text);
});

/* ------------------------------------------------------------------ */
/*  Character -> evdev KEY name with SHIFT resolution                  */
/* ------------------------------------------------------------------ */

const SHIFTED = {
  "~": "GRAVE", "!": "1", "@": "2", "#": "3", "$": "4", "%": "5",
  "^": "6", "&": "7", "*": "8", "(": "9", ")": "0",
  "_": "MINUS", "+": "EQUAL", "{": "LEFTBRACE", "}": "RIGHTBRACE",
  "|": "BACKSLASH", ":": "SEMICOLON", '"': "APOSTROPHE",
  "<": "COMMA", ">": "DOT", "?": "SLASH",
};
const PLAIN = {
  "-": "MINUS", "=": "EQUAL", "`": "GRAVE", "[": "LEFTBRACE",
  "]": "RIGHTBRACE", "\\": "BACKSLASH", ";": "SEMICOLON",
  "'": "APOSTROPHE", ",": "COMMA", ".": "DOT", "/": "SLASH",
  " ": "SPACE", "\t": "TAB", "\n": "ENTER", "\r": "ENTER",
};

function typeString(text) {
  const seq = [];
  for (const ch of text) {
    if (ch >= "a" && ch <= "z") {
      seq.push({ key: "KEY_" + ch.toUpperCase(), shift: false });
    } else if (ch >= "A" && ch <= "Z") {
      seq.push({ key: "KEY_" + ch, shift: true });
    } else if (ch >= "0" && ch <= "9") {
      seq.push({ key: "KEY_" + ch, shift: false });
    } else if (SHIFTED[ch]) {
      seq.push({ key: "KEY_" + SHIFTED[ch], shift: true });
    } else if (PLAIN[ch]) {
      seq.push({ key: "KEY_" + PLAIN[ch], shift: false });
    }
    // unmappable characters are skipped
  }

  let delay = 0;
  let shiftDown = false;
  for (const item of seq) {
    setTimeout(() => {
      if (item.shift && !shiftDown) {
        send({ type: "keyboard.key", key: "SHIFT", down: true });
        shiftDown = true;
      }
      send({ type: "keyboard.key", key: item.key, down: true });
      setTimeout(() => send({ type: "keyboard.key", key: item.key, down: false }), 30);
    }, delay);
    delay += 60;
  }
  setTimeout(() => {
    if (shiftDown) {
      send({ type: "keyboard.key", key: "SHIFT", down: false });
      shiftDown = false;
    }
  }, delay);
}

/* ------------------------------------------------------------------ */
/*  Boot                                                               */
/* ------------------------------------------------------------------ */

toggle.addEventListener("click", () => {
  if (!authenticated) return;
  if (capturing) {
    stopCapture();
  } else {
    startCapture();
  }
});

document.getElementById("keyboardToggle").addEventListener("click", () => {
  keyboardPanel.hidden = !keyboardPanel.hidden;
});

window.addEventListener("resize", () => {
  if (lastBitmap) draw(lastBitmap);
});

(async () => {
  keyPair = await loadKeyPair();
  phoneDeviceId = await deviceId(keyPair);
  phonePublicKey = await publicKeyHex(keyPair);

  if (!isPaired(relayHost) && !PAIR_TOKEN) {
    hint.textContent = "Set PAIR_TOKEN in config.js to pair this phone";
    return;
  }

  connect();
})();