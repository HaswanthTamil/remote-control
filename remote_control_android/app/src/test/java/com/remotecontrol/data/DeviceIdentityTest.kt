package com.remotecontrol.data

import com.remotecontrol.core.Hex
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeviceIdentityTest {

    private class MemoryStore : DeviceIdentity.KeyValueStore {
        override var privateKeyBase64: String? = null
        override var publicKeyHex: String? = null
    }

    private lateinit var store: MemoryStore

    @Before
    fun setUp() {
        store = MemoryStore()
    }

    @Test
    fun `first run generates a keypair and persists it`() {
        val identity = DeviceIdentity(store)
        identity.load()

        assertEquals(64, identity.publicKeyHex.length)
        assertEquals(64, identity.deviceId.length)
        assertNotNull(store.privateKeyBase64)
        assertEquals(identity.publicKeyHex, store.publicKeyHex)
    }

    @Test
    fun `a stored keypair is restored instead of regenerated`() {
        val first = DeviceIdentity(store).apply { load() }
        val second = DeviceIdentity(store).apply { load() }

        assertEquals(first.publicKeyHex, second.publicKeyHex)
        assertEquals(first.deviceId, second.deviceId)
    }

    @Test
    fun `device id is the sha256 fingerprint of the raw public key`() {
        val identity = DeviceIdentity(store).apply { load() }
        val digest = MessageDigest.getInstance("SHA-256").digest(Hex.decode(identity.publicKeyHex))

        assertEquals(Hex.encode(digest), identity.deviceId)
    }

    @Test
    fun `signature verifies against the raw public key, like the relay does`() {
        val identity = DeviceIdentity(store).apply { load() }
        val payload = "${identity.deviceId}:deadbeef"

        val signature = Hex.decode(identity.sign(payload))

        // Rebuild the key exactly like relay-server/index.js does from raw hex.
        val spec = X509EncodedKeySpec(wrapRawEd25519(identity.publicKeyHex))
        val verifier = Signature.getInstance("Ed25519", BouncyCastleProvider())
        verifier.initVerify(KeyFactory.getInstance("Ed25519", BouncyCastleProvider()).generatePublic(spec))
        verifier.update(payload.toByteArray(Charsets.UTF_8))

        assertEquals(64, signature.size)
        assertTrue(verifier.verify(signature))
    }

    @Test
    fun `a tampered payload does not verify`() {
        val identity = DeviceIdentity(store).apply { load() }
        val signature = Hex.decode(identity.sign("payload-a"))

        val verifier = Signature.getInstance("Ed25519", BouncyCastleProvider())
        verifier.initVerify(
            KeyFactory.getInstance("Ed25519", BouncyCastleProvider())
                .generatePublic(X509EncodedKeySpec(wrapRawEd25519(identity.publicKeyHex))),
        )
        verifier.update("payload-b".toByteArray(Charsets.UTF_8))

        assertTrue(!verifier.verify(signature))
    }

    @Test
    fun `generate replaces the identity`() {
        val identity = DeviceIdentity(store).apply { load() }
        val before = identity.deviceId
        identity.generate()

        assertTrue(before != identity.deviceId)
        assertEquals(identity.publicKeyHex, store.publicKeyHex)
    }

    @Test
    fun `keys are interchangeable with the platform provider`() {
        val identity = DeviceIdentity(store).apply { load() }

        // The stored PKCS#8 blob must be readable by a plain JCA provider too,
        // i.e. the format matches what the browser/Python peers exchange.
        val platform = runCatching { KeyPairGenerator.getInstance("Ed25519") }.getOrNull()
        if (platform != null) {
            val restored = KeyFactory.getInstance("Ed25519")
                .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(Base64.getDecoder().decode(store.privateKeyBase64)))
            assertNotNull(restored)
        }
    }
}

/** Builds a SubjectPublicKeyInfo around a raw 32-byte Ed25519 point. */
private fun wrapRawEd25519(publicKeyHex: String): ByteArray {
    val raw = Hex.decode(publicKeyHex)
    val header = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
    )
    return header + raw
}