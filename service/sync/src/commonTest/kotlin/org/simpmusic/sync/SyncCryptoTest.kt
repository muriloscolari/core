package org.simpmusic.sync

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
}
