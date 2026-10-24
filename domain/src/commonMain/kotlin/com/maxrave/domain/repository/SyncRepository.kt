package com.maxrave.domain.repository

import kotlinx.coroutines.flow.StateFlow

data class SyncDeviceInfo(
    val id: String,
    val deviceName: String,
    val createdAt: String,
    val lastSeenAt: String,
    val current: Boolean = false,
)

data class SyncStatus(
    val enabled: Boolean = false,
    val configured: Boolean = false,
    val authenticated: Boolean = false,
    val realtimeConnected: Boolean = false,
    val syncing: Boolean = false,
    val serverUrl: String = "https://sync.muriloscolari.me",
    val email: String? = null,
    val deviceName: String? = null,
    val recoveryKey: String? = null,
    val lastSyncedAt: Long = 0L,
    val devices: List<SyncDeviceInfo> = emptyList(),
    val error: String? = null,
)

interface SyncRepository {
    val status: StateFlow<SyncStatus>

    suspend fun configure(serverUrl: String, enabled: Boolean)

    suspend fun register(email: String, password: String, deviceName: String): String

    suspend fun login(email: String, password: String, deviceName: String)

    suspend fun recover(email: String, recoveryKey: String, newPassword: String, deviceName: String)

    suspend fun logout()

    suspend fun synchronize()

    suspend fun refreshDevices()

    suspend fun revokeDevice(deviceId: String)

    fun clearError()
}
