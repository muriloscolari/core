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
)
