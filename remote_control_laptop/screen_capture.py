"""Wayland-native screen capture for the laptop agent.

Stack (per PRD):
  XDG Desktop Portal (ScreenCast)  ->  PipeWire  ->  video frame  ->  JPEG

The portal flow lives on one dedicated thread and is purely sequential: each
ScreenCast method is a synchronous D-Bus call whose reply carries the request
object path; we then pump our own GLib main context until the portal emits the
``Response`` signal on that path. No async callback-dispatch subtleties, and
nothing blocks the agent's WebSocket/control loop.

Once ``Start`` returns a PipeWire node id, an in-process GStreamer pipeline
pulls JPEG-encoded frames off an ``appsink`` and publishes them into a
single-slot "newest frame wins" holder (PRD backpressure: bounded at one frame).

Any failure here is reported through ``on_status`` and returns cleanly so the
terminal and input subsystems keep working. No X11, no screenshots, no xdotool.
"""

from __future__ import annotations

import os
import subprocess
import threading
import time
import uuid

# Optional heavy deps: the agent must keep running (terminal + input) even if
# the portal/GStreamer/video stack is missing or broken on this machine.
try:
    import gi  # type: ignore[import-untyped]

    gi.require_version("Gio", "2.0")
    gi.require_version("GLib", "2.0")
    gi.require_version("Gst", "1.0")
    gi.require_version("GstApp", "1.0")
    from gi.repository import Gio, GLib, Gst, GstApp  # type: ignore[import-untyped]
    _GI_OK = True
except Exception:  # pragma: no cover - exercised only on machines without gobject
    _GI_OK = False
    Gio = GLib = Gst = GstApp = None  # type: ignore[assignment]

_PORTAL_BUS = "org.freedesktop.portal.Desktop"
_PORTAL_PATH = "/org/freedesktop/portal/desktop"
_SCREENCAST_IFACE = "org.freedesktop.portal.ScreenCast"
_REQUEST_IFACE = "org.freedesktop.portal.Request"

# Capture tuning (PRD: ~5 fps, newest-wins, bounded queue).
_FPS = 5
_MAX_W = 1280
_MAX_H = 1280
_JPEG_QUALITY = 70

# Timeouts (seconds). SelectSources waits for the human to click the share
# picker, so it gets a long window; the stream start should be quick.
_REQUEST_TIMEOUT = 60
_START_TIMEOUT = 20


def _in_wayland_session() -> bool:
    return bool(
        os.environ.get("WAYLAND_DISPLAY")
        and os.environ.get("XDG_RUNTIME_DIR")
    )


def capture_available() -> bool:
    return _GI_OK and _in_wayland_session()


def _bus_name_owned(conn, name: str) -> bool:
    try:
        res = conn.call_sync(
            "org.freedesktop.DBus",
            "/org/freedesktop/DBus",
            "org.freedesktop.DBus",
            "NameHasOwner",
            GLib.Variant("(s)", (name,)),
            None,
            Gio.DBusCallFlags.NONE,
            2000,
            None,
        )
        return bool(res.unpack()[0])
    except Exception:
        return False


def ensure_portal_broker() -> bool:
    """Make sure a working xdg-desktop-portal broker exists on the session bus.

    Fedora's user systemd unit is gated on ``graphical-session.target`` which
    does not start under a bare Hyprland login, so we spawn the broker
    ourselves. The ScreenCast *implementation* (xdg-desktop-portal-hyprland)
    is started by the broker on demand.
    """
    if not _GI_OK or not _in_wayland_session():
        return False

    conn = Gio.bus_get_sync(Gio.BusType.SESSION, None)
    if _bus_name_owned(conn, _PORTAL_BUS):
        return True

    for candidate in (
        "/usr/libexec/xdg-desktop-portal",
        "/usr/lib/xdg-desktop-portal",
    ):
        if not os.path.exists(candidate):
            continue
        try:
            subprocess.Popen(
                [candidate],
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                start_new_session=True,
            )
            break
        except Exception:
            continue
    else:
        return False

    for _ in range(50):
        if _bus_name_owned(conn, _PORTAL_BUS):
            return True
        time.sleep(0.1)
    return False


class ScreenCapture:
    """Portal session + GStreamer capture, publishing newest JPEG frames."""

    def __init__(self, on_status=None):
        self._on_status = on_status or (lambda text: None)
        self._conn = None
        self._ctx = None
        self._portal_thread = None
        self._node_id = None
        self._source_size = (0, 0)

        self._pipeline = None
        self._capture_thread = None
        self._capture_stop = threading.Event()

        # Newest-frame-wins slot (PRD backpressure: bounded at one frame).
        self._frame_lock = threading.Lock()
        self._latest_jpeg = None

        self._session_handle = None
        self._started = False

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    @property
    def active(self) -> bool:
        return self._started and not self._capture_stop.is_set()

    @property
    def source_size(self):
        return self._source_size

    def latest_frame(self) -> bytes | None:
        """Return the most recent complete JPEG, or None if none yet."""
        with self._frame_lock:
            return self._latest_jpeg

    def start(self) -> bool:
        if self._started:
            return True
        if not capture_available():
            self._on_status("screen: screen capture unavailable (no Wayland session)")
            return False
        if not ensure_portal_broker():
            self._on_status("screen: xdg-desktop-portal broker unavailable")
            return False
        self._portal_thread = threading.Thread(
            target=self._run_portal_flow, name="portal", daemon=True
        )
        self._portal_thread.start()
        return True

    def stop(self):
        self._capture_stop.set()
        if self._pipeline is not None:
            try:
                self._pipeline.set_state(Gst.State.NULL)
            except Exception:
                pass
        if self._session_handle and self._conn:
            try:
                self._conn.call_sync(
                    _PORTAL_BUS, self._session_handle,
                    _SCREENCAST_IFACE, "CloseSession",
                    GLib.Variant("(o)", (self._session_handle,)),
                    None, Gio.DBusCallFlags.NONE, 2000, None,
                )
            except Exception:
                pass
        self._started = False

    # ------------------------------------------------------------------
    # Portal flow (sequential, on the portal thread)
    # ------------------------------------------------------------------

    def _run_portal_flow(self):
        self._ctx = GLib.MainContext.new()
        GLib.MainContext.push_thread_default(self._ctx)
        try:
            self._conn = Gio.bus_get_sync(Gio.BusType.SESSION, None)
        except Exception as exc:
            self._on_status(f"screen: cannot reach session bus: {exc}")
            return

        proxy = self._proxy(_PORTAL_PATH, _SCREENCAST_IFACE)

        # 1. CreateSession --------------------------------------------------
        self._on_status("screen: creating portal session")
        opts = {
            "handle_token": GLib.Variant("s", uuid.uuid4().hex),
            "session_handle_token": GLib.Variant("s", uuid.uuid4().hex),
        }
        req = self._call(proxy, "CreateSession", "(a{sv})", (opts,))
        if req is None:
            return
        status, results = self._respond(req, _REQUEST_TIMEOUT)
        if status != 0:
            self._on_status(f"screen: CreateSession refused (status {status})")
            return
        self._session_handle = results.get("session_handle")
        self._on_status("screen: portal session created")

        # 2. SelectSources --------------------------------------------------
        opts = {
            "handle_token": GLib.Variant("s", uuid.uuid4().hex),
            "types": GLib.Variant("u", 1),          # 1 = MONITOR
            "multiple": GLib.Variant("b", False),
            "persist_mode": GLib.Variant("u", 2),   # remember the choice
        }
        self._on_status(
            "screen: requesting sources (click a monitor in the picker)"
        )
        req = self._call(
            proxy, "SelectSources", "(oa{sv})", (self._session_handle, opts)
        )
        if req is None:
            return
        status, _results = self._respond(req, _REQUEST_TIMEOUT)
        if status != 0:
            self._on_status(
                f"screen: SelectSources returned status {status} "
                "(picker canceled/dismissed?)"
            )
            return

        # 3. Start ----------------------------------------------------------
        opts = {"handle_token": GLib.Variant("s", uuid.uuid4().hex)}
        req = self._call(
            proxy, "Start", "(osa{sv})", (self._session_handle, "", opts)
        )
        if req is None:
            return
        status, results = self._respond(req, _START_TIMEOUT)
        if status != 0:
            self._on_status(f"screen: Start failed (status {status})")
            return
        streams = results.get("streams") or ()
        if not streams:
            self._on_status("screen: Start returned no streams")
            return
        _stream_id, props = streams[0]
        # The portal normally reports the PipeWire node via the ``node_id``
        # prop; the hyprland backend instead hands it out as the stream id
        # itself (which matches the pw global id exactly).
        node_id = props.get("node_id")
        node_id = int(node_id) if node_id is not None else int(_stream_id)
        size = props.get("source_size") or props.get("size") or (0, 0)
        self._node_id = node_id
        self._source_size = (int(size[0]), int(size[1])) if size else (0, 0)
        self._on_status(
            f"screen: PipeWire node {self._node_id} ready "
            f"({self._source_size[0]}x{self._source_size[1]})"
        )

        # 4. Capture --------------------------------------------------------
        self._started = True
        self._capture_stop.clear()
        self._capture_thread = threading.Thread(
            target=self._capture_loop, name="screen-capture", daemon=True
        )
        self._capture_thread.start()

    def _proxy(self, object_path, iface):
        return Gio.DBusProxy.new_sync(
            self._conn, Gio.DBusProxyFlags.NONE, None,
            _PORTAL_BUS, object_path, iface, None,
        )

    def _call(self, proxy, method, sig, args):
        """Synchronous portal call; returns the request object path or None."""
        try:
            reply = proxy.call_sync(
                method, GLib.Variant(sig, args),
                Gio.DBusCallFlags.NONE, 15000, None,
            )
        except Exception as exc:
            self._on_status(f"screen: {method} call failed: {exc}")
            return None
        return reply.unpack()[0]

    def _respond(self, req_path, timeout):
        """Wait for Request.Response on the portal request path.

        Returns (status, results), or (status=-1, {}) on timeout/error.
        """
        box = {}

        def on_signal(proxy, sender, signal_name, params, *_args):
            box["data"] = params.unpack()
            return 0

        try:
            req_proxy = self._proxy(req_path, _REQUEST_IFACE)
        except Exception as exc:
            self._on_status(f"screen: cannot watch portal response: {exc}")
            return (-1, {})
        sig_id = req_proxy.connect("g-signal", on_signal, None)

        deadline = time.monotonic() + timeout
        while "data" not in box and time.monotonic() < deadline:
            while self._ctx.pending():
                self._ctx.iteration(False)
            time.sleep(0.002)
        req_proxy.disconnect(sig_id)
        del req_proxy
        if "data" not in box:
            self._on_status(f"screen: no portal reply within {timeout}s")
            return (-1, {})
        return box["data"]

    # ------------------------------------------------------------------
    # GStreamer capture loop (pulls on its own thread)
    # ------------------------------------------------------------------

    def _caps(self):
        """Exact AR-preserving target caps, falling back to a width-only range."""
        w, h = self._source_size
        if w > 0 and h > 0:
            scale = min(1.0, _MAX_W / w, _MAX_H / h)
            tw = max(2, int(w * scale) // 2 * 2)
            th = max(2, int(h * scale) // 2 * 2)
            return f"video/x-raw,framerate={_FPS}/1,width={tw},height={th}"
        return (
            f"video/x-raw,framerate={_FPS}/1,width=[4,{_MAX_W}]"
        )

    def _capture_loop(self):
        try:
            Gst.init(None)
        except Exception as exc:
            self._on_status(f"screen: cannot init gstreamer: {exc}")
            self._started = False
            return
        desc = (
            f"pipewiresrc path={self._node_id} ! videoconvert ! videoscale "
            f"! videorate ! {self._caps()} "
            f"! jpegenc quality={_JPEG_QUALITY} ! appsink name=sink"
        )
        try:
            pipeline = Gst.parse_launch(desc)
        except Exception as exc:
            self._on_status(f"screen: could not build pipeline: {exc}")
            self._started = False
            return
        sink = pipeline.get_by_name("sink")
        sink.set_property("emit-signals", False)
        sink.set_property("max-buffers", 1)
        sink.set_property("drop", True)
        self._pipeline = pipeline

        pipeline.set_state(Gst.State.PLAYING)
        self._on_status(
            f"screen: capturing at {_FPS} fps "
            f"(max {_MAX_W}x{_MAX_H}, jpeg q{_JPEG_QUALITY})"
        )

        while not self._capture_stop.is_set():
            try:
                sample = GstApp.AppSink.try_pull_sample(sink, 100_000_000)
            except Exception:
                time.sleep(0.05)
                continue
            if sample is None:
                continue
            buffer = sample.get_buffer()
            if buffer is None:
                continue
            with self._frame_lock:
                self._latest_jpeg = buffer.extract_dup(0, buffer.get_size())

        pipeline.set_state(Gst.State.NULL)
        self._on_status("screen: capture stopped")