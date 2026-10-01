package com.remotecontrol.config

import com.remotecontrol.BuildConfig

/**
 * Runtime configuration for the phone. The defaults come from `BuildConfig`,
 * which mirrors `remote_control_phone/config.js` so both clients talk to the
 * same relay with the same pairing token and passcode.
 */
data class AppConfig(
    val serverUrl: String = BuildConfig.DEFAULT_SERVER_URL,
    val pairToken: String = BuildConfig.DEFAULT_PAIR_TOKEN,
    val passcode: String = BuildConfig.DEFAULT_PASSCODE,
    /**
     * Require a biometric approval before the app opens. The passcode stays
     * available as the fallback (and becomes the only gate when this is off or
     * the device has no enrolled biometric).
     */
    val biometricLockEnabled: Boolean = true,
    /** Re-lock automatically whenever the app leaves the foreground. */
    val lockOnBackground: Boolean = true,
) {
    /** WebSocket endpoint; http(s):// input is upgraded the same way `remote.js` does. */
    val relayUrl: String get() = toWebSocketUrl(serverUrl)

    /** `host[:port]` of the relay; used as the pairing marker key (like `rc:phone:paired`). */
    val relayHost: String get() = hostOf(relayUrl)

    companion object {
        fun toWebSocketUrl(raw: String): String {
            val url = raw.trim()
            return when {
                url.startsWith("https://") -> "wss://" + url.removePrefix("https://")
                url.startsWith("http://") -> "ws://" + url.removePrefix("http://")
                else -> url
            }
        }

        fun hostOf(url: String): String =
            runCatching { java.net.URI(url).host?.let { host ->
                val port = java.net.URI(url).port
                if (port > 0) "$host:$port" else host
            } }.getOrNull() ?: url
    }
}