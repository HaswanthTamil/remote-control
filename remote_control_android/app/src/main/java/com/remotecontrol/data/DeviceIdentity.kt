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

    /**
     * Empty until [load] has completed. The handshake always calls [load] first,
     * but the UI may render before it finishes, so this must never throw.
     */
    val publicKeyHex: String
        get() = keyPair?.let { Hex.encode(rawPublicKey(it.public)) }.orEmpty()

    val deviceId: String
        get() = keyPair?.let { Hex.encode(sha256(rawPublicKey(it.public))) }.orEmpty()

    val isLoaded: Boolean get() = keyPair != null

    /** Loads the stored keypair, or generates one on first run. */
    @Synchronized
    fun load() {
        keyPair?.let { return }
        val privB64 = storage.privateKeyBase64
        val pubHex = storage.publicKeyHex
        if (privB64 != null && pubHex != null) {
            val restored = runCatching {
                val der = Base64.getDecoder().decode(privB64)
                val privateKey: PrivateKey = keyFactory().generatePrivate(PKCS8EncodedKeySpec(der))
                // `publicKeyHex` is the raw 32-byte point, so wrap it back into
                // the X.509 envelope the KeyFactory expects.
                val publicKey: PublicKey = keyFactory()
                    .generatePublic(X509EncodedKeySpec(wrapRawEd25519(Hex.decode(pubHex))))
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
        signer.initSign(checkNotNull(keyPair) { "load() must run before signing" }.private)
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
         * Extracts the raw 32-byte Ed25519 point from an X.509
         * SubjectPublicKeyInfo (`SEQUENCE { AlgorithmIdentifier, BIT STRING }`),
         * so the hex we send matches the browser's `exportKey("raw")` and
         * Python's raw public bytes.
         */
        fun rawPublicKey(publicKey: PublicKey): ByteArray {
            val spki = DerReader(publicKey.encoded).readNext()?.content
                ?: error("malformed SubjectPublicKeyInfo")
            val inner = DerReader(spki)
            var bitString: ByteArray? = null
            while (true) {
                val element = inner.readNext() ?: break
                if (element.tag == BIT_STRING_TAG) {
                    bitString = element.content
                    break
                }
            }
            val bits = bitString ?: error("malformed public key bit string")
            require(bits.isNotEmpty()) { "empty public key bit string" }
            return bits.copyOfRange(1, bits.size) // drop the "unused bits" byte
        }

                const val BIT_STRING_TAG = 0x03

        /** X.509 SubjectPublicKeyInfo header for an Ed25519 raw public key. */
        private val SPKI_HEADER = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
        )

        fun wrapRawEd25519(raw: ByteArray): ByteArray = SPKI_HEADER + raw
    }
}

/** Minimal DER TLV reader - enough to unwrap SPKI without pulling in a crypto lib. */
private class DerReader(private val bytes: ByteArray) {

    class Element(val tag: Int, val content: ByteArray)

    private var index = 0

    fun readNext(): Element? {
        if (index + 2 > bytes.size) return null
        val tag = bytes[index++].toInt() and 0xff
        val length = readLength() ?: return null
        if (length < 0 || index + length > bytes.size) return null
        val content = bytes.copyOfRange(index, index + length)
        index += length
        return Element(tag, content)
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