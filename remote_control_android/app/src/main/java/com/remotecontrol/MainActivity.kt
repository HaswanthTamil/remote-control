package com.remotecontrol

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import com.remotecontrol.data.RemoteSession
import com.remotecontrol.ui.RemoteControlRoot

/**
 * Hosts the Compose UI. It extends [FragmentActivity] because `BiometricPrompt`
 * needs a fragment host - that is what turns the app's front door into a real
 * biometric gate instead of a soft passcode prompt.
 */
class MainActivity : FragmentActivity() {

    private val session: RemoteSession get() = (application as RemoteControlApp).session

    /** The single source of truth for "is the app locked"; lives here so `onStop` can flip it. */
    private var locked by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RemoteControlRoot(
                session = session,
                locked = locked,
                onUnlocked = { locked = false },
                onLock = { locked = true },
            )
        }
    }

    override fun onStop() {
        super.onStop()
        // Leaving the foreground re-locks the app, so coming back always needs
        // biometrics (or the passcode fallback) again.
        if (session.settings.current.lockOnBackground) {
            locked = true
            session.onBackgrounded()
        }
    }
}