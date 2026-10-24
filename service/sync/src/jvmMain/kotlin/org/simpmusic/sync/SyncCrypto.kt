package org.simpmusic.sync

import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

actual object SyncCrypto {
    actual const val VERSION: Byte = 1
    private const val KEY_BYTES = 32
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val PBKDF2_ITERATIONS = 120_000
    private const val BASE32_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val random = SecureRandom()

    actual fun newKey(): ByteArray = ByteArray(KEY_BYTES).also(random::nextBytes)

    actual fun seal(key: ByteArray, associatedData: ByteArray, plaintext: ByteArray): ByteArray {
        require(key.size == KEY_BYTES) { "data key must be 256 bits" }
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(associatedData)
        return byteArrayOf(VERSION) + nonce + cipher.doFinal(plaintext)
    }

    actual fun open(key: ByteArray, associatedData: ByteArray, envelope: ByteArray): ByteArray? {
        if (key.size != KEY_BYTES || envelope.size < 1 + NONCE_BYTES + TAG_BITS / 8 || envelope[0] != VERSION) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, envelope, 1, NONCE_BYTES))
            cipher.updateAAD(associatedData)
            cipher.doFinal(envelope, 1 + NONCE_BYTES, envelope.size - 1 - NONCE_BYTES)
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    actual fun deriveMasterCredentials(email: String, password: String): DerivedCredentials {
        val normalizedEmail = email.trim().lowercase()
        require(normalizedEmail.isNotEmpty()) { "email is required" }
        require(password.length >= 8) { "password must be at least 8 characters" }
        val salt = "simpsync-master-kdf-v1:$normalizedEmail".toByteArray(Charsets.UTF_8)
        val derived = pbkdf2(password.toCharArray(), salt, KEY_BYTES * 2)
        val authBytes = derived.copyOfRange(0, KEY_BYTES)
        val wrapBytes = derived.copyOfRange(KEY_BYTES, KEY_BYTES * 2)
        val authProof = Base64.getUrlEncoder().withoutPadding().encodeToString(authBytes)
        return DerivedCredentials(authProof = authProof, wrappingKey = wrapBytes)
    }

    actual fun generateRecoveryKey(): String {
        val indices = ByteArray(32).also(random::nextBytes)
        val sb = StringBuilder(39)
        for (i in 0 until 32) {
            if (i > 0 && i % 4 == 0) sb.append('-')
            val idx = (indices[i].toInt() and 0xFF) % BASE32_ALPHABET.length
            sb.append(BASE32_ALPHABET[idx])
        }
        return sb.toString()
    }

    actual fun deriveRecovery(recoveryKey: String): DerivedRecovery {
        val normalized = recoveryKey.uppercase().replace("-", "").replace(" ", "").trim()
        require(normalized.length >= 16) { "invalid recovery key" }
        val salt = "simpsync-recovery-kdf-v1".toByteArray(Charsets.UTF_8)
        val derived = pbkdf2(normalized.toCharArray(), salt, KEY_BYTES * 2)
        val hashBytes = derived.copyOfRange(0, KEY_BYTES)
        val wrapBytes = derived.copyOfRange(KEY_BYTES, KEY_BYTES * 2)
        val recoveryHash = Base64.getUrlEncoder().withoutPadding().encodeToString(hashBytes)
        return DerivedRecovery(recoveryKeyHash = recoveryHash, wrappingKey = wrapBytes)
    }

    actual fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun pbkdf2(chars: CharArray, salt: ByteArray, byteLength: Int): ByteArray {
        val spec = PBEKeySpec(chars, salt, PBKDF2_ITERATIONS, byteLength * 8)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
