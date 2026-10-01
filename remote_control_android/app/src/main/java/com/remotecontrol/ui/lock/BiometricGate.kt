package com.remotecontrol.ui.lock

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Can the device authenticate this user with biometrics right now? */
enum class BiometricStatus {
    /** Hardware present and at least one biometric enrolled. */
    AVAILABLE,

    /** Hardware works, but nothing is enrolled - passcode fallback only. */
    NONE_ENROLLED,

    /** No biometric hardware at all. */
    NO_HARDWARE,

    /** Sensor present but permanently unusable (hardware error). */
    UNAVAILABLE,
}

sealed interface GateEvent {
    data class Unlocked(val via: UnlockMethod) : GateEvent
    data class Failed(val message: String) : GateEvent

    /** User dismissed the system sheet (negative button / cancel). */
    data object Fallback : GateEvent
}

enum class UnlockMethod { BIOMETRIC, PASSCODE }

/**
 * Wraps [BiometricPrompt] so the lock screen can be driven from Compose.
 *
 * Biometrics are the primary gate; the app passcode (`PASSCODE` in the config,
 * same value as the web client's) is the fallback. A passcode alone can never
 * open the app without an explicit user action here, which is the same trust
 * model as the browser's `sessionStorage.rc_unlocked` gate but bound to the
 * device's secure hardware and the enrolled user.
 */
class BiometricGate(private val activity: FragmentActivity) {

    private val _status = MutableStateFlow(statusOf(activity))
    val status: StateFlow<BiometricStatus> = _status.asStateFlow()

    private val _authenticating = MutableStateFlow(false)
    val authenticating: StateFlow<Boolean> = _authenticating.asStateFlow()

    private val _events = MutableStateFlow<GateEvent?>(null)
    val events: StateFlow<GateEvent?> = _events.asStateFlow()

    private val prompt: BiometricPrompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                _authenticating.value = false
                _events.value = GateEvent.Unlocked(UnlockMethod.BIOMETRIC)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                _authenticating.value = false
                _events.value = when (errorCode) {
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_CANCELED,
                    -> GateEvent.Fallback

                    BiometricPrompt.ERROR_LOCKOUT,
                    BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
                    -> GateEvent.Failed(errString.toString())

                    else -> GateEvent.Failed(errString.toString())
                }
            }

            override fun onAuthenticationFailed() {
                // A single unrecognised finger/face: the sheet stays up and the
                // system shows its own "not recognized" hint, so keep the
                // authenticating state and let the user retry.
            }
        },
    )

    fun refreshStatus() {
        _status.value = statusOf(activity)
    }

    fun authenticate() {
        if (_status.value != BiometricStatus.AVAILABLE) {
            _events.value = GateEvent.Failed("Biometrics unavailable on this device")
            return
        }
        _authenticating.value = true
        _events.value = null
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Remote Control")
                .setSubtitle("Confirm it's you to open the laptop console")
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.BIOMETRIC_STRONG,
                )
                .setConfirmationRequired(false)
                .setNegativeButtonText("Use passcode")
                .build(),
        )
    }

    fun consumeEvent() {
        _events.value = null
    }

    companion object {
        fun statusOf(context: Context): BiometricStatus =
            when (BiometricManager.from(context).canAuthenticate(allowedAuthenticators())) {
                BiometricManager.BIOMETRIC_SUCCESS -> BiometricStatus.AVAILABLE
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricStatus.NONE_ENROLLED
                BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
                BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
                -> BiometricStatus.NO_HARDWARE

                else -> BiometricStatus.UNAVAILABLE
            }

        private fun allowedAuthenticators(): Int =
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.BIOMETRIC_STRONG

        /** Resolves the hosting activity, needed because BiometricPrompt requires one. */
        fun fragmentActivity(context: Context?): FragmentActivity? {
            var current = context
            while (current is ContextWrapper) {
                if (current is FragmentActivity) return current
                current = current.baseContext
            }
            return null
        }
    }
}