package org.simpmusic.sync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

expect object SyncCrypto {
    val VERSION: Byte

    fun newKey(): ByteArray

    fun seal(key: ByteArray, associatedData: ByteArray, plaintext: ByteArray): ByteArray

    fun open(key: ByteArray, associatedData: ByteArray, envelope: ByteArray): ByteArray?

    fun deriveMasterCredentials(email: String, password: String): DerivedCredentials

    fun generateRecoveryKey(): String

    fun deriveRecovery(recoveryKey: String): DerivedRecovery

    fun sha256Hex(data: ByteArray): String
}

@OptIn(ExperimentalEncodingApi::class)
object SyncEnvelopeHelper {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    const val KEYRING_ENTITY_TYPE = "crypto"
    const val KEYRING_ENTITY_ID = "keyring"
    private const val MASTER_WRAP_AAD = "crypto/keyring/master"
    private const val RECOVERY_WRAP_AAD = "crypto/keyring/recovery"

    fun aad(entityType: String, entityId: String): ByteArray =
        "$entityType/$entityId".encodeToByteArray()

    fun sealToBase64(
        key: ByteArray,
        entityType: String,
        entityId: String,
        plaintext: ByteArray,
    ): String {
        val sealed = SyncCrypto.seal(key, aad(entityType, entityId), plaintext)
        return Base64.encode(sealed)
    }

    fun openFromBase64(
        key: ByteArray,
        entityType: String,
        entityId: String,
        envelopeBase64: String,
    ): ByteArray? {
        if (envelopeBase64.isBlank()) return null
        val bytes = try {
            Base64.decode(envelopeBase64)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return SyncCrypto.open(key, aad(entityType, entityId), bytes)
    }

    fun encodeKey(key: ByteArray): String = Base64.encode(key)

    fun decodeKey(encoded: String): ByteArray? =
        try {
            Base64.decode(encoded).takeIf { it.size == 32 }
        } catch (_: IllegalArgumentException) {
            null
        }

    fun createKeyringEnvelope(
        dek: ByteArray,
        masterWrappingKey: ByteArray,
        recoveryWrappingKey: ByteArray,
    ): String {
        val masterWrapped = Base64.encode(
            SyncCrypto.seal(masterWrappingKey, MASTER_WRAP_AAD.encodeToByteArray(), dek),
        )
        val recoveryWrapped = Base64.encode(
            SyncCrypto.seal(recoveryWrappingKey, RECOVERY_WRAP_AAD.encodeToByteArray(), dek),
        )
        val keyring = SyncKeyring(
            version = 1,
            masterWrappedDek = masterWrapped,
            recoveryWrappedDek = recoveryWrapped,
        )
        return Base64.encode(json.encodeToString(keyring).encodeToByteArray())
    }

    fun unwrapKeyringWithMaster(
        keyringEnvelopeBase64: String,
        masterWrappingKey: ByteArray,
    ): Pair<ByteArray, SyncKeyring>? {
        val keyring = decodeKeyring(keyringEnvelopeBase64) ?: return null
        val wrappedBytes = try {
            Base64.decode(keyring.masterWrappedDek)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val dek = SyncCrypto.open(masterWrappingKey, MASTER_WRAP_AAD.encodeToByteArray(), wrappedBytes)
            ?: return null
        return dek to keyring
    }

    fun unwrapKeyringWithRecovery(
        keyringEnvelopeBase64: String,
        recoveryWrappingKey: ByteArray,
    ): Pair<ByteArray, SyncKeyring>? {
        val keyring = decodeKeyring(keyringEnvelopeBase64) ?: return null
        val wrappedBytes = try {
            Base64.decode(keyring.recoveryWrappedDek)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val dek = SyncCrypto.open(recoveryWrappingKey, RECOVERY_WRAP_AAD.encodeToByteArray(), wrappedBytes)
            ?: return null
        return dek to keyring
    }

    fun rewrapKeyringWithNewMaster(
        existingKeyring: SyncKeyring,
        dek: ByteArray,
        newMasterWrappingKey: ByteArray,
    ): String {
        val masterWrapped = Base64.encode(
            SyncCrypto.seal(newMasterWrappingKey, MASTER_WRAP_AAD.encodeToByteArray(), dek),
        )
        val updated = existingKeyring.copy(masterWrappedDek = masterWrapped)
        return Base64.encode(json.encodeToString(updated).encodeToByteArray())
    }

    private fun decodeKeyring(keyringEnvelopeBase64: String): SyncKeyring? =
        try {
            val rawJson = Base64.decode(keyringEnvelopeBase64).decodeToString()
            json.decodeFromString<SyncKeyring>(rawJson)
        } catch (_: Exception) {
            null
        }
}
