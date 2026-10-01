package com.remotecontrol.data

import com.remotecontrol.core.Hex
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * Ed25519 device identity, the Kotlin twin of `remote_control_phone/js/auth.js`.
 *
 * The private key never leaves the device: it is persisted as a PKCS#8 DER blob
 * (base64) exactly like the browser stores it in `localStorage`. The public key
 * is exchanged at registration and the relay verifies our signature over its
 * fresh per-connection nonce.
 *
 * `deviceId` = `sha256(raw public key).hexdigest()` — the key fingerprint,
 * computed identically by Node, Python, the browser and here, so identity stays
 * bound to the key.
 */
class DeviceIdentity(private val storage: KeyValueStore) {

    interface KeyValueStore {
        var privateKeyBase64: String?
        var publicKeyHex: String?
    }

    @Volatile
    private var keyPair: KeyPair? = null

    val publicKeyHex: String
        get() = keyPair?.let { Hex.encode(rawPublicKey(it.public)) }
            ?: error("DeviceIdentity.load() must be called before reading the public key")

    val deviceId: String
        get() = Hex.encode(sha256(rawPublicKey(keyPair!!.public)))

    /** Loads the stored keypair, or generates one on first run. */
    @Synchronized
    fun load() {
        keyPair?.let { return }
        val privB64 = storage.privateKeyBase64
        val pubHex = storage.publicKeyHex
        if (privB64 && pubHex) {
            val restored = runCatching {
                val der = Base64.getDecoder().decode(privB64)
                val privateKey: PrivateKey = keyFactory().generatePrivate(PKCS8EncodedKeySpec(der))
                val publicKey: PublicKey = keyFactory().generatePublic(X509EncodedKeySpec(Hex.decode(pubHex)))
                KeyPair(publicKey, privateKey)
            }.getOrNull()
            if (restored != null) {
                keyPair = restored
                return
            }
            // Stored key is unusable (corrupt or from an incompatible provider):
            // fall through and mint a fresh identity, exactly like the web app.
        }
        generate()
    }

    @Synchronized
    fun generate() {
        val generated = keyPairGenerator().generateKeyPair()
        keyPair = generated
        storage.privateKeyBase64 = Base64.getEncoder().encodeToString(generated.private.encoded)
        storage.publicKeyHex = Hex.encode(rawPublicKey(generated.public))
    }

    /** Signs `<deviceId>:<nonce>` - the exact payload the relay verifies. */
    fun sign(message: String): String {
        val signer = signature()
        signer.initSign(keyPair!!.private)
        signer.update(message.toByteArray(Charsets.UTF_8))
        return Hex.encode(signer.sign())
    }

    private companion object {
        /**
         * Ed25519 is only in the platform crypto provider from API 33, so fall
         * back to BouncyCastle on older devices. Both produce identical RFC 8032
         * signatures, which is what the relay (Node) and the laptop (Python) verify.
         */
        val bouncyCastle: BouncyCastleProvider by lazy {
            BouncyCastleProvider().also { provider ->
                runCatching { java.security.Security.addProvider(provider) }
            }
        }

        fun keyPairGenerator(): KeyPairGenerator =
            runCatching { KeyPairGenerator.getInstance(ALGORITHM) }.getOrElse {
                KeyPairGenerator.getInstance(ALGORITHM, bouncyCastle)
            }

        fun keyFactory(): KeyFactory =
            runCatching { KeyFactory.getInstance(ALGORITHM) }.getOrElse {
                KeyFactory.getInstance(ALGORITHM, bouncyCastle)
            }

        fun signature(): Signature =
            runCatching { Signature.getInstance(ALGORITHM) }.getOrElse {
                Signature.getInstance(ALGORITHM, bouncyCastle)
            }

        const val ALGORITHM = "Ed25519"

        fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

        /**
         * Extracts the raw 32-byte Ed25519 point from an X.509 SubjectPublicKeyInfo
         * (SEQUENCE { BIT STRING }), so the hex we send matches the browser's
         * `exportKey("raw")` and Python's `public_bytes_raw`.
         */
        fun rawPublicKey(publicKey: PublicKey): ByteArray {
            val der = publicKey.encoded
            val seq = DerReader(der)
            val sequence = seq.readTagged(0x30) ?: error("malformed SubjectPublicKeyInfo")
            val bitString = DerReader(sequence).readTagged(0x03) ?: error("malformed public key bit string")
            if (bitString.isEmpty()) error("empty public key bit string")
            return bitString.copyOfRange(1, bitString.size) // drop the "unused bits" byte
        }
    }
}

/** Minimal DER TLV reader - just enough to unwrap SPKI, avoiding a crypto lib. */
private class DerReader(private val bytes: ByteArray) {
    private var index = 0

    fun readTagged(expectedTag: Int): ByteArray? {
        if (index + 2 > bytes.size) return null
        val tag = bytes[index++].toInt() and 0xff
        if (tag != expectedTag) return null
        val length = readLength() ?: return null
        if (index + length > bytes.size) return null
        return bytes.copyOfRange(index, index + length).also { index += length }
    }

    private fun readLength(): Int? {
        val first = bytes[index++].toInt() and 0xff
        if (first < 0x80) return first
        val count = first and 0x7f
        if (count == 0 || count > 4 || index + count > bytes.size) return null
        var length = 0
        repeat(count) { length = (length shl 8) or (bytes[index++].toInt() and 0xff) }
        return length
    }
}