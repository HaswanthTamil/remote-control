# Task: Add Wayland Display Streaming + Mouse/Keyboard Input

Upgrade the existing remote-control system so the phone can:

1. See the laptop's screen.
2. Send mouse/touch input to the laptop.
3. Send keyboard input to the laptop.

Do NOT redesign or rewrite the existing remote-control system. The existing terminal/PTY functionality already works and must continue working unchanged.

The laptop runs Fedora Linux with Hyprland/Wayland.

The existing system has:

```text
PHONE
  │
  │ WebSocket
  ▼
RELAY SERVER
  │
  │ WebSocket
  ▼
LAPTOP AGENT
```

The laptop agent already maintains the connection to the relay and can execute commands through a PTY.

The new work is ONLY the display/input layer.

---

# 1. Required I/O architecture

The laptop agent should have three new logical capabilities:

```text
                 LAPTOP AGENT
                      │
          ┌───────────┼────────────┐
          │           │            │
          ▼           ▼            ▼
       SCREEN       MOUSE       KEYBOARD
       CAPTURE      INPUT        INPUT
          │           ▲            ▲
          │           │            │
          ▼           └────┬───────┘
       ENCODE              │
          │                │
          ▼                │
       WEBSOCKET ◄─────────┘
          │
          ▼
        RELAY
          │
          ▼
        PHONE
```

More precisely:

```text
Laptop screen
    ↓
Wayland ScreenCast portal
    ↓
PipeWire
    ↓
video frame
    ↓
encode/compress
    ↓
WebSocket
    ↓
relay
    ↓
phone
```

And input:

```text
Phone touch
    ↓
phone UI coordinates
    ↓
WebSocket
    ↓
relay
    ↓
laptop agent
    ↓
Wayland-native input mechanism
    ↓
Hyprland
    ↓
desktop
```

Keyboard:

```text
Phone keyboard
    ↓
key event
    ↓
WebSocket
    ↓
relay
    ↓
laptop agent
    ↓
Wayland-native virtual keyboard/input
    ↓
Hyprland
    ↓
desktop/application
```

---

# 2. Screen capture

The laptop is Wayland/Hyprland.

Do NOT use:

* xdotool
* Xlib screenshot APIs
* X11 screen capture
* `gnome-screenshot`
* desktop screenshots through shell commands

Use the Wayland-native screen capture stack:

```text
XDG Desktop Portal
        ↓
ScreenCast portal
        ↓
PipeWire
```

The relevant Hyprland portal implementation is:

```text
xdg-desktop-portal-hyprland
```

Use the existing user's graphical session and portal permissions.

The implementation must not attempt to bypass Wayland's security model.

---

# 3. First screen-capture requirement

Implement the smallest possible working capture pipeline.

The laptop agent must be able to:

```text
request screen capture
        ↓
obtain PipeWire stream
        ↓
receive a frame
        ↓
convert it into a browser-compatible image representation
        ↓
send it to the phone
```

Initially, do NOT optimize for high FPS.

The first successful implementation should be capable of sending individual frames.

For example:

```text
screen.request
        ↓
agent captures frame
        ↓
agent sends frame
        ↓
phone displays frame
```

The exact image format can initially be JPEG or PNG.

Do not send raw RGB frames over the network.

---

# 4. Screen protocol

Add a clear WebSocket message type for screen frames.

For example:

```json
{
  "type": "screen.frame",
  "data": "<encoded frame>"
}
```

Use the project's existing WebSocket message conventions if they already have an established format.

Do not create a second WebSocket connection unless the existing architecture genuinely requires it.

The relay should simply forward the screen message.

The relay does not need to understand image contents.

---

# 5. Phone display

The phone UI must display the received laptop frame.

Maintain the laptop's aspect ratio.

Example:

```text
Laptop:
1920 × 1080

Phone:
1080 × 2400

Phone UI:

┌──────────────────────┐
│                      │
│   ┌──────────────┐   │
│   │              │   │
│   │   LAPTOP     │   │
│   │   SCREEN     │   │
│   │              │   │
│   └──────────────┘   │
│                      │
└──────────────────────┘
```

Do not stretch the laptop screen to fill the phone arbitrarily.

The displayed screen must preserve its aspect ratio.

---

# 6. Coordinate system

Mouse/touch coordinates must NOT depend directly on the phone's physical resolution.

The phone should send normalized coordinates.

Use:

```text
x = 0.0 → 1.0
y = 0.0 → 1.0
```

Example:

```json
{
  "type": "pointer.move",
  "x": 0.421,
  "y": 0.672
}
```

The laptop agent converts normalized coordinates to actual screen coordinates.

If the laptop screen is:

```text
1920 × 1080
```

then:

```text
actual_x = 0.421 × 1920
actual_y = 0.672 × 1080
```

However, account for letterboxing/padding introduced by the phone UI.

Touch coordinates must first be mapped from:

```text
phone viewport
```

to:

```text
actual displayed laptop-screen rectangle
```

and only then normalized.

Do not accidentally map touches occurring outside the displayed screen area to laptop coordinates.

---

# 7. Mouse/pointer events

Implement these events:

```text
pointer.move
pointer.button
pointer.scroll
```

Pointer movement:

```json
{
  "type": "pointer.move",
  "x": 0.421,
  "y": 0.672
}
```

Mouse button:

```json
{
  "type": "pointer.button",
  "button": "left",
  "action": "down"
}
```

and:

```json
{
  "type": "pointer.button",
  "button": "left",
  "action": "up"
}
```

Support:

```text
left
right
middle
```

Scrolling:

```json
{
  "type": "pointer.scroll",
  "dx": 0,
  "dy": -5
}
```

Use a Wayland-native mechanism for injection.

Do NOT use:

```text
xdotool
```

because the target environment is Hyprland/Wayland.

Investigate and use the appropriate mechanism supported by the current system, prioritizing:

```text
libei / XDG RemoteDesktop portal
```

and, where appropriate for Hyprland/wlroots:

```text
virtual pointer protocol
```

Do not silently fall back to X11.

---

# 8. Touch behavior

The phone screen should behave like a trackpad/remote mouse initially.

Implement:

```text
finger touches screen
        ↓
pointer movement
        ↓
finger released
        ↓
left mouse click
```

For example:

```text
touchstart
    ↓
begin pointer interaction

touchmove
    ↓
pointer.move

touchend
    ↓
pointer.button down
    ↓
pointer.button up
```

Keep the implementation simple.

Do not implement multitouch gestures initially.

A later double-tap or right-click gesture is not required for this task.

---

# 9. Keyboard input

The phone must be able to send actual keyboard events, not merely execute shell commands.

Implement:

```text
keyboard.key
```

with explicit press/release semantics.

Example:

```json
{
  "type": "keyboard.key",
  "key": "KEY_A",
  "action": "down"
}
```

followed by:

```json
{
  "type": "keyboard.key",
  "key": "KEY_A",
  "action": "up"
}
```

Support modifier keys independently:

```text
CTRL
ALT
SHIFT
SUPER
```

This is required so combinations work.

Examples:

```text
Ctrl+C
Ctrl+V
Ctrl+Shift+C
Alt+Tab
Super
Shift+A
```

Do NOT implement keyboard input as:

```text
"send this text string"
```

because that cannot correctly represent modifier combinations or actual key events.

Use a Wayland-native input injection mechanism.

Prioritize:

```text
libei / XDG RemoteDesktop
```

or the appropriate Hyprland/wlroots virtual keyboard mechanism.

Do not use xdotool.

---

# 10. Ctrl+C

The existing terminal already uses a PTY.

Do not break it.

The display/input layer should allow the phone's keyboard to generate Ctrl+C as an actual keyboard combination.

The system should therefore be able to produce:

```text
Ctrl
+
C
```

as actual key events.

If the terminal needs the traditional terminal interrupt character, let the PTY/terminal subsystem handle that naturally.

Do not implement a special global `pkill` or process-name lookup for Ctrl+C.

---

# 11. Screen refresh

Once individual-frame capture works, implement continuous updates.

Start conservatively.

Target:

```text
5 FPS
```

Do not immediately target 60 FPS.

The pipeline should be:

```text
PipeWire frame
    ↓
encode/compress
    ↓
WebSocket
    ↓
phone
    ↓
display
```

The capture loop must not block the WebSocket/control loop.

Input latency is more important than maximizing screen FPS.

Mouse and keyboard messages should continue flowing even if screen frames are being generated.

---

# 12. Backpressure

Do not allow screen frames to build up indefinitely.

Bad:

```text
frame 1
frame 2
frame 3
frame 4
...
frame 500
```

If the network cannot keep up, old screen frames should be dropped.

For a live remote desktop:

```text
newest frame > old frame
```

Latency is more important than delivering every frame.

The screen pipeline should therefore have a bounded queue, ideally with at most one or a small number of pending frames.

Never allow unbounded memory growth.

---

# 13. Control-plane priority

The following messages are latency-sensitive:

```text
pointer.move
pointer.button
keyboard.key
```

Screen frames are lower priority.

Do not let a large screen-frame payload block keyboard/mouse messages.

If the current WebSocket implementation serializes all messages through one queue, modify it so control events are not starved by screen-frame traffic.

Preserve the existing terminal functionality.

---

# 14. Existing system compatibility

Before changing anything:

1. Inspect the existing codebase.
2. Understand how the current WebSocket connection works.
3. Understand the current relay message format.
4. Understand how the existing PTY is managed.
5. Reuse existing connection/message abstractions.

Do NOT rewrite working components simply to fit a preferred architecture.

The existing behavior:

```text
phone
  ↓
relay
  ↓
agent
  ↓
PTY
  ↓
command
  ↓
terminal output
  ↓
phone
```

must continue to work.

The new functionality is additive.

---

# 15. Error handling

Handle these cases explicitly:

```text
portal unavailable
PipeWire unavailable
screen permission denied
screen stream disconnected
input backend unavailable
relay disconnected
phone disconnected
invalid coordinates
malformed input messages
```

Do not crash the entire agent because screen capture failed.

For example:

```text
screen capture unavailable
```

should not prevent:

```text
terminal
```

from continuing to work.

Likewise, input injection failure should not kill the screen capture or terminal subsystem.

Return useful error messages to the phone where appropriate.

---

# 16. Required end-to-end behavior

When the user opens the remote-control UI:

```text
Phone
  ↓
connect WebSocket
  ↓
authenticate/identify using the existing mechanism
  ↓
request screen
  ↓
Laptop agent starts ScreenCast
  ↓
PipeWire provides frames
  ↓
Agent sends frames
  ↓
Phone displays laptop screen
```

Then:

```text
Phone touch
  ↓
normalized coordinates
  ↓
pointer.move / pointer.button
  ↓
relay
  ↓
agent
  ↓
Wayland input
  ↓
Hyprland
```

And:

```text
Phone keyboard
  ↓
keyboard.key
  ↓
relay
  ↓
agent
  ↓
Wayland input
  ↓
Hyprland
```

The user should ultimately be able to:

```text
see laptop screen
        +
move mouse
        +
click
        +
scroll
        +
type
        +
use Ctrl/Alt/Shift/Super combinations
```

from the phone.

---

# 17. Definition of done

This task is complete when all of the following work on the actual Fedora + Hyprland machine:

### Display

* [ ] Wayland-native screen capture works.
* [ ] Screen capture uses XDG ScreenCast + PipeWire.
* [ ] Phone receives screen frames.
* [ ] Phone preserves screen aspect ratio.
* [ ] Screen updates continuously.
* [ ] Screen frames use bounded buffering.
* [ ] Slow network does not cause unlimited memory growth.

### Pointer

* [ ] Touch moves the laptop pointer.
* [ ] Touch release produces a left click.
* [ ] Left click works.
* [ ] Right click works.
* [ ] Middle click works.
* [ ] Scroll works.
* [ ] Coordinate mapping remains correct across different phone/laptop resolutions.

### Keyboard

* [ ] Normal keys work.
* [ ] Key down/up are represented separately.
* [ ] Ctrl works.
* [ ] Alt works.
* [ ] Shift works.
* [ ] Super works.
* [ ] Keyboard combinations work.
* [ ] Ctrl+C works inside the existing PTY terminal.

### Stability

* [ ] Existing terminal functionality still works.
* [ ] Screen capture failure does not kill the agent.
* [ ] Input failure does not kill the agent.
* [ ] WebSocket disconnect/reconnect does not crash the agent.
* [ ] No X11/xdotool dependency is introduced.

Start by inspecting the existing project and determining the current language/runtime and WebSocket protocol. Then implement the smallest working Wayland screen-capture path first, followed by pointer input and keyboard input. Do not modify unrelated functionality.
