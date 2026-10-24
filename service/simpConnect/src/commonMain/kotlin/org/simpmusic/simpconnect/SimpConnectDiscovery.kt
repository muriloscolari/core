package org.simpmusic.simpconnect

import com.maxrave.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "SimpConnectDiscovery"
private const val DEVICE_EXPIRATION_MS = 25_000L

class SimpConnectDiscovery(
    private val localDeviceId: String,
    private val localDeviceName: () -> String,
    private val localPlatform: String,
    private val getTcpPort: () -> Int,
    private val scope: CoroutineScope,
) {
    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private val deviceMap = ConcurrentHashMap<String, DiscoveredDevice>()

    private var listenerJob: Job? = null
    private var cleanupJob: Job? = null
    private var periodicPingJob: Job? = null

    @Volatile
    private var socket: DatagramSocket? = null

    fun start() {
        if (listenerJob != null) return
        Logger.d(TAG, "Starting discovery for deviceId: $localDeviceId")

        listenerJob = scope.launch(Dispatchers.IO) {
            runListenerLoop()
        }

        cleanupJob = scope.launch(Dispatchers.Default) {
            runCleanupLoop()
        }

        periodicPingJob = scope.launch(Dispatchers.IO) {
            // Wait briefly for the listener socket to bind so the initial ping originates
            // from the listening socket instead of an ephemeral one.
            var waited = 0
            while (socket == null && waited < 600) {
                delay(50)
                waited += 50
            }
            // Initial burst
            broadcastPingInternal()
            delay(1500)
            broadcastPingInternal()

            while (isActive) {
                delay(12_000)
                broadcastPingInternal()
            }
        }
    }

    fun stop() {
        Logger.d(TAG, "Stopping discovery")
        listenerJob?.cancel()
        listenerJob = null
        cleanupJob?.cancel()
        cleanupJob = null
        periodicPingJob?.cancel()
        periodicPingJob = null

        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null

        deviceMap.clear()
        _discoveredDevices.value = emptyList()
    }

    fun broadcastPing() {
        scope.launch(Dispatchers.IO) {
            broadcastPingInternal()
        }
    }

    private suspend fun runListenerLoop() {
        val buf = ByteArray(2048)
        val context = currentCoroutineContext()
        while (context.isActive) {
            try {
                val sock = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 2000
                    bind(InetSocketAddress(SIMP_CONNECT_DISCOVERY_PORT))
                }
                socket = sock
                Logger.d(TAG, "Discovery UDP socket bound to port $SIMP_CONNECT_DISCOVERY_PORT")

                while (context.isActive && !sock.isClosed) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(packet)
                        handlePacket(packet, sock)
                    } catch (_: SocketTimeoutException) {
                        // Regular timeout to allow checking cancellation
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        if (!sock.isClosed) {
                            Logger.w(TAG, "Socket receive error: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) break
                Logger.e(TAG, "Failed to bind or error in listener: ${e.message}")
                try {
                    socket?.close()
                } catch (_: Exception) {}
                socket = null
                if (!context.isActive) break
                // Wait a bit before retrying bind, but stay cancellable. A blocking Thread.sleep
                // here used to keep the discovery alive forever after stop().
                delay(3000)
            }
        }
    }

    private fun handlePacket(packet: DatagramPacket, listeningSocket: DatagramSocket) {
        val text = packet.data.decodeToString(startIndex = packet.offset, endIndex = packet.offset + packet.length).trim()
        if (!text.contains(SIMP_CONNECT_MAGIC)) return

        val msg = runCatching {
            simpConnectJson.decodeFromString(DiscoveryMessage.serializer(), text)
        }.getOrNull() ?: return

        when (msg) {
            is DiscoveryMessage.Ping -> {
                if (msg.deviceId == localDeviceId) return // Ignore own ping

                // Register ping sender immediately if valid TCP port is carried
                val hostIp = packet.address?.hostAddress
                if (hostIp != null && msg.tcpPort > 0) {
                    val now = System.currentTimeMillis()
                    val device = DiscoveredDevice(
                        deviceId = msg.deviceId,
                        deviceName = msg.deviceName,
                        platform = msg.platform,
                        hostAddress = hostIp,
                        tcpPort = msg.tcpPort,
                        lastSeenMs = now,
                    )
                    deviceMap[msg.deviceId] = device
                    publishDevices()
                }

                val tcpPort = getTcpPort()
                if (tcpPort <= 0) return // Server not ready yet

                val pong = DiscoveryMessage.Pong(
                    magic = SIMP_CONNECT_MAGIC,
                    deviceId = localDeviceId,
                    deviceName = localDeviceName(),
                    platform = localPlatform,
                    tcpPort = tcpPort,
                )
                val replyBytes = simpConnectJson.encodeToString(DiscoveryMessage.serializer(), pong).encodeToByteArray()
                val replyPacket = DatagramPacket(replyBytes, replyBytes.size, packet.socketAddress)
                try {
                    listeningSocket.send(replyPacket)
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to send pong to ${packet.socketAddress}: ${e.message}")
                }
                // If ping arrived from an ephemeral port, also unicast to standard discovery port
                if (packet.port != SIMP_CONNECT_DISCOVERY_PORT && packet.address != null) {
                    try {
                        listeningSocket.send(
                            DatagramPacket(replyBytes, replyBytes.size, packet.address, SIMP_CONNECT_DISCOVERY_PORT)
                        )
                    } catch (_: Exception) {}
                }
            }

            is DiscoveryMessage.Pong -> {
                if (msg.deviceId == localDeviceId) return // Ignore own pong
                val hostIp = packet.address?.hostAddress ?: return
                val now = System.currentTimeMillis()

                val device = DiscoveredDevice(
                    deviceId = msg.deviceId,
                    deviceName = msg.deviceName,
                    platform = msg.platform,
                    hostAddress = hostIp,
                    tcpPort = msg.tcpPort,
                    lastSeenMs = now,
                )
                deviceMap[msg.deviceId] = device
                publishDevices()
            }
        }
    }

    private fun broadcastPingInternal() {
        val port = getTcpPort().takeIf { it > 0 } ?: SIMP_CONNECT_DEFAULT_SERVER_PORT
        val ping = DiscoveryMessage.Ping(
            magic = SIMP_CONNECT_MAGIC,
            deviceId = localDeviceId,
            deviceName = localDeviceName(),
            platform = localPlatform,
            tcpPort = port,
        )
        val data = simpConnectJson.encodeToString(DiscoveryMessage.serializer(), ping).encodeToByteArray()

        val targets = mutableSetOf<InetAddress>()
        runCatching {
            targets.add(InetAddress.getByName("255.255.255.255"))
        }

        // Add interface broadcast addresses
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.interfaceAddresses }
                .mapNotNull { it.broadcast }
                .forEach { targets.add(it) }
        }

        val sendSock = socket ?: runCatching {
            DatagramSocket().apply {
                broadcast = true
                soTimeout = 1000
            }
        }.getOrNull()

        if (sendSock == null) return

        for (target in targets) {
            try {
                val packet = DatagramPacket(data, data.size, target, SIMP_CONNECT_DISCOVERY_PORT)
                sendSock.send(packet)
            } catch (_: Exception) {
                // Ignore per-interface route errors
            }
        }

        if (sendSock !== socket) {
            runCatching { sendSock.close() }
        }
    }

    private suspend fun runCleanupLoop() {
        while (scope.isActive) {
            delay(5000)
            val now = System.currentTimeMillis()
            var changed = false
            val it = deviceMap.entries.iterator()
            while (it.hasNext()) {
                val entry = it.next()
                if (now - entry.value.lastSeenMs > DEVICE_EXPIRATION_MS) {
                    it.remove()
                    changed = true
                }
            }
            if (changed) {
                publishDevices()
            }
        }
    }

    private fun publishDevices() {
        _discoveredDevices.value = deviceMap.values.toList().sortedBy { it.deviceName }
    }
}
