package com.maxrave.data.simpconnect

import com.maxrave.domain.data.model.simpconnect.DiscoveredDeviceDomain
import com.maxrave.domain.data.model.simpconnect.RemotePlaybackStateDomain
import com.maxrave.domain.data.model.simpconnect.SimpConnectAuthRequestDomain
import com.maxrave.domain.data.model.simpconnect.SimpConnectStatusDomain
import com.maxrave.domain.data.model.simpconnect.TrackSummaryDomain
import com.maxrave.domain.repository.SimpConnectRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * iOS has no SimpConnect transport yet (the service module only targets Android and the JVM).
 *
 * The binding still has to exist: SharedViewModel injects [SimpConnectRepository] eagerly on every
 * platform, so leaving the definition out made the app crash at startup on iOS.
 */
class IosSimpConnectRepository(
    private val deviceName: String = "iPhone",
) : SimpConnectRepository {
    private val _localDevice =
        MutableStateFlow(
            DiscoveredDeviceDomain(
                deviceId = "ios-local",
                deviceName = deviceName,
                platform = "ios",
                hostAddress = "127.0.0.1",
                tcpPort = 0,
                isLocal = true,
            ),
        )
    override val localDevice: StateFlow<DiscoveredDeviceDomain> = _localDevice.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDeviceDomain>>(emptyList())
    override val discoveredDevices: StateFlow<List<DiscoveredDeviceDomain>> = _discoveredDevices.asStateFlow()

    private val _pendingDevice = MutableStateFlow<DiscoveredDeviceDomain?>(null)
    override val pendingDevice: StateFlow<DiscoveredDeviceDomain?> = _pendingDevice.asStateFlow()

    private val _activeRemoteDevice = MutableStateFlow<DiscoveredDeviceDomain?>(null)
    override val activeRemoteDevice: StateFlow<DiscoveredDeviceDomain?> = _activeRemoteDevice.asStateFlow()

    private val _remotePlaybackState = MutableStateFlow<RemotePlaybackStateDomain?>(null)
    override val remotePlaybackState: StateFlow<RemotePlaybackStateDomain?> = _remotePlaybackState.asStateFlow()

    private val _connectionStatus = MutableStateFlow(SimpConnectStatusDomain.IDLE)
    override val connectionStatus: StateFlow<SimpConnectStatusDomain> = _connectionStatus.asStateFlow()

    private val _incomingAuthRequest = MutableStateFlow<SimpConnectAuthRequestDomain?>(null)
    override val incomingAuthRequest: StateFlow<SimpConnectAuthRequestDomain?> = _incomingAuthRequest.asStateFlow()

    override fun start() = Unit

    override fun refreshDiscovery() = Unit

    override fun stopDiscovery() = Unit

    override fun connectToDevice(device: DiscoveredDeviceDomain) = Unit

    override fun disconnect() = Unit

    override fun sendPlayPause() = Unit

    override fun sendPlay() = Unit

    override fun sendPause() = Unit

    override fun sendNext() = Unit

    override fun sendPrevious() = Unit

    override fun sendSeekTo(positionMs: Long) = Unit

    override fun sendSetVolume(volume: Float) = Unit

    override fun sendSetShuffle(enabled: Boolean) = Unit

    override fun sendSetRepeat(mode: Int) = Unit

    override fun sendPlayTrack(
        videoId: String,
        playlistId: String?,
        playlistName: String?,
        queue: List<TrackSummaryDomain>,
        startIndex: Int,
        playWhenReady: Boolean,
        positionMs: Long,
    ) = Unit

    override fun requestHandoff() = Unit

    override fun handoffToRemote(device: DiscoveredDeviceDomain) = Unit

    override fun transferToDevice(device: DiscoveredDeviceDomain) = Unit
}
