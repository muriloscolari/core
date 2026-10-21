package com.maxrave.domain.repository

import kotlinx.coroutines.flow.StateFlow

data class SyncStatus(
    val enabled: Boolean = false,
    val configured: Boolean = false,
    val syncing: Boolean = false,
    val error: String? = null,
)

interface SyncRepository {
    val status: StateFlow<SyncStatus>

    suspend fun configure(serverUrl: String, enabled: Boolean)

    suspend fun register(email: String, authProof: String, deviceName: String)

    suspend fun login(email: String, authProof: String, deviceName: String)

    suspend fun logout()

    suspend fun synchronize()
}
