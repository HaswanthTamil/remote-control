#!/usr/bin/env python3
"""Non-interactive share picker for xdg-desktop-portal-hyprland (ScreenCast).

The portal has no "capture output N" option on Hyprland: every session either
opens the share picker or replays a restore token granted earlier. This script
replaces the picker with a single stdout line, so the agent can start mirroring
without any dialog:

    [SELECTION]r/screen:<output>

``r`` is the "allow token" flag, which is what makes the portal hand us a
restore token. With that token cached, even this script stops being called.

Configure it once in ``~/.config/hypr/xdph.conf`` (note Hyprlang wants the
flat ``category:key`` form, a ``[screencopy]`` INI section is ignored):

    screencopy:custom_picker_binary = /path/to/remote_control_laptop/hypr_auto_picker.py

Run with ``--install`` to add that line (idempotent), or ``--check`` to see
whether it is already active. Requires nothing outside the standard library.

Output selection: the first output the compositor reports, unless
``RC_SCREENCAST_OUTPUT`` names one. With a single display this is always the
only choice, which is the point.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys

CONFIG_PATH = os.path.join(
    os.environ.get("XDG_CONFIG_HOME") or os.path.expanduser("~/.config"),
    "hypr",
    "xdph.conf",
)
SELF = os.path.abspath(__file__)
# Portal stdout protocol, e.g. "[SELECTION]r/screen:eDP-1".
SELECTION = "[SELECTION]r/screen:{name}\n"


def _outputs_from_portal() -> list[str]:
    """Parse XDPH_OUTPUT_SHARING_LIST (``len:name:x:y:w:h;`` entries)."""
    names: list[str] = []
    for entry in os.environ.get("XDPH_OUTPUT_SHARING_LIST", "").split(";"):
        parts = entry.split(":")
        if len(parts) >= 2:
            names.append(parts[1])
    return names


def _outputs_from_hyprctl() -> list[str]:
    """Fallback when the portal did not export its list (e.g. manual runs)."""
    try:
        result = subprocess.run(
            ["hyprctl", "monitors", "-j"],
            capture_output=True, text=True, timeout=3,
        )
        monitors = [
            m for m in json.loads(result.stdout) if not m.get("disabled")
        ]
    except Exception:
        return []
    monitors.sort(key=lambda m: m.get("id", 0))
    return [m["name"] for m in monitors if m.get("name")]


def pick_output() -> str | None:
    names = _outputs_from_portal() or _outputs_from_hyprctl()
    if not names:
        return None
    wanted = os.environ.get("RC_SCREENCAST_OUTPUT", "").strip()
    if wanted and wanted in names:
        return wanted
    return names[0]


CONFIG_KEY = "screencopy:custom_picker_binary"
# Hyprlang (the config library behind xdg-desktop-portal-hyprland) reads these
# portal values as flat "category:key" entries, not as an INI section. Verified
# against xdg-desktop-portal-hyprland 1.3.12 on Fedora: the [screencopy] section
# form is silently ignored, the flat form works.


def _config_value(text: str) -> str | None:
    """Current value of our key, whether flat or in a section."""
    for line in text.splitlines():
        stripped = line.strip()
        for candidate in (CONFIG_KEY, "custom_picker_binary"):
            if stripped.startswith(candidate) and "=" in stripped:
                return stripped.split("=", 1)[1].strip().strip('"')
    return None


def _read_config() -> str:
    try:
        with open(CONFIG_PATH, "r", encoding="utf-8") as handle:
            return handle.read()
    except OSError:
        return ""


def install() -> int:
    text = _read_config()
    existing = _config_value(text)
    if existing:
        print(f"{CONFIG_PATH} already sets {CONFIG_KEY}; leaving it alone.")
        print(f"  current: {existing}")
        if existing != SELF:
            print("  (remove that line first if you want this picker instead)")
        return 0

    lines = text.splitlines() if text else []
    if lines and lines[-1].strip():
        lines.append("")
    lines.append(f"{CONFIG_KEY} = {SELF}")

    os.makedirs(os.path.dirname(CONFIG_PATH), exist_ok=True)
    with open(CONFIG_PATH, "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines) + "\n")
    print(f"wrote {CONFIG_PATH}")
    print(f"  {CONFIG_KEY} = {SELF}")
    print()
    print("Restart the portal backend for it to take effect:")
    print("  systemctl --user restart xdg-desktop-portal-hyprland.service")
    return 0


def picker_status() -> tuple[str, str | None]:
    """(state, configured path) for the portal's custom_picker_binary.

    state is one of:
      active        - this script is the configured picker
      not_configured- the portal will open Hyprland's own display picker
      missing       - a picker is configured but the file is gone
      other         - a different picker is configured
    """
    configured = _config_value(_read_config())
    if not configured:
        return "not_configured", None
    if configured == SELF:
        return "active", configured
    if not os.access(configured, os.X_OK):
        return "missing", configured
    return "other", configured


def check(quiet: bool = False) -> int:
    """0 when this picker is the configured, working one."""
    def say(text: str) -> None:
        if not quiet:
            print(text)

    state, path = picker_status()
    if state == "active":
        say(f"active: {path}")
        return 0
    if state == "missing":
        say(f"configured but not executable: {path}")
        say(f"  re-run: {SELF} --install")
        return 1
    if state == "other":
        say(f"another picker is configured: {path}")
        return 1
    say(f"not configured - run: {SELF} --install")
    return 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--install", action="store_true",
                        help="add custom_picker_binary to ~/.config/hypr/xdph.conf")
    parser.add_argument("--check", action="store_true",
                        help="report whether this picker is the configured one")
    args = parser.parse_args()

    if args.install:
        return install()
    if args.check:
        return check()

    # Portal mode: the stdout line is the whole contract.
    output = pick_output()
    if not output:
        print("no outputs reported by the compositor", file=sys.stderr)
        return 1
    sys.stdout.write(SELECTION.format(name=output))
    sys.stdout.flush()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
