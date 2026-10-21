package com.maxrave.domain.repository

import com.maxrave.domain.data.model.simpconnect.DiscoveredDeviceDomain
import com.maxrave.domain.data.model.simpconnect.RemotePlaybackStateDomain
import com.maxrave.domain.data.model.simpconnect.SimpConnectAuthRequestDomain
import com.maxrave.domain.data.model.simpconnect.SimpConnectStatusDomain
import com.maxrave.domain.data.model.simpconnect.TrackSummaryDomain
import kotlinx.coroutines.flow.StateFlow

interface SimpConnectRepository {
    val localDevice: StateFlow<DiscoveredDeviceDomain>
    val discoveredDevices: StateFlow<List<DiscoveredDeviceDomain>>

    /** Device the user picked, while the handshake is still connecting/awaiting approval. */
    val pendingDevice: StateFlow<DiscoveredDeviceDomain?>

    /** Device successfully connected as a remote controller target. */
    val activeRemoteDevice: StateFlow<DiscoveredDeviceDomain?>
    val remotePlaybackState: StateFlow<RemotePlaybackStateDomain?>
    val connectionStatus: StateFlow<SimpConnectStatusDomain>
    val incomingAuthRequest: StateFlow<SimpConnectAuthRequestDomain?>

    fun start()
    fun refreshDiscovery()
    fun stopDiscovery()

    fun connectToDevice(device: DiscoveredDeviceDomain)
    fun disconnect()

    fun sendPlayPause()
    fun sendPlay()
    fun sendPause()
    fun sendNext()
    fun sendPrevious()
    fun sendSeekTo(positionMs: Long)
    fun sendSetVolume(volume: Float)
    fun sendSetShuffle(enabled: Boolean)
    fun sendSetRepeat(mode: Int)
    fun sendPlayTrack(
        videoId: String,
        playlistId: String? = null,
        playlistName: String? = null,
        queue: List<TrackSummaryDomain> = emptyList(),
        startIndex: Int = 0,
        playWhenReady: Boolean = true,
        positionMs: Long = 0L,
    )

    /** Pulls playback from the connected remote device to the local player. */
    fun requestHandoff()

    /** Pushes current local playback to [device] and enters remote control mode. */
    fun handoffToRemote(device: DiscoveredDeviceDomain)

    /**
     * Moves the session from the device currently being controlled to [device], keeping the track,
     * queue, position and play state. Without it, picking another device while controlling one
     * left the old target playing and attached the UI to an unrelated session.
     */
    fun transferToDevice(device: DiscoveredDeviceDomain)
}
