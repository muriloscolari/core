package org.simpmusic.sync

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncCryptoTest {
    @Test
    fun roundTripAndAuthentication() {
        val key = SyncCrypto.newKey()
        val aad = "settings/settings-v1".encodeToByteArray()
        val envelope = SyncCrypto.seal(key, aad, "opaque settings".encodeToByteArray())
        assertContentEquals("opaque settings".encodeToByteArray(), SyncCrypto.open(key, aad, envelope))
        assertNull(SyncCrypto.open(key, "wrong".encodeToByteArray(), envelope))
        assertNotNull(SyncCrypto.open(key, aad, envelope))
    }

    @Test
    fun keyringMasterAndRecoveryRotation() {
        val master1 = SyncCrypto.deriveMasterCredentials("User@Example.com ", "correct-horse-battery-staple")
        val recoveryKey = SyncCrypto.generateRecoveryKey()
        val recovery = SyncCrypto.deriveRecovery(recoveryKey)

        assertTrue(master1.authProof.length >= 32)
        assertTrue(recovery.recoveryKeyHash.length >= 32)

        val dek = SyncCrypto.newKey()
        val keyringEnvelope = SyncEnvelopeHelper.createKeyringEnvelope(
            dek = dek,
            masterWrappingKey = master1.wrappingKey,
            recoveryWrappingKey = recovery.wrappingKey,
        )

        // Unwrap with master password on another device
        val unwrappedByMaster = SyncEnvelopeHelper.unwrapKeyringWithMaster(keyringEnvelope, master1.wrappingKey)
        assertNotNull(unwrappedByMaster)
        assertContentEquals(dek, unwrappedByMaster.first)

        // Recover with Recovery Key and rotate to a new master password without losing DEK!
        val unwrappedByRecovery = SyncEnvelopeHelper.unwrapKeyringWithRecovery(keyringEnvelope, recovery.wrappingKey)
        assertNotNull(unwrappedByRecovery)
        assertContentEquals(dek, unwrappedByRecovery.first)

        val master2 = SyncCrypto.deriveMasterCredentials("user@example.com", "new-password-after-recovery")
        val rotatedEnvelope = SyncEnvelopeHelper.rewrapKeyringWithNewMaster(
            existingKeyring = unwrappedByRecovery.second,
            dek = unwrappedByRecovery.first,
            newMasterWrappingKey = master2.wrappingKey,
        )

        assertNull(SyncEnvelopeHelper.unwrapKeyringWithMaster(rotatedEnvelope, master1.wrappingKey))
        val unwrappedWithNewMaster = SyncEnvelopeHelper.unwrapKeyringWithMaster(rotatedEnvelope, master2.wrappingKey)
        assertNotNull(unwrappedWithNewMaster)
        assertContentEquals(dek, unwrappedWithNewMaster.first)
        assertEquals(64, SyncCrypto.sha256Hex(dek).length)
    }
}
