package com.maxrave.data.repository

import com.maxrave.data.db.datasource.AnalyticsDatasource
import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.data.repository.sync.SyncPayloadStore
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.CommonRepository
import com.maxrave.domain.repository.SyncDeviceInfo
import com.maxrave.domain.repository.SyncRepository
import com.maxrave.domain.repository.SyncStatus
import com.maxrave.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.simpmusic.sync.SyncBlob
import org.simpmusic.sync.SyncClient
import org.simpmusic.sync.SyncCredentials
import org.simpmusic.sync.SyncCrypto
import org.simpmusic.sync.SyncEnvelopeHelper
import org.simpmusic.sync.SyncRecoveryRequest
import org.simpmusic.sync.SyncRequest
import org.simpmusic.sync.SyncSession
import org.simpmusic.sync.SyncWsNotification
import kotlin.concurrent.Volatile
import kotlin.time.Clock

internal class SyncRepositoryImpl(
    private val dataStore: DataStoreManager,
    private val localDataSource: LocalDataSource,
    analyticsDatasource: AnalyticsDatasource,
    commonRepository: CommonRepository,
) : SyncRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val payloadStore = SyncPayloadStore(dataStore, localDataSource, analyticsDatasource, commonRepository)
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    private val syncMutex = Mutex()
    private val clientMutex = Mutex()
    private var cachedUrl: String? = null
    private var cachedClient: SyncClient? = null

    private var realtimeJob: Job? = null
    private var periodicJob: Job? = null
    private var localObserverJob: Job? = null
    private var debounceSyncJob: Job? = null

    @Volatile
    private var suppressLocalObserverUntilMs: Long = 0L

    private val _status = MutableStateFlow(SyncStatus())
    override val status: StateFlow<SyncStatus> = _status

    companion object {
        private const val TAG = "SimpSyncRepo"
        private const val PUSH_BATCH_SIZE = 150
        private const val PERIODIC_SYNC_MS = 5 * 60 * 1000L // 5 minutes
        private const val LOCAL_CHANGE_DEBOUNCE_MS = 1_500L
        private const val WS_PULL_DEBOUNCE_MS = 300L
    }

    init {
        scope.launch {
            restorePersistedState()
        }
    }

    private suspend fun restorePersistedState() {
        val url = dataStore.syncServerUrl.first().trim().trimEnd('/')
        val enabled = dataStore.syncEnabled.first()
        val session = readSession()
        val dataKey = readDataKey()
        val email = dataStore.syncEmail.first()
        val deviceName = dataStore.syncDeviceName.first()
        val recoveryKey = dataStore.syncRecoveryKey.first()
        val lastSynced = dataStore.syncLastSyncedAt.first()

        val authenticated = session != null && dataKey != null
        _status.value = SyncStatus(
            enabled = enabled && authenticated,
            configured = url.isNotBlank(),
            authenticated = authenticated,
            serverUrl = url,
            email = email,
            deviceName = deviceName,
            recoveryKey = recoveryKey,
            lastSyncedAt = lastSynced,
        )

        if (authenticated && enabled) {
            startBackgroundWorkers()
            scope.launch {
                synchronize()
                refreshDevices()
            }
        }
    }

    override suspend fun configure(serverUrl: String, enabled: Boolean) {
        val normalizedUrl = serverUrl.trim().trimEnd('/')
        dataStore.setSyncServerUrl(normalizedUrl)
        dataStore.setSyncEnabled(enabled)
        val authenticated = readSession() != null && readDataKey() != null
        _status.value = _status.value.copy(
            serverUrl = normalizedUrl,
            enabled = enabled && authenticated,
            configured = normalizedUrl.isNotBlank(),
            error = null,
        )
        if (enabled && authenticated) {
            startBackgroundWorkers()
            scope.launch { synchronize() }
        } else {
            stopBackgroundWorkers()
        }
    }

    override suspend fun register(
        email: String,
        password: String,
        deviceName: String,
    ): String =
        withContext(Dispatchers.IO) {
            val cleanEmail = email.trim().lowercase()
            val cleanDevice = deviceName.trim().ifBlank { "SimpMusic" }
            val url = dataStore.syncServerUrl.first().trim().trimEnd('/')
            require(url.isNotBlank()) { "Sync server URL is required" }

            _status.value = _status.value.copy(syncing = true, error = null)
            try {
                val master = withContext(Dispatchers.Default) {
                    SyncCrypto.deriveMasterCredentials(cleanEmail, password)
                }
                val recoveryKey = SyncCrypto.generateRecoveryKey()
                val recovery = withContext(Dispatchers.Default) {
                    SyncCrypto.deriveRecovery(recoveryKey)
                }
                val dek = SyncCrypto.newKey()

                val client = getClient(url)
                val session = client.register(
                    SyncCredentials(
                        email = cleanEmail,
                        authProof = master.authProof,
                        deviceName = cleanDevice,
                        recoveryKeyHash = recovery.recoveryKeyHash,
                    ),
                )

                val keyringEnvelope = SyncEnvelopeHelper.createKeyringEnvelope(
                    dek = dek,
                    masterWrappingKey = master.wrappingKey,
                    recoveryWrappingKey = recovery.wrappingKey,
                )
                val keyringSync = client.sync(
                    token = session.token,
                    request = SyncRequest(
                        changes = listOf(
                            SyncBlob(
                                entityType = SyncEnvelopeHelper.KEYRING_ENTITY_TYPE,
                                entityId = SyncEnvelopeHelper.KEYRING_ENTITY_ID,
                                envelope = keyringEnvelope,
                                deleted = false,
                            ),
                        ),
                        cursor = 0L,
                    ),
                )

                payloadStore.clearLocalState()
                persistSessionAndKeys(
                    session = session,
                    dek = dek,
                    email = cleanEmail,
                    deviceName = cleanDevice,
                    recoveryKey = recoveryKey,
                    cursor = keyringSync.cursor,
                )
                _status.value = _status.value.copy(
                    enabled = true,
                    configured = true,
                    authenticated = true,
                    syncing = false,
                    email = cleanEmail,
                    deviceName = cleanDevice,
                    recoveryKey = recoveryKey,
                    error = null,
                )
                startBackgroundWorkers()
                scope.launch {
                    synchronize()
                    refreshDevices()
                }
                recoveryKey
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val msg = cleanErrorMessage(e)
                _status.value = _status.value.copy(syncing = false, error = msg)
                throw IllegalStateException(msg, e)
            }
        }

    override suspend fun login(
        email: String,
        password: String,
        deviceName: String,
    ) = withContext(Dispatchers.IO) {
        val cleanEmail = email.trim().lowercase()
        val cleanDevice = deviceName.trim().ifBlank { "SimpMusic" }
        val url = dataStore.syncServerUrl.first().trim().trimEnd('/')
        require(url.isNotBlank()) { "Sync server URL is required" }

        _status.value = _status.value.copy(syncing = true, error = null)
        try {
            val master = withContext(Dispatchers.Default) {
                SyncCrypto.deriveMasterCredentials(cleanEmail, password)
            }
            val client = getClient(url)
            val session = client.login(
                SyncCredentials(
                    email = cleanEmail,
                    authProof = master.authProof,
                    deviceName = cleanDevice,
                ),
            )

            // Locate the keyring blob on the server to unwrap the existing DEK.
            val keyringBlob = fetchKeyringBlob(client, session.token)
            val dek: ByteArray
            var newRecoveryKey: String? = dataStore.syncRecoveryKey.first()

            if (keyringBlob != null) {
                val unwrapped = SyncEnvelopeHelper.unwrapKeyringWithMaster(keyringBlob.envelope, master.wrappingKey)
                    ?: error("Could not unlock encryption keyring with the provided master password")
                dek = unwrapped.first
            } else {
                // Legacy account without keyring: initialize one now.
                val generatedRecoveryKey = SyncCrypto.generateRecoveryKey()
                val recovery = withContext(Dispatchers.Default) {
                    SyncCrypto.deriveRecovery(generatedRecoveryKey)
                }
                dek = SyncCrypto.newKey()
                newRecoveryKey = generatedRecoveryKey
                val keyringEnvelope = SyncEnvelopeHelper.createKeyringEnvelope(
                    dek = dek,
                    masterWrappingKey = master.wrappingKey,
                    recoveryWrappingKey = recovery.wrappingKey,
                )
                client.sync(
                    token = session.token,
                    request = SyncRequest(
                        changes = listOf(
                            SyncBlob(
                                entityType = SyncEnvelopeHelper.KEYRING_ENTITY_TYPE,
                                entityId = SyncEnvelopeHelper.KEYRING_ENTITY_ID,
                                envelope = keyringEnvelope,
                            ),
                        ),
                        cursor = 0L,
                    ),
                )
            }

            // Reset local state and cursor to 0 so the initial synchronize() pulls and merges all cloud data first!
            payloadStore.clearLocalState()
            persistSessionAndKeys(
                session = session,
                dek = dek,
                email = cleanEmail,
                deviceName = cleanDevice,
                recoveryKey = newRecoveryKey,
                cursor = 0L,
            )
            _status.value = _status.value.copy(
                enabled = true,
                configured = true,
                authenticated = true,
                syncing = false,
                email = cleanEmail,
                deviceName = cleanDevice,
                recoveryKey = newRecoveryKey,
                error = null,
            )
            startBackgroundWorkers()
            scope.launch {
                synchronize()
                refreshDevices()
            }
            Unit
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val msg = cleanErrorMessage(e)
            _status.value = _status.value.copy(syncing = false, error = msg)
            throw IllegalStateException(msg, e)
        }
    }

    override suspend fun recover(
        email: String,
        recoveryKey: String,
        newPassword: String,
        deviceName: String,
    ) = withContext(Dispatchers.IO) {
        val cleanEmail = email.trim().lowercase()
        val cleanDevice = deviceName.trim().ifBlank { "SimpMusic" }
        val cleanRecoveryKey = recoveryKey.trim().uppercase()
        val url = dataStore.syncServerUrl.first().trim().trimEnd('/')
        require(url.isNotBlank()) { "Sync server URL is required" }

        _status.value = _status.value.copy(syncing = true, error = null)
        try {
            val recovery = withContext(Dispatchers.Default) {
                SyncCrypto.deriveRecovery(cleanRecoveryKey)
            }
            val newMaster = withContext(Dispatchers.Default) {
                SyncCrypto.deriveMasterCredentials(cleanEmail, newPassword)
            }
            val client = getClient(url)
            val session = client.recover(
                SyncRecoveryRequest(
                    email = cleanEmail,
                    recoveryKeyHash = recovery.recoveryKeyHash,
                    newAuthProof = newMaster.authProof,
                    deviceName = cleanDevice,
                ),
            )

            // Unwrap the existing DEK using the Recovery Key and re-wrap it with the new Master Password!
            val keyringBlob = fetchKeyringBlob(client, session.token)
            val dek: ByteArray
            val updatedKeyringEnvelope = if (keyringBlob != null) {
                val unwrapped = SyncEnvelopeHelper.unwrapKeyringWithRecovery(keyringBlob.envelope, recovery.wrappingKey)
                    ?: error("Invalid recovery key for encrypted keyring")
                dek = unwrapped.first
                SyncEnvelopeHelper.rewrapKeyringWithNewMaster(
                    existingKeyring = unwrapped.second,
                    dek = dek,
                    newMasterWrappingKey = newMaster.wrappingKey,
                )
            } else {
                dek = SyncCrypto.newKey()
                SyncEnvelopeHelper.createKeyringEnvelope(
                    dek = dek,
                    masterWrappingKey = newMaster.wrappingKey,
                    recoveryWrappingKey = recovery.wrappingKey,
                )
            }

            client.sync(
                token = session.token,
                request = SyncRequest(
                    changes = listOf(
                        SyncBlob(
                            entityType = SyncEnvelopeHelper.KEYRING_ENTITY_TYPE,
                            entityId = SyncEnvelopeHelper.KEYRING_ENTITY_ID,
                            envelope = updatedKeyringEnvelope,
                        ),
                    ),
                    cursor = 0L,
                ),
            )

            payloadStore.clearLocalState()
            persistSessionAndKeys(
                session = session,
                dek = dek,
                email = cleanEmail,
                deviceName = cleanDevice,
                recoveryKey = cleanRecoveryKey,
                cursor = 0L,
            )
            _status.value = _status.value.copy(
                enabled = true,
                configured = true,
                authenticated = true,
                syncing = false,
                email = cleanEmail,
                deviceName = cleanDevice,
                recoveryKey = cleanRecoveryKey,
                error = null,
            )
            startBackgroundWorkers()
            scope.launch {
                synchronize()
                refreshDevices()
            }
            Unit
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val msg = cleanErrorMessage(e)
            _status.value = _status.value.copy(syncing = false, error = msg)
            throw IllegalStateException(msg, e)
        }
    }

    override suspend fun logout() =
        withContext(Dispatchers.IO) {
            stopBackgroundWorkers()
            val session = readSession()
            val url = dataStore.syncServerUrl.first().trim().trimEnd('/')
            if (session != null && url.isNotBlank()) {
                runCatching { getClient(url).logout(session.token) }
            }
            payloadStore.clearLocalState()
            dataStore.setSyncSession(null)
            dataStore.setSyncDataKey(null)
            dataStore.setSyncCursor(0L)
            dataStore.setSyncEnabled(false)
            dataStore.setSyncRecoveryKey(null)
            _status.value = _status.value.copy(
                enabled = false,
                authenticated = false,
                realtimeConnected = false,
                syncing = false,
                recoveryKey = null,
                devices = emptyList(),
                error = null,
            )
        }

    override suspend fun synchronize() {
        syncMutex.withLock {
            val url = dataStore.syncServerUrl.first().trim().trimEnd('/')
            val session = readSession()
            val dek = readDataKey()
            if (url.isBlank() || session == null || dek == null) {
                _status.value = _status.value.copy(error = "Sync is not configured or logged in")
                return
            }

            _status.value = _status.value.copy(syncing = true, error = null)
            try {
                val client = getClient(url)
                var cursor = dataStore.syncCursor.first()
                var localState = payloadStore.loadLocalState()
                val mutableHashes = localState.entityHashes.toMutableMap()
                var newestAnalyticsIso = localState.lastAnalyticsTimestampIso

                // 1. Paginated Pull-before-Push
                do {
                    val pullResponse = client.sync(
                        token = session.token,
                        request = SyncRequest(changes = emptyList(), cursor = cursor),
                    )
                    if (pullResponse.changes.isNotEmpty()) {
                        suppressLocalObserverUntilMs = Clock.System.now().toEpochMilliseconds() + 3_000L
                        val remoteAnalyticsIso = payloadStore.applyRemoteChanges(
                            dek = dek,
                            changes = pullResponse.changes,
                            currentHashes = mutableHashes,
                        )
                        suppressLocalObserverUntilMs = Clock.System.now().toEpochMilliseconds() + 1_000L
                        if (remoteAnalyticsIso != null &&
                            (newestAnalyticsIso == null || remoteAnalyticsIso > newestAnalyticsIso)
                        ) {
                            newestAnalyticsIso = remoteAnalyticsIso
                        }
                    }
                    cursor = pullResponse.cursor
                    dataStore.setSyncCursor(cursor)
                } while (pullResponse.hasMore)

                localState = localState.copy(
                    entityHashes = mutableHashes,
                    lastAnalyticsTimestampIso = newestAnalyticsIso,
                )

                // 2. Compute Local Diff & Batched Push
                val (outgoing, updatedState) = payloadStore.buildPushChanges(dek, localState)
                if (outgoing.isNotEmpty()) {
                    for (batch in outgoing.chunked(PUSH_BATCH_SIZE)) {
                        val pushResponse = client.sync(
                            token = session.token,
                            request = SyncRequest(changes = batch, cursor = cursor),
                        )
                        if (pushResponse.changes.isNotEmpty()) {
                            suppressLocalObserverUntilMs = Clock.System.now().toEpochMilliseconds() + 3_000L
                            payloadStore.applyRemoteChanges(
                                dek = dek,
                                changes = pushResponse.changes,
                                currentHashes = mutableHashes,
                            )
                            suppressLocalObserverUntilMs = Clock.System.now().toEpochMilliseconds() + 1_000L
                        }
                        cursor = pushResponse.cursor
                        dataStore.setSyncCursor(cursor)
                    }
                }

                payloadStore.saveLocalState(updatedState)
                val nowMs = Clock.System.now().toEpochMilliseconds()
                dataStore.setSyncLastSyncedAt(nowMs)
                _status.value = _status.value.copy(
                    syncing = false,
                    lastSyncedAt = nowMs,
                    error = null,
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Logger.e(TAG, "Synchronization error: ${e.message}")
                val msg = cleanErrorMessage(e)
                if (msg.contains("401")) {
                    // Session was revoked or expired
                    stopBackgroundWorkers()
                    dataStore.setSyncSession(null)
                    dataStore.setSyncEnabled(false)
                    _status.value = _status.value.copy(
                        syncing = false,
                        enabled = false,
                        authenticated = false,
                        realtimeConnected = false,
                        error = "Session expired or revoked. Please sign in again.",
                    )
                } else {
                    _status.value = _status.value.copy(syncing = false, error = msg)
                }
            }
        }
    }

    override suspend fun refreshDevices() =
        withContext(Dispatchers.IO) {
            val url = dataStore.syncServerUrl.first().trim().trimEnd('/')
            val session = readSession() ?: return@withContext
            if (url.isBlank()) return@withContext
            try {
                val list = getClient(url).devices(session.token).map {
                    SyncDeviceInfo(
                        id = it.id,
                        deviceName = it.deviceName,
                        createdAt = it.createdAt,
                        lastSeenAt = it.lastSeenAt,
                        current = it.current || it.id == session.deviceId,
                    )
                }
                _status.value = _status.value.copy(devices = list)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Logger.w(TAG, "Failed to refresh devices: ${e.message}")
            }
        }

    override suspend fun revokeDevice(deviceId: String) =
        withContext(Dispatchers.IO) {
            val url = dataStore.syncServerUrl.first().trim().trimEnd('/')
            val session = readSession() ?: return@withContext
            if (url.isBlank()) return@withContext
            try {
                getClient(url).revokeDevice(session.token, deviceId)
                refreshDevices()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _status.value = _status.value.copy(error = cleanErrorMessage(e))
            }
        }

    override fun clearError() {
        _status.value = _status.value.copy(error = null)
    }

    private fun startBackgroundWorkers() {
        stopBackgroundWorkers()
        realtimeJob = scope.launch {
            var backoffMs = 2_000L
            while (isActive && dataStore.syncEnabled.first()) {
                val url = dataStore.syncServerUrl.first().trim().trimEnd('/')
                val session = readSession()
                if (url.isBlank() || session == null) break
                try {
                    val client = getClient(url)
                    client.observeNotifications(
                        token = session.token,
                        onConnected = {
                            _status.value = _status.value.copy(realtimeConnected = true)
                            backoffMs = 2_000L
                        },
                        onMessage = { raw ->
                            val notif = runCatching {
                                json.decodeFromString<SyncWsNotification>(raw)
                            }.getOrNull()
                            val currentCursor = dataStore.syncCursor.first()
                            if (notif == null || notif.version > currentCursor) {
                                scheduleDebouncedSync(WS_PULL_DEBOUNCE_MS)
                            }
                        },
                    )
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Logger.w(TAG, "WebSocket disconnected, retrying in ${backoffMs}ms: ${e.message}")
                } finally {
                    _status.value = _status.value.copy(realtimeConnected = false)
                }
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
            }
        }

        localObserverJob = scope.launch {
            localDataSource.observePrimaryLibrarySignature()
                .drop(1)
                .collect {
                    val now = Clock.System.now().toEpochMilliseconds()
                    if (now >= suppressLocalObserverUntilMs &&
                        dataStore.syncEnabled.first() &&
                        readSession() != null
                    ) {
                        scheduleDebouncedSync(LOCAL_CHANGE_DEBOUNCE_MS)
                    }
                }
        }

        periodicJob = scope.launch {
            while (isActive) {
                delay(PERIODIC_SYNC_MS)
                if (dataStore.syncEnabled.first() && readSession() != null) {
                    synchronize()
                }
            }
        }
    }

    private fun stopBackgroundWorkers() {
        realtimeJob?.cancel()
        realtimeJob = null
        localObserverJob?.cancel()
        localObserverJob = null
        periodicJob?.cancel()
        periodicJob = null
        debounceSyncJob?.cancel()
        debounceSyncJob = null
        _status.value = _status.value.copy(realtimeConnected = false)
    }

    private fun scheduleDebouncedSync(delayMs: Long = LOCAL_CHANGE_DEBOUNCE_MS) {
        debounceSyncJob?.cancel()
        debounceSyncJob = scope.launch {
            delay(delayMs)
            synchronize()
        }
    }

    private suspend fun fetchKeyringBlob(
        client: SyncClient,
        token: String,
    ): SyncBlob? {
        var cursor = 0L
        do {
            val response = client.sync(token, SyncRequest(changes = emptyList(), cursor = cursor))
            val match = response.changes.firstOrNull {
                it.entityType == SyncEnvelopeHelper.KEYRING_ENTITY_TYPE &&
                    it.entityId == SyncEnvelopeHelper.KEYRING_ENTITY_ID &&
                    !it.deleted
            }
            if (match != null) return match
            cursor = response.cursor
        } while (response.hasMore)
        return null
    }

    private suspend fun persistSessionAndKeys(
        session: SyncSession,
        dek: ByteArray,
        email: String,
        deviceName: String,
        recoveryKey: String?,
        cursor: Long,
    ) {
        dataStore.setSyncSession(json.encodeToString(session))
        dataStore.setSyncDataKey(SyncEnvelopeHelper.encodeKey(dek))
        dataStore.setSyncEmail(email)
        dataStore.setSyncDeviceName(deviceName)
        if (recoveryKey != null) {
            dataStore.setSyncRecoveryKey(recoveryKey)
        }
        dataStore.setSyncCursor(cursor)
        dataStore.setSyncEnabled(true)
    }

    private suspend fun getClient(url: String): SyncClient =
        clientMutex.withLock {
            val existing = cachedClient
            if (existing != null && cachedUrl == url) {
                return@withLock existing
            }
            existing?.close()
            val created = SyncClient(url)
            cachedUrl = url
            cachedClient = created
            created
        }

    private suspend fun readSession(): SyncSession? =
        dataStore.syncSession.first()?.let {
            runCatching { json.decodeFromString<SyncSession>(it) }.getOrNull()
        }

    private suspend fun readDataKey(): ByteArray? =
        dataStore.syncDataKey.first()?.let { SyncEnvelopeHelper.decodeKey(it) }

    private fun cleanErrorMessage(e: Exception): String {
        val raw = e.message ?: "Synchronization failed"
        return raw.lineSequence().firstOrNull()?.take(180) ?: raw
    }
}
