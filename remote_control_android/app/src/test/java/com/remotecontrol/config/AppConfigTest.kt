package com.remotecontrol.config

import org.junit.Assert.assertEquals
import org.junit.Test

class AppConfigTest {

    @Test
    fun `http urls are upgraded to websocket schemes`() {
        assertEquals("wss://relay.example.com/", AppConfig.toWebSocketUrl("https://relay.example.com/"))
        assertEquals("ws://localhost:3000", AppConfig.toWebSocketUrl("http://localhost:3000"))
        assertEquals("wss://relay.example.com", AppConfig.toWebSocketUrl("wss://relay.example.com"))
        assertEquals("ws://10.0.0.5:3000", AppConfig.toWebSocketUrl("  ws://10.0.0.5:3000  "))
    }

    @Test
    fun `relay host drops the scheme and keeps a non default port`() {
        assertEquals("relay.example.com", AppConfig.hostOf("wss://relay.example.com/"))
        assertEquals("localhost:3000", AppConfig.hostOf("ws://localhost:3000"))
    }

    @Test
    fun `config exposes the upgraded url and its host`() {
        val config = AppConfig(serverUrl = "https://remote-control.example/")
        assertEquals("wss://remote-control.example/", config.relayUrl)
        assertEquals("remote-control.example", config.relayHost)
    }

    @Test
    fun `the shipped defaults match the web client config`() {
        val config = AppConfig()
        assertEquals("wss://remote-control-lmxu.vercel.app/", config.relayUrl)
        assertEquals("rc.pairToken (see gitignored env files)", config.pairToken)
        assertEquals("laser", config.passcode)
        assertEquals(true, config.biometricLockEnabled)
    }
}