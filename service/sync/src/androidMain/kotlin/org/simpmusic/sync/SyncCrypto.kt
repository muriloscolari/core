package org.simpmusic.sync

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object SyncCrypto {
    const val VERSION: Byte = 1
    private const val KEY_BYTES = 32
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun newKey(): ByteArray = ByteArray(KEY_BYTES).also(random::nextBytes)

    fun seal(key: ByteArray, associatedData: ByteArray, plaintext: ByteArray): ByteArray {
        require(key.size == KEY_BYTES) { "data key must be 256 bits" }
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(associatedData)
        return byteArrayOf(VERSION) + nonce + cipher.doFinal(plaintext)
    }

    fun open(key: ByteArray, associatedData: ByteArray, envelope: ByteArray): ByteArray? {
        if (key.size != KEY_BYTES || envelope.size < 1 + NONCE_BYTES + TAG_BITS / 8) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, envelope, 1, NONCE_BYTES))
            cipher.updateAAD(associatedData)
            cipher.doFinal(envelope, 1 + NONCE_BYTES, envelope.size - 1 - NONCE_BYTES)
        } catch (_: GeneralSecurityException) {
            null
        }
    }
}
