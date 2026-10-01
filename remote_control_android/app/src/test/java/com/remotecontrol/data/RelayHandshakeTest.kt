package com.remotecontrol.data

import com.remotecontrol.config.AppConfig
import com.remotecontrol.core.Hex
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Drives [RelayClient] against a fake relay that repeats the checks from
 * `relay-server/index.js`: identity bound to the key (device_id == sha256 of
 * the raw public key) and an Ed25519 signature over this connection's nonce.
 */
class RelayHandshakeTest {

    private class MemoryStore : DeviceIdentity.KeyValueStore {
        override var privateKeyBase64: String? = null
        override var publicKeyHex: String? = null
    }

    private lateinit var server: MockWebServer
    private lateinit var identity: DeviceIdentity
    private val store = MemoryStore()

    private val states = CopyOnWriteArrayList<ConnectionState>()
    private val messages = CopyOnWriteArrayList<Incoming>()
    private val frames = CopyOnWriteArrayList<ByteArray>()
    private val relayed = CopyOnWriteArrayList<String>()
    private val relayFailures = CopyOnWriteArrayList<String>()
    private val clients = CopyOnWriteArrayList<RelayClient>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        identity = DeviceIdentity(store)
        identity.load()
    }

    @After
    fun tearDown() {
        clients.forEach { runCatching { it.disconnect() } }
        runCatching { server.shutdown() }
    }

    private fun client(paired: Boolean): RelayClient {
        val client = RelayClient(identity)
        client.pairingChecker = { paired }
        client.listener = object : RelayClient.Listener {
            override fun onState(state: ConnectionState) {
                states += state
            }

            override fun onMessage(message: Incoming) {
                messages += message
            }

            override fun onFrame(bytes: ByteArray) {
                frames += bytes
            }
        }
        clients += client
        return client
    }

    private fun relay(nonce: String = "0011223344", expectPairToken: Boolean = true): CountDownLatch {
        val started = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().withWebSocketUpgrade(Relay(nonce, expectPairToken, started))
        }
        return started
    }

    @Test
    fun `register, challenge and auth reach READY`() {
        val accepted = relay()
        val client = client(paired = false)

        client.connect(AppConfig(serverUrl = server.url("/").toString(), pairToken = "pair-token"))

        assertTrue(accepted.await(5, TimeUnit.SECONDS))
        waitUntil { states.contains(ConnectionState.READY) }
        assertEquals(listOf(ConnectionState.CONNECTING, ConnectionState.AUTHENTICATING, ConnectionState.READY), states)
        assertTrue(client.authenticated)

        client.disconnect()
    }

    @Test
    fun `the pair token is only sent while the relay does not know the key`() {
        val first = relay(nonce = "aabb", expectPairToken = true)
        val unpaired = client(paired = false)
        unpaired.connect(AppConfig(serverUrl = server.url("/").toString(), pairToken = "pair-token"))
        assertTrue(first.await(5, TimeUnit.SECONDS))
        waitUntil { states.contains(ConnectionState.READY) }
        unpaired.disconnect()

        states.clear()

        // Second connection for an already paired relay: no pair_token, and the
        // same Ed25519 key is presented.
        val second = relay(nonce = "ccdd", expectPairToken = false)
        val paired = client(paired = true)
        paired.connect(AppConfig(serverUrl = server.url("/").toString(), pairToken = "pair-token"))
        assertTrue(second.await(5, TimeUnit.SECONDS))
        waitUntil { states.contains(ConnectionState.READY) }
        paired.disconnect()
    }

    @Test
    fun `routed messages and binary frames flow through`() {
        val accepted = relay(nonce = "ff00", expectPairToken = false)
        val client = client(paired = true)
        client.connect(AppConfig(serverUrl = server.url("/").toString(), pairToken = "pair-token"))
        assertTrue(accepted.await(5, TimeUnit.SECONDS))
        waitUntil { states.contains(ConnectionState.READY) }

        assertTrue(client.send(Protocol.command("cmd-1", "echo hello")))
        assertTrue(client.send(Protocol.pointerMove(0.5f, 0.25f)))
        assertTrue(client.send(Protocol.keyboardKey("KEY_C", true)))

        waitUntil { relayed.size >= 3 }
        assertTrue(relayed.any { it.contains("\"command\"") && it.contains("echo hello") })
        assertTrue(relayed.any { it.contains("pointer.move") })
        assertTrue(relayed.any { it.contains("keyboard.key") })

        waitUntil { frames.isNotEmpty() }
        assertEquals("jpeg-bytes", String(frames.first()))
        assertTrue(messages.any { it is Incoming.Output && it.data == "hi\n" })

        client.disconnect()
    }
    /** Mirrors the relay's authentication state machine. */
    private inner class Relay(
        private val nonce: String,
        private val expectPairToken: Boolean,
        private val started: CountDownLatch,
    ) : WebSocketListener() {

        private var publicKeyHex = ""
        private var deviceId = ""

        override fun onOpen(webSocket: WebSocket, response: Response) {
            started.countDown()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching { handle(webSocket, text) }
                .onFailure { relayFailures += "${it::class.simpleName}: ${it.message}" }
        }

        private fun handle(webSocket: WebSocket, text: String) {
            val message = JSONObject(text)
            when (message.getString("type")) {
                "register" -> {
                    deviceId = message.getString("device_id")
                    publicKeyHex = message.getString("public_key")
                    val fingerprint = Hex.encode(
                        MessageDigest.getInstance("SHA-256").digest(Hex.decode(publicKeyHex)),
                    )
                    check(deviceId == fingerprint) { "device_id does not match public_key" }
                    if (expectPairToken) {
                        check(message.getString("pair_token") == "pair-token") { "missing pair token" }
                    } else {
                        check(!message.has("pair_token")) { "pair token must not be re-sent" }
                    }
                    webSocket.send(JSONObject().put("type", "challenge").put("nonce", nonce).toString())
                }

                "auth" -> {
                    val payload = "$deviceId:$nonce"
                    val verifier = Signature.getInstance("Ed25519")
                    verifier.initVerify(
                        KeyFactory.getInstance("Ed25519")
                            .generatePublic(X509EncodedKeySpec(wrapRawEd25519(publicKeyHex))),
                    )
                    verifier.update(payload.toByteArray(Charsets.UTF_8))
                    check(verifier.verify(Hex.decode(message.getString("signature")))) { "bad signature" }
                    check(message.getString("device_id") == deviceId)
                    webSocket.send(JSONObject().put("type", "registered").put("device", "phone").toString())
                    // The laptop streams screenshots as raw binary frames over the same socket.
                    webSocket.send(ByteString.of(*"jpeg-bytes".toByteArray(Charsets.UTF_8)))
                }

                else -> {
                    relayed += text
                    webSocket.send(JSONObject().put("type", "output").put("data", "hi\n").toString())
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // The phone is not expected to push binary payloads.
            relayFailures += "unexpected binary frame: ${bytes.size} bytes"
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // Surfaced through the latch assertions below.
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        assertTrue("condition not met in time: states=$states messages=$messages relayed=$relayed", condition())
        assertTrue("fake relay rejected the client: $relayFailures", relayFailures.isEmpty())
    }

    @Test
    fun `client refuses to send before authentication`() {
        val client = client(paired = true)
        assertFalse(client.send(Protocol.command("id", "ls")))
    }
}

private fun wrapRawEd25519(publicKeyHex: String): ByteArray {
    val header = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
    )
    return header + Hex.decode(publicKeyHex)
}