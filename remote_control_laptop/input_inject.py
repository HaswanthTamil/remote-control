"""Wayland-native mouse/keyboard injection for Hyprland.

Uses the wlroots compositor protocols implemented by Hyprland:
  * zwlr_virtual_pointer_manager_v1  -> zwlr_virtual_pointer_v1
  * zwp_virtual_keyboard_manager_v1  -> zwp_virtual_keyboard_v1

No X11 / xdotool / libei involved. Coordinates are normalized (0.0-1.0) by
callers; this module maps them to absolute output space. Screen geometry is
queried from Hyprland via `hyprctl monitors -j` (JSON), so we stay aligned
with the compositor's logical coordinate space used by the ScreenCast stream.
"""

from __future__ import annotations

import json
import os
import subprocess
import threading
import time

from pywayland.client import Display
from pywayland.protocol.wayland import WlKeyboard, WlSeat

from wayland_protocols.virtual_keyboard_unstable_v1 import (
    ZwpVirtualKeyboardManagerV1,
    ZwpVirtualKeyboardV1,
)
from wayland_protocols.virtual_pointer_unstable_v1 import (
    ZwlrVirtualPointerManagerV1,
    ZwlrVirtualPointerV1,
)

from evdev_keycodes import keycode_for_name

# wl_pointer.button_state
BTN_STATE_RELEASED = 0
BTN_STATE_PRESSED = 1

# wl_pointer.axis
AXIS_VERTICAL_SCROLL = 0
AXIS_HORIZONTAL_SCROLL = 1

# wl_pointer button codes (from linux/input-event-codes.h via libinput)
BTN_LEFT = 0x110
BTN_RIGHT = 0x111
BTN_MIDDLE = 0x112

BUTTON_CODES = {
    "left": BTN_LEFT,
    "right": BTN_RIGHT,
    "middle": BTN_MIDDLE,
}

# zwp_virtual_keyboard_v1.key_state
KEY_STATE_RELEASED = 0
KEY_STATE_PRESSED = 1

# zwp_virtual_keyboard_v1.keymap_format
XKB_KEYMAP_FORMAT_NO_V1 = 1

# XKB modifier bit masks for the modifiers() request. Indices follow the
# standard xkb common_iso6442 modmap used by every mainstream layout:
#   Shift=0  Lock=1  Control=2  Mod1=3  Mod2=4  Mod3=5  Mod4=6  Mod5=7
# Super is Mod4 (bit 6), Alt is Mod1 (bit 3).
_XKB_MOD_BITS = {
    "SHIFT": 1 << 0, "LEFTSHIFT": 1 << 0, "RIGHTSHIFT": 1 << 0,
    "CTRL": 1 << 2, "LEFTCTRL": 1 << 2, "RIGHTCTRL": 1 << 2,
    "ALT": 1 << 3, "LEFTALT": 1 << 3, "RIGHTALT": 1 << 3,
    "SUPER": 1 << 6, "LEFTMETA": 1 << 6, "RIGHTMETA": 1 << 6,
}


def _monotonic_ms() -> int:
    return int(time.monotonic() * 1000)


def query_monitor_geometry() -> dict | None:
    """Return {'x','y','width','height'} of the focus/monitor for mapping.

    Queries `hyprctl monitors -j`; falls back to environment-ish hints if the
    query fails (capture should keep working even if input mapping degrades).
    """
    try:
        out = subprocess.run(
            ["hyprctl", "monitors", "-j"],
            capture_output=True, text=True, timeout=5,
        )
        monitors = json.loads(out.stdout)
    except Exception:
        return None
    if not monitors:
        return None
    mon = monitors[0]
    return {
        "x": int(mon.get("x", 0)),
        "y": int(mon.get("y", 0)),
        "width": int(mon.get("width", 0)),
        "height": int(mon.get("height", 0)),
    }


class WaylandInput:
    """Injects pointer + keyboard events straight into the Wayland session."""

    SWAY_LOCK = None

    def __init__(self):
        self.display: Display | None = None
        self.pointer: ZwlrVirtualPointerV1Proxy | None = None
        self.keyboard: ZwpVirtualKeyboardV1Proxy | None = None
        self.geometry: dict | None = None
        self._lock = threading.Lock()
        self._connected = False
        self._keymap_bytes = b""
        self._keymap_sent = False
        self._held_mods = 0

    # -- connection ------------------------------------------------------

    def connect(self) -> bool:
        if self._connected:
            return True
        display = Display(os.environ.get("WAYLAND_DISPLAY", "wayland-0"))
        display.connect()
        registry = display.get_registry()

        def on_global(registry, name, interface, version):
            if interface == "wl_seat":
                self._seat = registry.bind(name, WlSeat, min(version, 4))
            elif interface == "zwlr_virtual_pointer_manager_v1":
                self._vp_mgr = registry.bind(
                    name, ZwlrVirtualPointerManagerV1,
                    min(version, ZwlrVirtualPointerManagerV1.version),
                )
            elif interface == "zwp_virtual_keyboard_manager_v1":
                self._vk_mgr = registry.bind(
                    name, ZwpVirtualKeyboardManagerV1,
                    min(version, ZwpVirtualKeyboardManagerV1.version),
                )

        registry.dispatcher["global"] = on_global
        self.display = display
        display.roundtrip()

        if getattr(self, "_vp_mgr", None) is None:
            raise RuntimeError(
                "zwlr_virtual_pointer_manager_v1 not advertised (not a "
                "wlroots-compatible compositor?)"
            )
        if getattr(self, "_vk_mgr", None) is None:
            raise RuntimeError("zwp_virtual_keyboard_manager_v1 not advertised")

        self._bind_seat_keyboard()
        self.geometry = query_monitor_geometry()
        self._connected = True
        return True

    def _bind_seat_keyboard(self):
        """Bind wl_keyboard so we receive the compositor's real keymap.

        zwp_virtual_keyboard_v1 requires a keymap to be set before any key
        event; without it the compositor cannot resolve keycodes, so Hyprland
        keybinds and typed text go nowhere. We capture the live keymap from
        the seat and mirror it into the virtual keyboard.
        """
        try:
            kbd = getattr(self, "_seat", None).get_keyboard()
        except Exception:
            return

        def on_keymap(keyboard, format, fd, size):
            if format != 1:  # wl_keyboard.keymap_format: xkb_v1 = 1
                return
            try:
                # pywayland hands the fd over at EOF; rewind before reading.
                os.lseek(fd, 0, os.SEEK_SET)
                data = bytearray()
                while len(data) < size:
                    chunk = os.read(fd, size - len(data))
                    if not chunk:
                        break
                    data += chunk
            except OSError:
                return
            self._keymap_bytes = bytes(data)

        kbd.dispatcher["keymap"] = on_keymap
        self._kb_proxy = kbd
        # Roundtrip so the keymap event is received and processed.
        try:
            self.display.roundtrip()
        except Exception:
            pass

    def _send_keymap(self):
        """Push the captured keymap into the virtual keyboard (once)."""
        if self._keymap_sent or not self._keymap_bytes:
            return self._keymap_bytes != b""
        try:
            size = len(self._keymap_bytes)
            fd = os.memfd_create("rc-virtual-keymap", 0)
            try:
                os.write(fd, self._keymap_bytes)
            except OSError:
                pass
            with self._lock:
                self.keyboard.keymap(XKB_KEYMAP_FORMAT_NO_V1, fd, size)
            os.close(fd)
            self._flush()
            self._keymap_sent = True
            return True
        except Exception:
            # memfd unavailable / not-yet-created keyboard: skip keymap, keep
            # sending raw key events as before rather than failing hard.
            return False

    def create_inputs(self):
        """Create the virtual pointer and keyboard objects (call after connect)."""
        with self._lock:
            seat = getattr(self, "_seat", None)
            if seat is None:
                raise RuntimeError("no wl_seat bound (no seat available)")
            self.pointer = self._vp_mgr.create_virtual_pointer(seat)
            self.keyboard = self._vk_mgr.create_virtual_keyboard(seat)
        self._send_keymap()
        self._flush()

    # -- pointer ---------------------------------------------------------

    def pointer_move(self, x: float, y: float) -> None:
        """Move pointer to normalized (x, y) in 0.0-1.0 relative to the monitor."""
        if not self._ensure_ready():
            return
        g = self.geometry or {"x": 0, "y": 0, "width": 1920, "height": 1080}
        px = int(g["x"] + x * g["width"])
        py = int(g["y"] + y * g["height"])
        px = max(0, min(g["width"], px))
        py = max(0, min(g["height"], py))
        with self._lock:
            self.pointer.motion_absolute(
                _monotonic_ms(), px, py, g["width"], g["height"]
            )
            self.pointer.frame(_monotonic_ms())
        self._flush()

    def pointer_button(self, button: str, down: bool) -> None:
        if not self._ensure_ready():
            return
        code = BUTTON_CODES.get(button)
        if code is None:
            return
        with self._lock:
            self.pointer.button(
                _monotonic_ms(), code, BTN_STATE_PRESSED if down else BTN_STATE_RELEASED
            )
            self.pointer.frame(_monotonic_ms())
        self._flush()

    def pointer_scroll(self, dx: float, dy: float) -> None:
        if not self._ensure_ready():
            return
        with self._lock:
            if dy:
                self.pointer.axis(_monotonic_ms(), AXIS_VERTICAL_SCROLL, dy)
            if dx:
                self.pointer.axis(_monotonic_ms(), AXIS_HORIZONTAL_SCROLL, dx)
            self.pointer.frame(_monotonic_ms())
        self._flush()

    # -- keyboard --------------------------------------------------------

    def key(self, key_name: str, down: bool) -> None:
        if not self._ensure_ready():
            return
        code = keycode_for_name(key_name)
        if code is None:
            return
        if not self._keymap_sent:
            self._send_keymap()
        with self._lock:
            mask = _XKB_MOD_BITS.get(key_name.upper().replace("KEY_", ""), 0)
            if down:
                self._held_mods |= mask
            else:
                self._held_mods &= ~mask
            self.keyboard.key(
                _monotonic_ms(), code, KEY_STATE_PRESSED if down else KEY_STATE_RELEASED
            )
            self.keyboard.modifiers(self._held_mods, 0, 0, 0)
        self._flush()

    # -- lifecycle helpers -----------------------------------------------

    def _ensure_ready(self) -> bool:
        if not self._connected:
            try:
                self.connect()
            except Exception:
                return False
        if self.pointer is None or self.keyboard is None:
            try:
                self.create_inputs()
            except Exception:
                return False
        return True

    def _flush(self):
        try:
            self.display.flush()
        except Exception:
            pass

    def close(self):
        with self._lock:
            for proxy in (
                getattr(self, "keyboard", None),
                getattr(self, "pointer", None),
                getattr(self, "_kb_proxy", None),
                getattr(self, "_seat", None),
                getattr(self, "_vp_mgr", None),
                getattr(self, "_vk_mgr", None),
            ):
                if proxy is not None:
                    try:
                        proxy._destroy()
                    except Exception:
                        pass
        if self.display is not None:
            try:
                self.display.disconnect()
            except Exception:
                pass
        self._connected = False


_instance: WaylandInput | None = None
_instance_lock = threading.Lock()


def get_input() -> WaylandInput:
    global _instance
    with _instance_lock:
        if _instance is None:
            _instance = WaylandInput()
        return _instance


def reset_input():
    global _instance
    with _instance_lock:
        if _instance is not None:
            _instance.close()
        _instance = None