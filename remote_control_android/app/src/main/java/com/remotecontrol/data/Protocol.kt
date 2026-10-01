package com.remotecontrol.data

import org.json.JSONObject

/**
 * The relay wire protocol, byte-for-byte the same as the web client's.
 *
 * Phone -> relay -> laptop:
 *   register { device, device_id, public_key, pair_token? }
 *   auth     { device_id, signature }                  signature = sign(device_id:nonce)
 *   command  { id, command }        -> ack | error
 *   signal   { id, signal }                          e.g. SIGINT
 *   screen.request { action: start|stop }
 *   pointer.move   { x, y }                           normalized 0.0-1.0
 *   pointer.button { button, down }
 *   pointer.scroll { dx, dy }
 *   keyboard.key   { key, down }                      evdev name, e.g. KEY_A
 *
 * Laptop -> relay -> phone:
 *   challenge { nonce }, registered { device }, output/stderr { data },
 *   exit { code }, ack { id }, error { message }, screen.status { active, width, height, message }
 *   plus raw binary JPEG frames (screen fast-lane).
 */
object Protocol {

    const val DEVICE = "phone"

    const val CLOSE_SUPERSEDED = 4001

    fun register(deviceId: String, publicKeyHex: String, pairToken: String?): JSONObject =
        JSONObject()
            .put("type", "register")
            .put("device", DEVICE)
            .put("device_id", deviceId)
            .put("public_key", publicKeyHex)
            .apply { if (!pairToken.isNullOrEmpty()) put("pair_token", pairToken) }

    fun auth(deviceId: String, signatureHex: String): JSONObject =
        JSONObject()
            .put("type", "auth")
            .put("device_id", deviceId)
            .put("signature", signatureHex)

    fun command(id: String, command: String): JSONObject =
        JSONObject()
            .put("type", "command")
            .put("id", id)
            .put("command", command)

    fun signal(id: String, signal: String): JSONObject =
        JSONObject()
            .put("type", "signal")
            .put("id", id)
            .put("signal", signal)

    fun screenRequest(action: String): JSONObject =
        JSONObject()
            .put("type", "screen.request")
            .put("action", action)

    fun pointerMove(x: Float, y: Float): JSONObject =
        JSONObject()
            .put("type", "pointer.move")
            .put("x", x.toDouble())
            .put("y", y.toDouble())

    fun pointerButton(button: String, down: Boolean): JSONObject =
        JSONObject()
            .put("type", "pointer.button")
            .put("button", button)
            .put("down", down)

    fun pointerScroll(dx: Float, dy: Float): JSONObject =
        JSONObject()
            .put("type", "pointer.scroll")
            .put("dx", dx.toDouble())
            .put("dy", dy.toDouble())

    fun keyboardKey(key: String, down: Boolean): JSONObject =
        JSONObject()
            .put("type", "keyboard.key")
            .put("key", key)
            .put("down", down)

    fun parse(text: String): Incoming? {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        return when (json.optString("type")) {
            "challenge" -> json.optString("nonce").takeIf { it.isNotEmpty() }?.let { Incoming.Challenge(it) }
            "registered" -> Incoming.Registered(json.optString("device"))
            "ack" -> Incoming.Ack(json.optString("id"))
            "output" -> Incoming.Output(json.optString("data"))
            "stderr" -> Incoming.Stderr(json.optString("data"))
            "exit" -> Incoming.Exit(if (json.has("code")) json.optInt("code") else null)
            "error" -> Incoming.Error(json.optString("message"), json.optString("id"))
            "screen.status" -> Incoming.ScreenStatus(
                active = json.optBoolean("active"),
                width = json.optInt("width"),
                height = json.optInt("height"),
                message = json.optString("message"),
            )
            else -> Incoming.Unknown(json.optString("type"))
        }
    }
}

sealed interface Incoming {
    data class Challenge(val nonce: String) : Incoming
    data class Registered(val device: String) : Incoming
    data class Ack(val id: String) : Incoming
    data class Output(val data: String) : Incoming
    data class Stderr(val data: String) : Incoming
    data class Exit(val code: Int?) : Incoming
    data class Error(val message: String, val id: String) : Incoming
    data class ScreenStatus(
        val active: Boolean,
        val width: Int,
        val height: Int,
        val message: String,
    ) : Incoming

    data class Unknown(val type: String) : Incoming
}