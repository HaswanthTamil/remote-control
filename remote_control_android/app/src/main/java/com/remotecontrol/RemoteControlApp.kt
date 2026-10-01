package com.remotecontrol

import android.app.Application
import com.remotecontrol.data.RemoteSession

class RemoteControlApp : Application() {

    /** Single owner of the relay connection; shared by every screen. */
    lateinit var session: RemoteSession
        private set

    override fun onCreate() {
        super.onCreate()
        session = RemoteSession(this)
        // No relay connection yet: `RemoteControlRoot` opens it only after the
        // biometric (or passcode) gate has been approved.
    }
}