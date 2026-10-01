package com.remotecontrol.data

/**
 * Key name mapping for `keyboard.key`, the Kotlin twin of the tables in
 * `remote_control_phone/js/remote.js` (which mirror `evdev_keycodes.py` on the
 * laptop agent). Modifiers are sent as explicit down/up events so real
 * combinations (Ctrl+C, Alt+Tab, Super+4) work on the Wayland virtual keyboard.
 */
object KeyMap {

    const val SHIFT = "LEFTSHIFT"
    const val CTRL = "LEFTCTRL"
    const val ALT = "LEFTALT"
    const val SUPER = "LEFTMETA"

    private val MOD_ALIASES = mapOf(
        "CTRL" to CTRL,
        "CONTROL" to CTRL,
        "ALT" to ALT,
        "SHIFT" to SHIFT,
        "SUPER" to SUPER,
        "META" to SUPER,
        "WIN" to SUPER,
    )

    /** Every non-modifier key the combo bar accepts as a bare token. */
    val KEY_NAMES: Set<String> = setOf(
        "ESC", "TAB", "BACKSPACE", "ENTER", "SPACE", "CAPSLOCK",
        "HOME", "END", "PAGEUP", "PAGEDOWN", "DELETE", "INSERT",
        "UP", "DOWN", "LEFT", "RIGHT",
        "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12",
        "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
        "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z",
        "1", "2", "3", "4", "5", "6", "7", "8", "9", "0",
        "MINUS", "EQUAL", "LEFTBRACE", "RIGHTBRACE", "BACKSLASH",
        "SEMICOLON", "APOSTROPHE", "GRAVE", "COMMA", "DOT", "SLASH",
    )

    val MODIFIER_NAMES: Set<String> = setOf(CTRL, ALT, SHIFT, SUPER)

    private val SHIFTED = mapOf(
        '~' to "GRAVE", '!' to "1", '@' to "2", '#' to "3", '$' to "4", '%' to "5",
        '^' to "6", '&' to "7", '*' to "8", '(' to "9", ')' to "0",
        '_' to "MINUS", '+' to "EQUAL", '{' to "LEFTBRACE", '}' to "RIGHTBRACE",
        '|' to "BACKSLASH", ':' to "SEMICOLON", '"' to "APOSTROPHE",
        '<' to "COMMA", '>' to "DOT", '?' to "SLASH",
    )

    private val PLAIN = mapOf(
        '-' to "MINUS", '=' to "EQUAL", '`' to "GRAVE", '[' to "LEFTBRACE",
        ']' to "RIGHTBRACE", '\\' to "BACKSLASH", ';' to "SEMICOLON",
        '\'' to "APOSTROPHE", ',' to "COMMA", '.' to "DOT", '/' to "SLASH",
        ' ' to "SPACE", '\t' to "TAB", '\n' to "ENTER", '\r' to "ENTER",
    )

    /** One step of a literal-text stream: a key plus whether shift must be held. */
    data class TypedKey(val key: String, val shift: Boolean)

    data class Combo(val modifiers: List<String>, val key: String)

    sealed interface Plan {
        data class Tap(val key: String) : Plan
        data class Typing(val keys: List<TypedKey>) : Plan
        data class Combination(val combo: Combo) : Plan
    }

    /** Resolves friendly aliases and strips the `KEY_` prefix. */
    fun normalize(token: String): String {
        val upper = token.trim().uppercase()
        return MOD_ALIASES[upper] ?: upper.removePrefix("KEY_")
    }

    fun isKnownKey(name: String): Boolean = name in KEY_NAMES || name in MODIFIER_NAMES

    /**
     * Turns the combo bar's text into key events, matching `remote.js`:
     * anything containing `+` is a combination ("CTRL+ALT+T"), a single known
     * key name is a tap ("ESC"), anything else is typed character by character.
     */
    fun plan(rawInput: String): Plan? {
        val raw = rawInput.trim()
        if (raw.isEmpty()) return null

        if (raw.contains('+')) {
            val tokens = raw.split('+').map { it.trim() }.filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return null
            val finalKey = normalize(tokens.last())
            val modifiers = tokens.dropLast(1).map { normalize(it) }
            return Plan.Combination(Combo(modifiers, finalKey))
        }

        val single = normalize(raw)
        if (isKnownKey(single)) return Plan.Tap(single)

        val keys = raw.mapNotNull { typeChar(it) }
        return if (keys.isEmpty()) null else Plan.Typing(keys)
    }

    /** Maps a single character to a key event, or null when unmappable. */
    fun typeChar(char: Char): TypedKey? = when {
        char in 'a'..'z' -> TypedKey("KEY_" + char.uppercaseChar(), false)
        char in 'A'..'Z' -> TypedKey("KEY_$char", true)
        char in '0'..'9' -> TypedKey("KEY_$char", false)
        else -> {
            val shifted = SHIFTED[char]
            when {
                shifted != null -> TypedKey("KEY_$shifted", true)
                else -> PLAIN[char]?.let { TypedKey("KEY_$it", false) }
            }
        }
    }
}