package com.maxrave.data.repository

import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.SyncRepository
import com.maxrave.domain.repository.SyncStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.simpmusic.sync.SyncClient
import org.simpmusic.sync.SyncCredentials
import org.simpmusic.sync.SyncSession
import org.simpmusic.sync.SyncRecoveryRequest

class SyncRepositoryImpl(
    private val dataStore: DataStoreManager,
) : SyncRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private val _status = MutableStateFlow(SyncStatus())
    override val status: StateFlow<SyncStatus> = _status

    override suspend fun configure(serverUrl: String, enabled: Boolean) {
        dataStore.setSyncServerUrl(serverUrl)
        dataStore.setSyncEnabled(enabled)
        _status.value = _status.value.copy(enabled = enabled, configured = serverUrl.isNotBlank(), error = null)
    }

    override suspend fun register(email: String, authProof: String, deviceName: String) {
        authenticate(email, authProof, deviceName, register = true)
    }

    override suspend fun login(email: String, authProof: String, deviceName: String) {
        authenticate(email, authProof, deviceName, register = false)
    }

    override suspend fun recover(email: String, recoveryKeyHash: String, newAuthProof: String, deviceName: String) {
        val url = dataStore.syncServerUrl.first().trimEnd('/')
        require(url.isNotBlank()) { "Sync server URL is required" }
        val session = SyncClient(url).recover(SyncRecoveryRequest(email.trim(), recoveryKeyHash, newAuthProof, deviceName.trim()))
        dataStore.setSyncSession(json.encodeToString(session))
        dataStore.setSyncEnabled(true)
        _status.value = SyncStatus(enabled = true, configured = true)
    }

    override suspend fun logout() {
        val session = readSession() ?: return
        val url = dataStore.syncServerUrl.first()
        if (url.isNotBlank()) SyncClient(url).logout(session.token)
        dataStore.setSyncSession(null)
        _status.value = _status.value.copy(enabled = false, configured = url.isNotBlank(), error = null)
    }

    override suspend fun synchronize() {
        val url = dataStore.syncServerUrl.first()
        val session = readSession()
        if (url.isBlank() || session == null) {
            _status.value = _status.value.copy(error = "Sync is not configured")
            return
        }
        _status.value = _status.value.copy(syncing = true, error = null)
        try {
            SyncClient(url).sync(session.token, org.simpmusic.sync.SyncRequest())
        } catch (error: Exception) {
            _status.value = _status.value.copy(error = error.message ?: "Synchronization failed")
        } finally {
            _status.value = _status.value.copy(syncing = false)
        }
    }

    private suspend fun authenticate(email: String, authProof: String, deviceName: String, register: Boolean) {
        val url = dataStore.syncServerUrl.first().trimEnd('/')
        require(url.isNotBlank()) { "Sync server URL is required" }
        val client = SyncClient(url)
        val credentials = SyncCredentials(email.trim(), authProof, deviceName.trim())
        val session = if (register) client.register(credentials) else client.login(credentials)
        dataStore.setSyncSession(json.encodeToString(session))
        dataStore.setSyncEnabled(true)
        _status.value = SyncStatus(enabled = true, configured = true)
    }

    private suspend fun readSession(): SyncSession? =
        dataStore.syncSession.first()?.let { json.decodeFromString<SyncSession>(it) }
}
