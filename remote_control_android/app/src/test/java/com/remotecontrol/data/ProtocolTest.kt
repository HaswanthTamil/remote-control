package com.remotecontrol.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {

    private val deviceId = "a".repeat(64)
    private val publicKey = "b".repeat(64)

    @Test
    fun `register carries identity and only pairs when a token is supplied`() {
        val withToken = Protocol.register(deviceId, publicKey, "secret")
        assertEquals("register", withToken.getString("type"))
        assertEquals("phone", withToken.getString("device"))
        assertEquals(deviceId, withToken.getString("device_id"))
        assertEquals(publicKey, withToken.getString("public_key"))
        assertEquals("secret", withToken.getString("pair_token"))

        val withoutToken = Protocol.register(deviceId, publicKey, null)
        assertFalse(withoutToken.has("pair_token"))
        assertFalse(Protocol.register(deviceId, publicKey, "").has("pair_token"))
    }

    @Test
    fun `auth signs the challenge payload`() {
        val auth = Protocol.auth(deviceId, "cc".repeat(64))
        assertEquals("auth", auth.getString("type"))
        assertEquals(deviceId, auth.getString("device_id"))
        assertEquals("cc".repeat(64), auth.getString("signature"))
    }

    @Test
    fun `input messages match the relay's routing table`() {
        assertEquals("command", Protocol.command("id-1", "ls -la").getString("type"))
        assertEquals("ls -la", Protocol.command("id-1", "ls -la").getString("command"))

        val signal = Protocol.signal("id-2", "SIGINT")
        assertEquals("signal", signal.getString("type"))
        assertEquals("SIGINT", signal.getString("signal"))

        assertEquals("start", Protocol.screenRequest("start").getString("action"))
        assertEquals("stop", Protocol.screenRequest("stop").getString("action"))

        val move = Protocol.pointerMove(0.25f, 0.75f)
        assertEquals("pointer.move", move.getString("type"))
        assertEquals(0.25, move.getDouble("x"), 1e-9)
        assertEquals(0.75, move.getDouble("y"), 1e-9)

        val button = Protocol.pointerButton("left", true)
        assertEquals("pointer.button", button.getString("type"))
        assertEquals("left", button.getString("button"))
        assertTrue(button.getBoolean("down"))

        val scroll = Protocol.pointerScroll(0f, -3.5f)
        assertEquals("pointer.scroll", scroll.getString("type"))
        assertEquals(-3.5, scroll.getDouble("dy"), 1e-9)

        val key = Protocol.keyboardKey("KEY_A", false)
        assertEquals("keyboard.key", key.getString("type"))
        assertEquals("KEY_A", key.getString("key"))
        assertFalse(key.getBoolean("down"))
    }

    @Test
    fun `incoming messages are parsed into typed events`() {
        assertTrue(Protocol.parse("""{"type":"challenge","nonce":"abc"}""") is Incoming.Challenge)
        assertTrue(Protocol.parse("""{"type":"registered","device":"phone"}""") is Incoming.Registered)
        assertTrue(Protocol.parse("""{"type":"ack","id":"7"}""") is Incoming.Ack)
        assertTrue(Protocol.parse("""{"type":"output","data":"hi"}""") is Incoming.Output)
        assertTrue(Protocol.parse("""{"type":"stderr","data":"bad"}""") is Incoming.Stderr)
        assertTrue(Protocol.parse("""{"type":"exit","code":2}""") is Incoming.Exit)
        assertTrue(Protocol.parse("""{"type":"error","message":"Device not paired"}""") is Incoming.Error)

        val status = Protocol.parse(
            """{"type":"screen.status","active":true,"width":1920,"height":1080,"message":"live"}""",
        )
        assertTrue(status is Incoming.ScreenStatus)
        status as Incoming.ScreenStatus
        assertTrue(status.active)
        assertEquals(1920, status.width)
        assertEquals(1080, status.height)

        val exit = Protocol.parse("""{"type":"exit","code":2}""") as Incoming.Exit
        assertEquals(2, exit.code)

        val unknownExit = Protocol.parse("""{"type":"exit"}""") as Incoming.Exit
        assertNull(unknownExit.code)
    }

    @Test
    fun `garbage is ignored`() {
        assertNull(Protocol.parse("not json"))
        assertNull(Protocol.parse("""{"type":"challenge"}"""))
        assertTrue(Protocol.parse("""{"type":"weird"}""") is Incoming.Unknown)
    }

    @Test
    fun `messages serialise to the shape the relay expects`() {
        val json = JSONObject(Protocol.pointerMove(0.5f, 0.5f).toString())
        assertEquals(
            setOf("type", "x", "y"),
            json.keys().asSequence().toSet(),
        )
    }
}