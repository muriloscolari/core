package com.my.kizzy.ipc

import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.mediaservice.handler.DiscordConnectionStatus
import com.maxrave.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.ClosedChannelException
import java.nio.channels.SocketChannel
import java.util.UUID

interface IpcChannel : Closeable {
    fun writeFully(data: ByteArray)
    fun readFully(data: ByteArray)
    val isOpen: Boolean
}

class UnixDomainIpcChannel(
    private val socketChannel: SocketChannel,
) : IpcChannel {
    override fun writeFully(data: ByteArray) {
        val buffer = ByteBuffer.wrap(data)
        while (buffer.hasRemaining()) {
            val written = socketChannel.write(buffer)
            if (written < 0) throw IOException("Channel closed while writing")
        }
    }

    override fun readFully(data: ByteArray) {
        val buffer = ByteBuffer.wrap(data)
        while (buffer.hasRemaining()) {
            val count = socketChannel.read(buffer)
            if (count < 0) throw IOException("End of stream reached")
        }
    }

    override val isOpen: Boolean
        get() = socketChannel.isOpen && socketChannel.isConnected

    override fun close() {
        runCatching { socketChannel.close() }
    }
}

class WindowsNamedPipeIpcChannel(
    private val raf: RandomAccessFile,
) : IpcChannel {
    override fun writeFully(data: ByteArray) {
        raf.write(data)
    }

    override fun readFully(data: ByteArray) {
        raf.readFully(data)
    }

    override val isOpen: Boolean
        get() = runCatching { raf.fd.valid() }.getOrDefault(false)

    override fun close() {
        runCatching { raf.close() }
    }
}

class DiscordNativeIpcClient(
    private val coroutineScope: CoroutineScope,
    private val statusFlow: MutableStateFlow<DiscordConnectionStatus>,
) {
    private val sendMutex = Mutex()

    @Volatile
    private var channel: IpcChannel? = null

    @Volatile
    private var isRunning = false

    @Volatile
    private var lastActivityPayload: String? = null

    private var connectionJob: Job? = null
    private var readerJob: Job? = null

    val isConnected: Boolean
        get() = channel?.isOpen == true && statusFlow.value == DiscordConnectionStatus.CONNECTED

    fun start() {
        if (isRunning) return
        isRunning = true
        statusFlow.value = DiscordConnectionStatus.CONNECTING
        connectionJob =
            coroutineScope.launch(Dispatchers.IO) {
                runConnectionLoop()
            }
    }

    fun stop() {
        isRunning = false
        connectionJob?.cancel()
        connectionJob = null
        readerJob?.cancel()
        readerJob = null

        coroutineScope.launch(Dispatchers.IO) {
            clearActivityInternal()
            closeCurrentChannel()
            statusFlow.value = DiscordConnectionStatus.DISABLED
        }
    }

    private suspend fun runConnectionLoop() {
        while (isRunning && coroutineScope.isActive) {
            val currentChan = channel
            if (currentChan == null || !currentChan.isOpen) {
                statusFlow.value = DiscordConnectionStatus.CONNECTING
                val connectedChannel = tryConnect()
                if (connectedChannel != null) {
                    channel = connectedChannel
                    statusFlow.value = DiscordConnectionStatus.CONNECTED
                    Logger.d(TAG, "Connected to Discord Desktop IPC")

                    // Push retained activity if available
                    lastActivityPayload?.let { payload ->
                        sendFrame(payload)
                    }

                    // Start background reader job to monitor socket and ping/pong
                    readerJob?.cancel()
                    readerJob =
                        coroutineScope.launch(Dispatchers.IO) {
                            runReader(connectedChannel)
                        }
                } else {
                    statusFlow.value = DiscordConnectionStatus.NOT_RUNNING
                }
            }

            // Silent retry delay
            if (isRunning && (channel == null || channel?.isOpen != true)) {
                delay(RECONNECT_INTERVAL_MS)
            } else {
                // If connected, wait until disconnected
                while (isRunning && channel?.isOpen == true && coroutineScope.isActive) {
                    delay(2_000L)
                }
            }
        }
    }

    private fun tryConnect(): IpcChannel? {
        val osName = System.getProperty("os.name").orEmpty().lowercase()
        return if (osName.contains("win")) {
            connectWindows()
        } else {
            connectUnix()
        }
    }

    private fun connectWindows(): IpcChannel? {
        for (i in 0..9) {
            val pipePath = "\\\\.\\pipe\\discord-ipc-$i"
            try {
                val raf = RandomAccessFile(File(pipePath), "rw")
                val chan = WindowsNamedPipeIpcChannel(raf)
                if (performHandshake(chan)) {
                    return chan
                } else {
                    chan.close()
                }
            } catch (_: Exception) {
                // Try next pipe index
            }
        }
        return null
    }

    private fun connectUnix(): IpcChannel? {
        val candidatePaths = getUnixCandidatePaths()
        for (path in candidatePaths) {
            val file = File(path)
            if (!file.exists()) continue
            try {
                val address = UnixDomainSocketAddress.of(file.toPath())
                val socketChannel = SocketChannel.open(StandardProtocolFamily.UNIX)
                socketChannel.connect(address)
                val chan = UnixDomainIpcChannel(socketChannel)
                if (performHandshake(chan)) {
                    return chan
                } else {
                    chan.close()
                }
            } catch (_: Exception) {
                // Continue searching
            }
        }
        return null
    }

    private fun getUnixCandidatePaths(): List<String> {
        val paths = mutableListOf<String>()
        val xdg = System.getenv("XDG_RUNTIME_DIR")
        val tmpdir = System.getenv("TMPDIR")
        val tmp = System.getenv("TMP")
        val temp = System.getenv("TEMP")
        val home = System.getProperty("user.home")

        // Also inspect /run/user/<uid> if available
        val runUserDirs =
            try {
                File("/run/user").listFiles()?.filter { it.isDirectory }?.map { it.absolutePath } ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }

        for (i in 0..9) {
            val socketName = "discord-ipc-$i"
            if (!xdg.isNullOrBlank()) {
                paths.add("$xdg/$socketName")
                paths.add("$xdg/app/com.discordapp.Discord/$socketName")
                paths.add("$xdg/snap.discord/$socketName")
            }
            for (runUser in runUserDirs) {
                paths.add("$runUser/$socketName")
                paths.add("$runUser/app/com.discordapp.Discord/$socketName")
                paths.add("$runUser/snap.discord/$socketName")
            }
            if (!tmpdir.isNullOrBlank()) {
                paths.add("$tmpdir/$socketName")
                paths.add("$tmpdir/app/com.discordapp.Discord/$socketName")
            }
            if (!tmp.isNullOrBlank()) paths.add("$tmp/$socketName")
            if (!temp.isNullOrBlank()) paths.add("$temp/$socketName")
            paths.add("/tmp/$socketName")

            if (!home.isNullOrBlank()) {
                paths.add("$home/.var/app/com.discordapp.Discord/data/$socketName")
                paths.add("$home/.var/app/com.discordapp.Discord/$socketName")
                paths.add("$home/snap/discord/current/$socketName")
            }
        }
        return paths.distinct()
    }

    private fun performHandshake(channel: IpcChannel): Boolean {
        try {
            val handshakeJson =
                buildJsonObject {
                    put("v", 1)
                    put("client_id", APPLICATION_ID)
                }.toString()

            sendPacket(channel, OPCODE_HANDSHAKE, handshakeJson)
            val (opcode, response) = readPacket(channel)
            return opcode == OPCODE_FRAME && response.contains("READY")
        } catch (e: Exception) {
            return false
        }
    }

    private suspend fun runReader(chan: IpcChannel) {
        try {
            while (isRunning && chan.isOpen) {
                val (opcode, payload) = readPacket(chan)
                when (opcode) {
                    OPCODE_PING -> {
                        sendMutex.withLock {
                            if (chan.isOpen) {
                                sendPacket(chan, OPCODE_PONG, payload)
                            }
                        }
                    }
                    OPCODE_CLOSE -> {
                        Logger.d(TAG, "Discord IPC close received")
                        break
                    }
                    OPCODE_FRAME -> {
                        // Regular DISPATCH frame or command acknowledgement
                    }
                }
            }
        } catch (e: Exception) {
            if (e !is CancellationException && e !is ClosedChannelException) {
                Logger.d(TAG, "Discord IPC connection dropped: ${e.message}")
            }
        } finally {
            closeCurrentChannel()
            if (isRunning) {
                statusFlow.value = DiscordConnectionStatus.NOT_RUNNING
            }
        }
    }

    private fun closeCurrentChannel() {
        val chan = channel
        channel = null
        chan?.close()
    }

    suspend fun updatePlaying(
        song: SongEntity,
        currentPlaybackTimeMillis: Long,
        durationMillis: Long,
        playbackSpeed: Float = 1.0f,
    ) {
        val currentTime = System.currentTimeMillis()
        val speed = if (playbackSpeed <= 0f) 1.0f else playbackSpeed
        val adjustedPlaybackTime = (currentPlaybackTimeMillis / speed).toLong()
        val calculatedStartTime = currentTime - adjustedPlaybackTime
        val remainingDuration = (durationMillis - currentPlaybackTimeMillis).coerceAtLeast(0L)
        val adjustedRemainingDuration = (remainingDuration / speed).toLong()
        val calculatedEndTime = currentTime + adjustedRemainingDuration

        val startSeconds = calculatedStartTime / 1000
        val endSeconds = if (durationMillis > 0) calculatedEndTime / 1000 else null

        val buttons = mutableListOf<Pair<String, String>>()
        if (!song.videoId.isNullOrBlank()) {
            buttons.add("Listen on SimpMusic" to "https://simpmusic.org/app/watch?v=${song.videoId}")
        }
        buttons.add("Visit SimpMusic" to "https://github.com/maxrave-dev/SimpMusic")

        val payload =
            buildSetActivityJson(
                pid = currentPid,
                details = song.title,
                state = song.artistName?.joinToString(", ") ?: "",
                startTime = startSeconds,
                endTime = endSeconds,
                largeImage = song.thumbnails ?: APP_ICON,
                largeText = song.albumName ?: song.title,
                smallImage = APP_ICON,
                smallText = song.artistName?.firstOrNull() ?: APP_NAME,
                buttons = buttons,
            )
        lastActivityPayload = payload
        if (isConnected) {
            sendFrame(payload)
        }
    }

    suspend fun updatePaused(
        song: SongEntity,
        progressMs: Long,
        durationMs: Long,
    ) {
        val artist = song.artistName?.joinToString(", ") ?: ""
        val stateText = if (artist.isNotBlank()) "$artist (Pausado)" else "Pausado"

        val buttons = mutableListOf<Pair<String, String>>()
        if (!song.videoId.isNullOrBlank()) {
            buttons.add("Listen on SimpMusic" to "https://simpmusic.org/app/watch?v=${song.videoId}")
        }
        buttons.add("Visit SimpMusic" to "https://github.com/maxrave-dev/SimpMusic")

        val payload =
            buildSetActivityJson(
                pid = currentPid,
                details = song.title,
                state = stateText,
                startTime = null,
                endTime = null,
                largeImage = song.thumbnails ?: APP_ICON,
                largeText = song.albumName ?: song.title,
                smallImage = APP_ICON,
                smallText = "Pausado",
                buttons = buttons,
            )
        lastActivityPayload = payload
        if (isConnected) {
            sendFrame(payload)
        }
    }

    suspend fun clearActivity() {
        lastActivityPayload = null
        clearActivityInternal()
    }

    private suspend fun clearActivityInternal() {
        if (!isConnected) return
        val payload = buildClearActivityJson(currentPid)
        sendFrame(payload)
    }

    private suspend fun sendFrame(jsonPayload: String) {
        sendMutex.withLock {
            val chan = channel
            if (chan != null && chan.isOpen) {
                try {
                    sendPacket(chan, OPCODE_FRAME, jsonPayload)
                } catch (e: Exception) {
                    Logger.e(TAG, "Failed to send Discord IPC frame: ${e.message}")
                    closeCurrentChannel()
                    if (isRunning) {
                        statusFlow.value = DiscordConnectionStatus.NOT_RUNNING
                    }
                }
            }
        }
    }

    private fun sendPacket(
        channel: IpcChannel,
        opcode: Int,
        jsonPayload: String,
    ) {
        val payloadBytes = jsonPayload.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(8 + payloadBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(opcode)
        buffer.putInt(payloadBytes.size)
        buffer.put(payloadBytes)
        channel.writeFully(buffer.array())
    }

    private fun readPacket(channel: IpcChannel): Pair<Int, String> {
        val headerBytes = ByteArray(8)
        channel.readFully(headerBytes)
        val header = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        val opcode = header.getInt()
        val length = header.getInt()
        if (length < 0 || length > 1024 * 1024) {
            throw IOException("Discord IPC packet length invalid: $length")
        }
        val payloadBytes = ByteArray(length)
        if (length > 0) {
            channel.readFully(payloadBytes)
        }
        return Pair(opcode, String(payloadBytes, Charsets.UTF_8))
    }

    private fun buildSetActivityJson(
        pid: Long,
        details: String,
        state: String,
        startTime: Long?,
        endTime: Long?,
        largeImage: String?,
        largeText: String?,
        smallImage: String?,
        smallText: String?,
        buttons: List<Pair<String, String>> = emptyList(),
    ): String {
        val activity =
            buildJsonObject {
                put("name", APP_NAME)
                put("type", 2) // LISTENING
                put("details", details)
                if (state.isNotBlank()) put("state", state)
                if (startTime != null || endTime != null) {
                    putJsonObject("timestamps") {
                        if (startTime != null) put("start", startTime)
                        if (endTime != null) put("end", endTime)
                    }
                }
                putJsonObject("assets") {
                    if (!largeImage.isNullOrBlank()) put("large_image", largeImage)
                    if (!largeText.isNullOrBlank()) put("large_text", largeText)
                    if (!smallImage.isNullOrBlank()) put("small_image", smallImage)
                    if (!smallText.isNullOrBlank()) put("small_text", smallText)
                }
                if (buttons.isNotEmpty()) {
                    putJsonArray("buttons") {
                        for ((label, url) in buttons.take(2)) {
                            addJsonObject {
                                put("label", label)
                                put("url", url)
                            }
                        }
                    }
                }
            }
        val root =
            buildJsonObject {
                put("cmd", "SET_ACTIVITY")
                putJsonObject("args") {
                    put("pid", pid)
                    put("activity", activity)
                }
                put("nonce", UUID.randomUUID().toString())
            }
        return root.toString()
    }

    private fun buildClearActivityJson(pid: Long): String {
        val root =
            buildJsonObject {
                put("cmd", "SET_ACTIVITY")
                putJsonObject("args") {
                    put("pid", pid)
                    put("activity", JsonNull)
                }
                put("nonce", UUID.randomUUID().toString())
            }
        return root.toString()
    }

    companion object {
        private const val TAG = "DiscordNativeIpcClient"
        const val APPLICATION_ID = "1271273225120125040"
        private const val APP_NAME = "SimpMusic"
        private const val APP_ICON =
            "https://fra.cloud.appwrite.io/v1/storage/buckets/683f1f620010ba0fa5b1/files/69007bc8001a28a7cea8/view?project=67ec0369002bd8a96885"
        private const val RECONNECT_INTERVAL_MS = 10_000L

        private const val OPCODE_HANDSHAKE = 0
        private const val OPCODE_FRAME = 1
        private const val OPCODE_CLOSE = 2
        private const val OPCODE_PING = 3
        private const val OPCODE_PONG = 4

        private val currentPid: Long by lazy {
            try {
                ProcessHandle.current().pid()
            } catch (_: Throwable) {
                12345L
            }
        }
    }
}
