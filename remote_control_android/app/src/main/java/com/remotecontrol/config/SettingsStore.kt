package com.remotecontrol.config

import android.content.Context
import android.content.SharedPreferences
import com.remotecontrol.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Local persistence for config + pairing state. The storage keys mirror the web
 * client's `localStorage` keys (`rc:phone:*`) so the two implementations stay
 * conceptually identical.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(
        AppConfig(
            serverUrl = prefs.getString(KEY_SERVER_URL, null) ?: BuildConfig.DEFAULT_SERVER_URL,
            pairToken = prefs.getString(KEY_PAIR_TOKEN, null) ?: BuildConfig.DEFAULT_PAIR_TOKEN,
            passcode = prefs.getString(KEY_PASSCODE, null) ?: BuildConfig.DEFAULT_PASSCODE,
        ),
    )
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    val current: AppConfig get() = _config.value

    fun update(config: AppConfig) {
        prefs.edit()
            .putString(KEY_SERVER_URL, config.serverUrl)
            .putString(KEY_PAIR_TOKEN, config.pairToken)
            .putString(KEY_PASSCODE, config.passcode)
            .apply()
        _config.value = config
    }

    /* ---------------- device identity keys (mirrors js/auth.js) ---------------- */

    var privateKeyBase64: String?
        get() = prefs.getString(PRIVATE_KEY_STORAGE, null)
        set(value) = prefs.edit().putString(PRIVATE_KEY_STORAGE, value).apply()

    var publicKeyHex: String?
        get() = prefs.getString(PUBLIC_KEY_STORAGE, null)
        set(value) = prefs.edit().putString(PUBLIC_KEY_STORAGE, value).apply()

    /** `rc:phone:paired:<relayHost>` marker; set once the relay answers `registered`. */
    fun isPaired(relayHost: String): Boolean = prefs.getString(PAIRED_STORAGE + ":" + relayHost, null) != null

    fun markPaired(relayHost: String, deviceId: String) {
        prefs.edit().putString(PAIRED_STORAGE + ":" + relayHost, deviceId).apply()
    }

    fun clearPaired(relayHost: String) {
        prefs.edit().remove(PAIRED_STORAGE + ":" + relayHost).apply()
    }

    fun clearIdentity() {
        prefs.edit().remove(PRIVATE_KEY_STORAGE).remove(PUBLIC_KEY_STORAGE).apply()
    }

    /* ---------------- terminal command history ---------------- */

    fun commandHistory(): List<String> =
        prefs.getString(KEY_HISTORY, null)?.split('\u0000')?.filter { it.isNotBlank() } ?: emptyList()

    fun pushCommand(command: String) {
        val updated = (listOf(command) + commandHistory().filter { it != command }).take(HISTORY_LIMIT)
        prefs.edit().putString(KEY_HISTORY, updated.joinToString("\u0000")).apply()
    }

    companion object {
        private const val NAME = "remote_control"
        private const val KEY_SERVER_URL = "rc:config:server_url"
        private const val KEY_PAIR_TOKEN = "rc:config:pair_token"
        private const val KEY_PASSCODE = "rc:config:passcode"
        private const val KEY_HISTORY = "rc:terminal:history"
        private const val HISTORY_LIMIT = 50

        // Same names as remote_control_phone/config.js
        const val PRIVATE_KEY_STORAGE = "rc:phone:private_key"
        const val PUBLIC_KEY_STORAGE = "rc:phone:public_key"
        const val PAIRED_STORAGE = "rc:phone:paired"
    }
}