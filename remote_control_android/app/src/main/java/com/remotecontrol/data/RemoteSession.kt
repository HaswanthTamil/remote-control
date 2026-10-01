package com.remotecontrol.data

import android.app.Application
import com.remotecontrol.config.AppConfig
import com.remotecontrol.config.SettingsStore
import com.remotecontrol.input.KeyMap
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Application-scoped owner of the relay connection. Both screens talk to the
 * single authenticated socket the same way the web app shares `auth.js`, but
 * unlike the browser (one socket per page, which makes them fight over the
 * relay's single device slot) there is exactly one socket per app.
 */
class RemoteSession(private val app: Application) {

    val settings = SettingsStore(app)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val identity = DeviceIdentity(
        object : DeviceIdentity.KeyValueStore {
            override var privateKeyBase64: String?
                get() = settings.privateKeyBase64
                set(value) { settings.privateKeyBase64 = value }

            override var publicKeyHex: String?
                get() = settings.publicKeyHex
                set(value) { settings.publicKeyHex = value }
        },
    )

    private val client = RelayClient(identity).apply {
        pairingChecker = { host -> settings.isPaired(host) }
        listener = object : RelayClient.Listener {
            override fun onState(state: ConnectionState) = onStateChanged(state)
            override fun onMessage(message: Incoming) = onMessageReceived(message)
            override fun onFrame(bytes: ByteArray) {
                frames.trySend(bytes) // newest-wins: a slow phone must not queue frames
            }
        }
    }

    private val _connection = MutableStateFlow(ConnectionState.OFFLINE)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _lines = MutableStateFlow<List<TerminalLine>>(emptyList())
    val lines: StateFlow<List<TerminalLine>> = _lines.asStateFlow()

    private val _screenStatus = MutableStateFlow(ScreenStatus())
    val screenStatus: StateFlow<ScreenStatus> = _screenStatus.asStateFlow()

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    val frames: Flow<ByteArray> = framesChannel.receiveAsFlow()

    private val framesChannel = Channel<ByteArray>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val config: AppConfig get() = settings.current
    val deviceId: String get() = identity.deviceId
    val publicKeyHex: String get() = identity.publicKeyHex
    val isReady: Boolean get() = client.authenticated
    val isPaired: Boolean get() = settings.isPaired(config.relayHost)

    init {
        scope.launch { identity.load() }
    }

    fun connect() {
        scope.launch {
            identity.load()
            client.connect(settings.current)
        }
    }

    fun disconnect() = client.disconnect()

    fun reconnect() = client.reconnectNow()

    fun applyConfig(config: AppConfig) {
        settings.update(config)
        reconnect()
    }

    /** Forgets the pairing marker so the next register sends the pair token again. */
    fun clearPairing() {
        settings.clearPaired(config.relayHost)
        _notices.tryEmit("Pairing marker cleared - next connect re-pairs")
    }

    /** Mints a brand new keypair; the relay treats it as a new device. */
    fun regenerateIdentity() {
        settings.clearIdentity()
        settings.clearPaired(config.relayHost)
        scope.launch {
            identity.generate()
            client.reconnectNow()
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Terminal                                                          */
    /* ------------------------------------------------------------------ */

    fun runCommand(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return
        if (!client.authenticated) {
            appendLine("Not connected to the laptop yet", LineKind.System)
            return
        }
        val id = UUID.randomUUID().toString()
        if (client.send(Protocol.command(id, trimmed))) {
            appendLine("\$ $trimmed", LineKind.Command)
            settings.pushCommand(trimmed)
        } else {
            appendLine("Laptop is not connected", LineKind.System)
        }
    }

    fun interrupt() {
        if (!client.authenticated) {
            appendLine("Not connected to the laptop yet", LineKind.System)
            return
        }
        val id = UUID.randomUUID().toString()
        if (client.send(Protocol.signal(id, "SIGINT"))) {
            appendLine("^C", LineKind.Command)
        }
    }

    fun clearTerminal() = _lines.value = emptyList()

    /* ------------------------------------------------------------------ */
    /*  Screen + input                                                    */
    /* ------------------------------------------------------------------ */

    fun requestCapture(start: Boolean) {
        client.send(Protocol.screenRequest(if (start) "start" else "stop"))
    }

    fun pointerMove(x: Float, y: Float) {
        client.send(Protocol.pointerMove(x, y))
    }

    fun pointerButton(button: String, down: Boolean) {
        client.send(Protocol.pointerButton(button, down))
    }

    fun pointerScroll(dy: Float) {
        client.send(Protocol.pointerScroll(0f, dy))
    }

    /** Sends one `keyboard.key` with explicit down/up semantics. */
    fun key(key: String, down: Boolean) {
        client.send(Protocol.keyboardKey(key, down))
    }

    fun tapKey(key: String) = scope.launch {
        key(key, true)
        delay(KEY_HOLD_MS)
        key(key, false)
    }

    fun sendCombo(modifiers: List<String>, key: String) = scope.launch {
        modifiers.forEach { key(it, true) }
        delay(MOD_LEAD_MS)
        key(key, true)
        delay(KEY_HOLD_MS)
        key(key, false)
        delay(MOD_TRAIL_MS)
        modifiers.forEach { key(it, false) }
    }

    /** Types literal text as real key events (shift held only where needed). */
    fun typeText(text: String) = scope.launch {
        var shiftDown = false
        for (char in text) {
            val step = KeyMap.typeChar(char) ?: continue
            if (step.shift && !shiftDown) {
                key(KeyMap.SHIFT, true)
                shiftDown = true
            }
            key(step.key, true)
            delay(KEY_HOLD_MS)
            key(step.key, false)
            delay(KEY_INTERVAL_MS)
        }
        if (shiftDown) {
            delay(KEY_INTERVAL_MS)
            key(KeyMap.SHIFT, false)
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Relay callbacks                                                   */
    /* ------------------------------------------------------------------ */

    private fun onStateChanged(state: ConnectionState) {
        val previous = _connection.value
        _connection.value = state
        scope.launch {
            when (state) {
                ConnectionState.CONNECTING -> appendLine("-> Connecting...", LineKind.Muted)
                ConnectionState.AUTHENTICATING -> appendLine("-> Connected, authenticating...", LineKind.Muted)
                ConnectionState.READY -> {
                    appendLine("-> Authenticated", LineKind.Muted)
                    settings.markPaired(config.relayHost, deviceId)
                    if (previous == ConnectionState.SUPERSEDED) {
                        appendLine("Reconnected after being superseded", LineKind.Muted)
                    }
                }

                ConnectionState.OFFLINE -> if (previous == ConnectionState.READY) {
                    appendLine("x Disconnected. Reconnecting...", LineKind.Muted)
                }

                ConnectionState.SUPERSEDED -> {
                    appendLine(
                        "x Superseded by a newer connection. Not reconnecting.",
                        LineKind.Muted,
                    )
                }
            }
        }
    }

    private fun onMessageReceived(message: Incoming) {
        when (message) {
            is Incoming.Output -> appendLine(message.data, LineKind.Output)
            is Incoming.Stderr -> appendLine(message.data, LineKind.Error)
            is Incoming.Exit -> appendLine(
                "--- process exited with code ${message.code ?: "unknown"} ---",
                LineKind.Muted,
            )

            is Incoming.Ack -> Unit
            is Incoming.Error -> {
                appendLine("x ${message.message}", LineKind.Error)
                // Self-healing: if the relay doesn't know a device we thought was
                // paired (fresh/emptied keystore after a redeploy), drop the marker
                // so the next register includes PAIR_TOKEN again.
                if (message.message.contains("not paired", ignoreCase = true) && isPaired) {
                    settings.clearPaired(config.relayHost)
                    appendLine("x Pairing state stale; re-pairing on next connect", LineKind.Muted)
                }
            }

            is Incoming.ScreenStatus -> _screenStatus.value = ScreenStatus(
                active = message.active,
                width = message.width,
                height = message.height,
                message = message.message,
            )

            is Incoming.Registered, is Incoming.Challenge, is Incoming.Unknown -> Unit
        }
    }

    private fun appendLine(text: String, kind: LineKind) {
        _lines.value = (_lines.value + TerminalLine(text, kind)).takeLast(MAX_LINES)
    }

    private companion object {
        const val MAX_LINES = 500
        const val KEY_HOLD_MS = 40L
        const val MOD_LEAD_MS = 30L
        const val MOD_TRAIL_MS = 40L
        const val KEY_INTERVAL_MS = 20L
    }
}

enum class LineKind { Output, Error, Command, Muted, System }

data class TerminalLine(val text: String, val kind: LineKind)

data class ScreenStatus(
    val active: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    val message: String = "",
)