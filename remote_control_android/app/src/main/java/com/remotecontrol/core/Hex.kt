package com.remotecontrol.core

/** Hex helpers for the wire format (raw public keys / signatures travel as hex). */
object Hex {
    fun encode(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            out.append(HEX_CHARS[v ushr 4])
            out.append(HEX_CHARS[v and 0x0f])
        }
        return out.toString()
    }

    fun decode(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex string must have even length" }
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "invalid hex character" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private const val HEX_CHARS = "0123456789abcdef"
}