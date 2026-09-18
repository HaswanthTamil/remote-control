"""Linux evdev keycode table (linux/input-event-codes.h).

Used by the Wayland virtual-keyboard injection: the zwp_virtual_keyboard_v1
protocol transports raw evdev keycodes. This module maps the PRD's key names
("KEY_A", modifier names) and printable characters to those keycodes, and
records which characters need SHIFT.
"""

# Core keycodes (subset of input-event-codes.h, US layout).
KEY = {
    "RESERVED": 0, "ESC": 1,
    "1": 2, "2": 3, "3": 4, "4": 5, "5": 6, "6": 7, "7": 8, "8": 9, "9": 10, "0": 11,
    "MINUS": 12, "EQUAL": 13, "BACKSPACE": 14, "TAB": 15,
    "Q": 16, "W": 17, "E": 18, "R": 19, "T": 20, "Y": 21, "U": 22, "I": 23, "O": 24, "P": 25,
    "LEFTBRACE": 26, "RIGHTBRACE": 27, "ENTER": 28, "LEFTCTRL": 29,
    "A": 30, "S": 31, "D": 32, "F": 33, "G": 34, "H": 35, "J": 36, "K": 37, "L": 38,
    "SEMICOLON": 39, "APOSTROPHE": 40, "GRAVE": 41, "LEFTSHIFT": 42, "BACKSLASH": 43,
    "Z": 44, "X": 45, "C": 46, "V": 47, "B": 48, "N": 49, "M": 50,
    "COMMA": 51, "DOT": 52, "SLASH": 53, "RIGHTSHIFT": 54, "LEFTALT": 56, "SPACE": 57,
    "CAPSLOCK": 58, "F1": 59, "F2": 60, "F3": 61, "F4": 62, "F5": 63, "F6": 64,
    "F7": 65, "F8": 66, "F9": 67, "F10": 68, "NUMLOCK": 69, "SCROLLLOCK": 70,
    "KP7": 71, "KP8": 72, "KP9": 73, "KPMINUS": 74, "KP4": 75, "KP5": 76, "KP6": 77,
    "KPPLUS": 78, "KP1": 79, "KP2": 80, "KP3": 81, "KP0": 82, "KPDOT": 83,
    "ZENKAKUHANKAKU": 85, "102ND": 86, "F11": 87, "F12": 88, "RO": 89,
    "KPENTER": 96, "RIGHTCTRL": 97, "KPSLASH": 98, "SYSRQ": 99, "KPPLUSMINUS": 101,
    "HOME": 102, "UP": 103, "PAGEUP": 104, "LEFT": 105, "RIGHT": 106, "END": 107,
    "DOWN": 108, "PAGEDOWN": 109, "INSERT": 110, "DELETE": 111,
    "MUTE": 113, "VOLUMEDOWN": 114, "VOLUMEUP": 115, "POWER": 116,
    "PAUSE": 119, "KPEQUAL": 117, "KPCOMMA": 121, "PAST": 124,
    "LEFTMETA": 125, "RIGHTMETA": 126, "COMPOSE": 127,
}

# Friendly names for modifiers / special keys that arrive from the phone.
ALIASES = {
    "CTRL": "LEFTCTRL",
    "CONTROL": "LEFTCTRL",
    "ALT": "LEFTALT",
    "SHIFT": "LEFTSHIFT",
    "SUPER": "LEFTMETA",
    "META": "LEFTMETA",
    "WIN": "LEFTMETA",
    "BSPACE": "BACKSPACE",
    "BKSP": "BACKSPACE",
    "DEL": "DELETE",
    "RETURN": "ENTER",
    "LR": "LEFT",       # typed by accident -> no-op safety
    "SPC": "SPACE",
}

# Printable ASCII -> (keycode, needs_shift)
_CHAR_MAP = {}
_PUNCT_PLAIN = {"-": "MINUS", "=": "EQUAL"}
for _c in "1234567890":
    _CHAR_MAP[_c] = (KEY[_c], False)
for _c, _n in _PUNCT_PLAIN.items():
    _CHAR_MAP[_c] = (KEY[_n], False)
for _c in "abcdefghijklmnopqrstuvwxyz":
    _CHAR_MAP[_c] = (KEY[_c.upper()], False)
for _c in "ABCDEFGHIJKLMNOPQRSTUVWXYZ":
    _CHAR_MAP[_c] = (KEY[_c], True)
for _c in "`[]\\;',./":
    _CHAR_MAP[_c] = (KEY[{
        "`": "GRAVE", "[": "LEFTBRACE", "]": "RIGHTBRACE", "\\": "BACKSLASH",
        ";": "SEMICOLON", "'": "APOSTROPHE", ",": "COMMA", ".": "DOT",
        "/": "SLASH",
    }[_c]], False)
for _c, _n in {
    "~": "GRAVE", "!": "1", "@": "2", "#": "3", "$": "4", "%": "5",
    "^": "6", "&": "7", "*": "8", "(": "9", ")": "0",
    "_": "MINUS", "+": "EQUAL", "{": "LEFTBRACE", "}": "RIGHTBRACE",
    "|": "BACKSLASH", ":": "SEMICOLON", '"': "APOSTROPHE",
    "<": "COMMA", ">": "DOT", "?": "SLASH",
}.items():
    _CHAR_MAP[_c] = (KEY[_n], True)
_CHAR_MAP[" "] = (KEY["SPACE"], False)
_CHAR_MAP["\t"] = (KEY["TAB"], False)
_CHAR_MAP["\n"] = (KEY["ENTER"], False)
_CHAR_MAP["\r"] = (KEY["ENTER"], False)

# Name lookups: "KEY_A", "A", "LEFTCTRL", "CTRL", ...
_KEYCODE_BY_NAME = {}
for _n, _code in KEY.items():
    _KEYCODE_BY_NAME[_n] = _code
    _KEYCODE_BY_NAME["KEY_" + _n] = _code
for _alias, _canon in ALIASES.items():
    _KEYCODE_BY_NAME[_alias] = KEY[_canon]
    _KEYCODE_BY_NAME["KEY_" + _alias] = KEY[_canon]


def keycode_for_name(name):
    """Resolve a key name ("KEY_A", "CTRL", "SUPER", ...) to an evdev keycode.

    Returns None when the name is unknown so callers can skip gracefully.
    """
    if not isinstance(name, str):
        return None
    return _KEYCODE_BY_NAME.get(name.strip().upper())


def keycode_for_char(char):
    """Resolve a single printable character to (evdev_keycode, needs_shift).

    Returns None when the character cannot be typed as a key event.
    """
    if not isinstance(char, str) or len(char) != 1:
        return None
    return _CHAR_MAP.get(char)