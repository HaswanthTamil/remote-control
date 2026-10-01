package com.remotecontrol.data

import com.remotecontrol.config.AppConfig
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/** Coarse connection state, surfaced as the status pill on every screen. */
enum class ConnectionState { OFFLINE, CONNECTING, AUTHENTICATING, READY, SUPERSEDED }

/**
 * One authenticated WebSocket to the relay - the Kotlin twin of the connection
 * code duplicated in `remote.js` / `terminal.js`.
 *
 * Handshake: `register` -> relay `challenge` -> `auth` (Ed25519 signature over
 * `<device_id>:<nonce>`) -> `registered`. Reconnects with the same capped
 * exponential backoff as the web client, and gives up when the relay closes the
 * socket with `4001` (a newer connection took over the device slot).
 */
class RelayClient(private val identity: DeviceIdentity) {

    interface Listener {
        fun onState(state: ConnectionState)
        fun onMessage(message: Incoming)
        fun onFrame(bytes: ByteArray)
    }

    var listener: Listener? = null

    /** Tells us whether this relay already knows our key, so we only pair once. */
    var pairingChecker: ((String) -> Boolean)? = null

    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived socket
        .build()

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "relay-reconnect").apply { isDaemon = true }
    }

    private val lock = Any()
    private var socket: WebSocket? = null
    private var config: AppConfig? = null
    private var stopped = true
    private var reconnectDelay = INITIAL_DELAY_MS
    private var reconnectPending = false

    @Volatile
    var authenticated: Boolean = false
        private set

    fun connect(config: AppConfig) {
        synchronized(lock) {
            stopped = false
            reconnectDelay = INITIAL_DELAY_MS
            this.config = config
        }
        openSocket(config)
    }

    fun disconnect() {
        synchronized(lock) {
            stopped = true
            reconnectPending = false
        }
        authenticated = false
        socket?.close(NORMAL_CLOSURE, "client closed")
        socket = null
        listener?.onState(ConnectionState.OFFLINE)
    }

    /** Drops the socket and reconnects immediately ("Reconnect now"). */
    fun reconnectNow() {
        val target = synchronized(lock) {
            stopped = false
            reconnectPending = false
            reconnectDelay = INITIAL_DELAY_MS
            config
        } ?: return
        authenticated = false
        socket?.close(NORMAL_CLOSURE, "reconnect requested")
        socket = null
        openSocket(target)
    }

    fun send(message: JSONObject): Boolean {
        val ws = socket ?: return false
        if (!authenticated) return false
        return ws.send(message.toString())
    }

    private fun openSocket(config: AppConfig) {
        synchronized(lock) {
            if (stopped || socket != null) return
            socket = http.newWebSocket(Request.Builder().url(config.relayUrl).build(), SocketListener())
        }
        listener?.onState(ConnectionState.CONNECTING)
    }

    private fun scheduleReconnect() {
        val delay = synchronized(lock) {
            if (stopped || reconnectPending) return
            reconnectPending = true
            reconnectDelay.also { reconnectDelay = (it * 2).coerceAtMost(MAX_DELAY_MS) }
        }
        scheduler.schedule(
            {
                val proceed = synchronized(lock) {
                    if (!reconnectPending || stopped) false
                    else {
                        reconnectPending = false
                        true
                    }
                }
                val target = synchronized(lock) { config }
                if (proceed && target != null) openSocket(target)
            },
            delay,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun sendRegister(ws: WebSocket) {
        val config = config ?: return
        // The one-time pair token only travels while this relay does not know the
        // key; once the relay answers `registered` the marker stops us re-sending it.
        val alreadyPaired = pairingChecker?.invoke(config.relayHost) == true
        val pairToken = config.pairToken.takeIf { !alreadyPaired }
        send(ws, Protocol.register(identity.deviceId, identity.publicKeyHex, pairToken))
    }

    private inner class SocketListener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) {
                reconnectPending = false
                reconnectDelay = INITIAL_DELAY_MS
            }
            listener?.onState(ConnectionState.AUTHENTICATING)
            sendRegister(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            when (val message = Protocol.parse(text)) {
                is Incoming.Challenge -> {
                    val signature = identity.sign("${identity.deviceId}:${message.nonce}")
                    send(webSocket, Protocol.auth(identity.deviceId, signature))
                }

                is Incoming.Registered -> {
                    authenticated = true
                    listener?.onState(ConnectionState.READY)
                }

                null -> Unit
                else -> listener?.onMessage(message)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteArray) {
            // Raw binary = JPEG screen frame from the laptop (fast-lane, untouched).
            listener?.onFrame(bytes)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            authenticated = false
            clearSocket(webSocket)
            if (code == Protocol.CLOSE_SUPERSEDED) {
                // A newer connection for this device owns the slot; don't fight it.
                listener?.onState(ConnectionState.SUPERSEDED)
                return
            }
            listener?.onState(ConnectionState.OFFLINE)
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            authenticated = false
            clearSocket(webSocket)
            listener?.onState(ConnectionState.OFFLINE)
            scheduleReconnect()
        }

        private fun clearSocket(webSocket: WebSocket) {
            synchronized(lock) {
                if (socket === webSocket) socket = null
            }
        }
    }

    private companion object {
        const val INITIAL_DELAY_MS = 1_000L
        const val MAX_DELAY_MS = 30_000L
        const val NORMAL_CLOSURE = 1000
    }
}