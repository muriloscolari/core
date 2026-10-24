package org.simpmusic.sync

import kotlinx.serialization.Serializable

@Serializable
data class SyncCredentials(
    val email: String,
    val authProof: String,
    val deviceName: String,
    val recoveryKeyHash: String? = null,
)

@Serializable
data class SyncRecoveryRequest(
    val email: String,
    val recoveryKeyHash: String,
    val newAuthProof: String,
    val deviceName: String,
)

@Serializable
data class SyncSession(
    val token: String,
    val expiresAt: String,
    val deviceId: String,
)

@Serializable
data class SyncDevice(
    val id: String,
    val deviceName: String,
    val createdAt: String,
    val lastSeenAt: String,
    val current: Boolean = false,
)

@Serializable
data class SyncBlob(
    val entityType: String,
    val entityId: String,
    val envelope: String,
    val version: Long = 0,
    val deleted: Boolean = false,
    val updatedAt: String? = null,
)

@Serializable
data class SyncRequest(
    val changes: List<SyncBlob> = emptyList(),
    val cursor: Long = 0,
)

@Serializable
data class SyncResponse(
    val changes: List<SyncBlob> = emptyList(),
    val cursor: Long = 0,
    val hasMore: Boolean = false,
)

@Serializable
data class SyncKeyring(
    val version: Int = 1,
    val masterWrappedDek: String,
    val recoveryWrappedDek: String,
)

@Serializable
data class SyncWsNotification(
    val entityType: String = "",
    val entityId: String = "",
    val version: Long = 0,
)

data class DerivedCredentials(
    val authProof: String,
    val wrappingKey: ByteArray,
)

data class DerivedRecovery(
    val recoveryKeyHash: String,
    val wrappingKey: ByteArray,
)
