package com.movsx.bandsync

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

class BandSyncServer(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val beatTrackGenerator = BeatTrackGenerator(appContext)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val playbackLock = Any()
    private val clients = ConcurrentHashMap<String, TrackedClient>()
    private val eventClients = ConcurrentHashMap<String, EventClient>()

    private val _uiState = MutableStateFlow(ServerUiState())
    val uiState: StateFlow<ServerUiState> = _uiState.asStateFlow()

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var tickJob: Job? = null
    private var scheduledCommandJob: Job? = null
    private var networkSignalWarmupJob: Job? = null
    private var serverPlayer: MediaPlayer? = null
    private var serverPlayerPrepared = false
    private var clientAudioFile: File? = null
    private var clientAudioMimeType: String = "audio/mpeg"
    private var audioRevision: Long = 0L
    private var commandId: Long = 0L
    private var currentCommand: RemoteCommand = RemoteCommand.IDLE
    private var commandSyncMode: PlaybackSyncMode = PlaybackSyncMode.DEVICE_TIME
    private var commandStartPositionMs: Long = 0L
    private var commandExecuteAtWallClockMs: Long = 0L
    private var startedAtElapsedMs: Long = 0L
    private var pausedPositionMs: Long = 0L
    private var isPlaying: Boolean = false

    fun setPortInput(value: String) {
        val filtered = value.filter { it.isDigit() }.take(5)
        _uiState.update { it.copy(portInput = filtered, errorMessage = null) }
    }

    fun setSyncMode(mode: PlaybackSyncMode) {
        _uiState.update { it.copy(syncMode = mode, errorMessage = null) }
    }

    fun start() {
        if (_uiState.value.isRunning) return

        val port = _uiState.value.portInput.toIntOrNull()
        if (port == null || port !in 1..65535) {
            _uiState.update { it.copy(errorMessage = "端口必须在 1 到 65535 之间") }
            return
        }

        _uiState.update { it.copy(status = "正在启动服务器...", errorMessage = null) }
        ioScope.launch {
            try {
                val socket = ServerSocket()
                socket.reuseAddress = true
                socket.bind(InetSocketAddress("0.0.0.0", port), 50)
                serverSocket = socket

                _uiState.update {
                    it.copy(
                        boundPort = port,
                        isRunning = true,
                        listenAddresses = findLocalIpv4Addresses(),
                        status = "服务器正在监听",
                        errorMessage = null
                    )
                }

                acceptJob = launch { acceptLoop(socket) }
                tickJob = launch { tickLoop() }
            } catch (error: IOException) {
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        status = "服务器启动失败",
                        errorMessage = error.localizedMessage ?: "无法监听该端口"
                    )
                }
            }
        }
    }

    fun stop() {
        acceptJob?.cancel()
        tickJob?.cancel()
        scheduledCommandJob?.cancel()
        networkSignalWarmupJob?.cancel()
        acceptJob = null
        tickJob = null
        scheduledCommandJob = null
        networkSignalWarmupJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        clients.clear()
        val clientsToClose = eventClients.values.toList()
        eventClients.clear()
        ioScope.launch {
            clientsToClose.forEach { runCatching { it.socket.close() } }
        }
        pauseLocalPlayback(status = "服务器已停止")
        _uiState.update {
            it.copy(
                isRunning = false,
                listenAddresses = emptyList(),
                clients = emptyList(),
                status = "服务器已停止"
            )
        }
    }

    fun selectServerAudio(uri: Uri) {
        _uiState.update { it.copy(status = "正在读取 Server Output...", errorMessage = null) }
        ioScope.launch {
            try {
                val info = readAudioSelection(uri)
                withContext(Dispatchers.Main.immediate) {
                    prepareServerPlayer(uri)
                }
                _uiState.update { current ->
                    val duration = max(info.durationMs, current.clientAudio?.durationMs ?: 0L)
                    trimPlaybackToDuration(duration)
                    current.copy(
                        serverAudio = info,
                        durationMs = duration,
                        playbackPositionMs = currentPlaybackPosition(),
                        status = "Server Output 已加载",
                        errorMessage = null
                    )
                }
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        status = "读取 Server Output 失败",
                        errorMessage = error.localizedMessage ?: "无法读取音频文件"
                    )
                }
            }
        }
    }

    fun selectClientAudio(uri: Uri) {
        _uiState.update { it.copy(status = "正在缓存 Clients Output...", errorMessage = null) }
        ioScope.launch {
            try {
                val info = readAudioSelection(uri)
                val cachedFile = copyToStreamCache(uri, info.displayName)
                val oldFile = clientAudioFile
                clientAudioFile = cachedFile
                clientAudioMimeType = info.mimeType.ifBlank { guessMimeType(info.displayName) }
                oldFile?.delete()

                synchronized(playbackLock) {
                    audioRevision += 1L
                }

                _uiState.update { current ->
                    val cachedInfo = info.copy(sizeBytes = cachedFile.length())
                    val duration = max(current.serverAudio?.durationMs ?: 0L, cachedInfo.durationMs)
                    trimPlaybackToDuration(duration)
                    current.copy(
                        clientAudio = cachedInfo,
                        durationMs = duration,
                        audioRevision = audioRevision,
                        detectedBpm = null,
                        detectedBeatOffsetMs = null,
                        isGeneratingBeat = false,
                        playbackPositionMs = currentPlaybackPosition(),
                        status = "Clients Output 已缓存到服务端，等待客户端下载",
                        errorMessage = null
                    )
                }
                broadcastSnapshot()
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        status = "读取 Clients Output 失败",
                        errorMessage = error.localizedMessage ?: "无法读取音频文件"
                    )
                }
            }
        }
    }

    fun generateClientBeatFromServerOutput() {
        val serverAudio = _uiState.value.serverAudio
        if (serverAudio == null) {
            _uiState.update { it.copy(errorMessage = "请先选择 Server Output") }
            return
        }
        if (_uiState.value.isGeneratingBeat) return

        _uiState.update {
            it.copy(
                isGeneratingBeat = true,
                status = "正在分析 Server Output 的 BPM 和节拍...",
                errorMessage = null
            )
        }

        ioScope.launch {
            try {
                val generated = beatTrackGenerator.generateFrom(serverAudio)
                val oldFile = clientAudioFile
                clientAudioFile = generated.file
                clientAudioMimeType = "audio/wav"
                oldFile?.delete()

                synchronized(playbackLock) {
                    audioRevision += 1L
                }

                val generatedAudio = AudioSelection(
                    uri = Uri.fromFile(generated.file),
                    displayName = "Generated Beat ${generated.bpm} BPM.wav",
                    durationMs = generated.durationMs,
                    sizeBytes = generated.file.length(),
                    mimeType = "audio/wav"
                )

                _uiState.update { current ->
                    val duration = max(current.serverAudio?.durationMs ?: 0L, generatedAudio.durationMs)
                    trimPlaybackToDuration(duration)
                    current.copy(
                        clientAudio = generatedAudio,
                        durationMs = duration,
                        audioRevision = audioRevision,
                        isGeneratingBeat = false,
                        detectedBpm = generated.bpm,
                        detectedBeatOffsetMs = generated.offsetMs,
                        playbackPositionMs = currentPlaybackPosition(),
                        status = "已生成节拍：${generated.bpm} BPM，首拍偏移 ${generated.offsetMs} ms",
                        errorMessage = null
                    )
                }
                broadcastSnapshot()
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isGeneratingBeat = false,
                        status = "节拍生成失败",
                        errorMessage = error.localizedMessage ?: "无法从 Server Output 分析节拍"
                    )
                }
            }
        }
    }

    fun play() {
        val duration = _uiState.value.durationMs
        if (duration <= 0L) {
            _uiState.update { it.copy(errorMessage = "请先选择至少一个音频文件") }
            return
        }

        if (!allVisibleClientsCached()) {
            val waitingCount = currentVisibleClients().count { !it.isCurrentAudioCached }
            _uiState.update {
                it.copy(
                    status = "还有 $waitingCount 个客户端未完成缓存，未下发开始命令",
                    errorMessage = null
                )
            }
            return
        }

        val visibleClients = currentVisibleClients()
        val readyErrorLimitMs = readyTimeSyncErrorLimitMs(visibleClients)
        if (_uiState.value.syncMode == PlaybackSyncMode.DEVICE_TIME && !allVisibleClientsTimeSynced(visibleClients, readyErrorLimitMs)) {
            val waitingCount = visibleClients.count { !it.isTimeSyncReady(readyErrorLimitMs) }
            _uiState.update {
                it.copy(
                    status = "还有 $waitingCount 个客户端时间同步未稳定（动态阈值 ${readyErrorLimitMs}ms），未下发开始命令",
                    errorMessage = null
                )
            }
            return
        }

        val startPosition = synchronized(playbackLock) {
            if (pausedPositionMs >= duration) 0L else pausedPositionMs.coerceAtLeast(0L)
        }
        val mode = _uiState.value.syncMode
        if (mode == PlaybackSyncMode.NETWORK_SIGNAL) {
            issueNetworkSignalPlay(startPosition)
        } else {
            issueCommand(RemoteCommand.PLAY, startPosition, mode)
        }
    }

    fun pausePlayback() {
        if (_uiState.value.durationMs <= 0L) return
        networkSignalWarmupJob?.cancel()
        networkSignalWarmupJob = null
        issueCommand(RemoteCommand.PAUSE, currentPlaybackPosition(), _uiState.value.syncMode)
    }

    fun stopPlayback() {
        if (_uiState.value.durationMs <= 0L) return
        networkSignalWarmupJob?.cancel()
        networkSignalWarmupJob = null
        issueCommand(RemoteCommand.STOP, 0L, _uiState.value.syncMode)
    }

    fun clearCache() {
        stopPlayback()
        val oldFile = clientAudioFile
        clientAudioFile = null
        clientAudioMimeType = "audio/mpeg"
        oldFile?.delete()
        appContext.cacheDir.listFiles()
            ?.filter {
                it.name.startsWith("bandsync-client-output-") ||
                    it.name.startsWith("bandsync-generated-click-")
            }
            ?.forEach { it.delete() }

        synchronized(playbackLock) {
            audioRevision += 1L
        }

        _uiState.update { current ->
            val duration = current.serverAudio?.durationMs ?: 0L
            trimPlaybackToDuration(duration)
            current.copy(
                clientAudio = null,
                durationMs = duration,
                audioRevision = audioRevision,
                detectedBpm = null,
                detectedBeatOffsetMs = null,
                isGeneratingBeat = false,
                playbackPositionMs = currentPlaybackPosition(),
                status = "服务端 Clients Output 缓存已清除",
                errorMessage = null
            )
        }
        broadcastSnapshot()
    }

    override fun close() {
        stop()
        ioScope.cancel()
        runCatching {
            serverPlayer?.release()
            serverPlayer = null
            serverPlayerPrepared = false
        }
        mainScope.cancel()
        runCatching { clientAudioFile?.delete() }
    }

    private fun issueNetworkSignalPlay(startPositionMs: Long) {
        networkSignalWarmupJob?.cancel()
        issueCommand(RemoteCommand.PREPARE, startPositionMs, PlaybackSyncMode.NETWORK_SIGNAL)
        networkSignalWarmupJob = mainScope.launch {
            delay(NETWORK_SIGNAL_WARMUP_MS)
            val shouldPlay = synchronized(playbackLock) {
                currentCommand == RemoteCommand.PREPARE &&
                    commandSyncMode == PlaybackSyncMode.NETWORK_SIGNAL &&
                    commandStartPositionMs == startPositionMs.coerceAtLeast(0L)
            }
            if (shouldPlay) {
                issueCommand(RemoteCommand.PLAY, startPositionMs, PlaybackSyncMode.NETWORK_SIGNAL)
            }
        }
    }

    private fun issueCommand(command: RemoteCommand, startPositionMs: Long, mode: PlaybackSyncMode) {
        val issueElapsedMs = SystemClock.elapsedRealtime()
        val executeAtWallClockMs = if (mode == PlaybackSyncMode.DEVICE_TIME && command == RemoteCommand.PLAY) {
            System.currentTimeMillis() + DEVICE_TIME_COMMAND_LEAD_MS
        } else {
            0L
        }
        val executeAtElapsedRealtimeMs = if (mode == PlaybackSyncMode.DEVICE_TIME && command == RemoteCommand.PLAY) {
            issueElapsedMs + DEVICE_TIME_COMMAND_LEAD_MS
        } else {
            0L
        }
        val issuedId = synchronized(playbackLock) {
            commandId += 1L
            currentCommand = command
            commandSyncMode = mode
            commandStartPositionMs = startPositionMs.coerceAtLeast(0L)
            commandExecuteAtWallClockMs = executeAtWallClockMs
            commandId
        }

        _uiState.update {
            it.copy(
                commandId = issuedId,
                command = command,
                status = commandStatus(command, mode, executeAtWallClockMs),
                errorMessage = null
            )
        }
        scheduleLocalCommand(command, startPositionMs, mode, executeAtWallClockMs, executeAtElapsedRealtimeMs)
        broadcastSnapshot()
    }

    private fun scheduleLocalCommand(
        command: RemoteCommand,
        startPositionMs: Long,
        mode: PlaybackSyncMode,
        executeAtWallClockMs: Long,
        executeAtElapsedRealtimeMs: Long
    ) {
        scheduledCommandJob?.cancel()
        scheduledCommandJob = mainScope.launch {
            if (mode == PlaybackSyncMode.DEVICE_TIME) {
                waitUntilElapsedRealtime(executeAtElapsedRealtimeMs)
            }
            when (command) {
                RemoteCommand.PREPARE -> prepareLocalPlayback(startPositionMs)
                RemoteCommand.PLAY -> startLocalPlayback(startPositionMs, mode, executeAtWallClockMs, executeAtElapsedRealtimeMs)
                RemoteCommand.PAUSE -> pauseLocalPlayback(status = "已暂停")
                RemoteCommand.STOP -> stopLocalPlayback(status = "已停止")
                RemoteCommand.IDLE -> Unit
            }
        }
    }

    private fun prepareLocalPlayback(startPositionMs: Long) {
        val duration = _uiState.value.durationMs
        if (duration <= 0L) return
        val targetPosition = startPositionMs.coerceIn(0L, duration)
        if (targetPosition < duration) {
            preSeekServerPlayerAt(targetPosition)
        }
        synchronized(playbackLock) {
            pausedPositionMs = targetPosition
            isPlaying = false
        }
        _uiState.update {
            it.copy(
                isPlaying = false,
                playbackPositionMs = targetPosition,
                status = "网络信号同步预热中...",
                errorMessage = null
            )
        }
    }

    private fun startLocalPlayback(
        startPositionMs: Long,
        mode: PlaybackSyncMode,
        executeAtWallClockMs: Long,
        executeAtElapsedRealtimeMs: Long
    ) {
        val duration = _uiState.value.durationMs
        if (duration <= 0L) return

        val latenessMs = if (mode == PlaybackSyncMode.DEVICE_TIME && executeAtWallClockMs > 0L) {
            (SystemClock.elapsedRealtime() - executeAtElapsedRealtimeMs).coerceAtLeast(0L)
        } else {
            0L
        }
        val targetPosition = (startPositionMs + latenessMs).coerceIn(0L, duration)
        val shouldPlay = targetPosition < duration

        synchronized(playbackLock) {
            pausedPositionMs = targetPosition
            startedAtElapsedMs = SystemClock.elapsedRealtime() - targetPosition
            isPlaying = shouldPlay
        }

        _uiState.update {
            it.copy(
                isPlaying = shouldPlay,
                playbackPositionMs = targetPosition,
                status = if (shouldPlay) "正在播放" else "播放已结束",
                errorMessage = null
            )
        }

        if (shouldPlay) {
            startServerPlayerAt(targetPosition, executeAtElapsedRealtimeMs)
        } else {
            pauseServerPlayerAt(duration)
        }
    }

    private fun pauseLocalPlayback(status: String) {
        val position = synchronized(playbackLock) {
            pausedPositionMs = currentPlaybackPositionLocked()
            isPlaying = false
            pausedPositionMs
        }
        _uiState.update {
            it.copy(
                isPlaying = false,
                playbackPositionMs = position,
                status = status
            )
        }
        mainScope.launch { pauseServerPlayerAt(position) }
    }

    private fun stopLocalPlayback(status: String) {
        synchronized(playbackLock) {
            pausedPositionMs = 0L
            startedAtElapsedMs = 0L
            isPlaying = false
        }
        _uiState.update {
            it.copy(
                isPlaying = false,
                playbackPositionMs = 0L,
                status = status,
                errorMessage = null
            )
        }
        mainScope.launch { pauseServerPlayerAt(0L) }
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        while (ioScope.isActive && !socket.isClosed) {
            try {
                val clientSocket = socket.accept()
                ioScope.launch { handleHttpConnection(clientSocket) }
            } catch (_: IOException) {
                if (_uiState.value.isRunning) {
                    _uiState.update { it.copy(errorMessage = "连接监听中断") }
                }
                break
            }
        }
    }

    private suspend fun tickLoop() {
        while (ioScope.isActive && _uiState.value.isRunning) {
            val position = synchronized(playbackLock) {
                val duration = _uiState.value.durationMs
                val current = currentPlaybackPositionLocked()
                if (isPlaying && duration > 0L && current >= duration) {
                    pausedPositionMs = duration
                    isPlaying = false
                }
                current.coerceIn(0L, max(duration, 0L))
            }
            val playingSnapshot = synchronized(playbackLock) { isPlaying }
            _uiState.update {
                it.copy(
                    isPlaying = playingSnapshot,
                    playbackPositionMs = position,
                    clients = currentVisibleClients(),
                    audioRevision = audioRevision
                )
            }
            delay(500L)
        }
    }

    private fun handleHttpConnection(socket: Socket) {
        try {
            socket.use { activeSocket ->
                activeSocket.soTimeout = 15_000
                val input = BufferedInputStream(activeSocket.getInputStream())
                val requestLine = readAsciiLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) {
                    writeTextResponse(activeSocket, 400, "Bad Request")
                    return
                }

                val target = parts[1]
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = readAsciiLine(input) ?: break
                    if (line.isEmpty()) break
                    val separator = line.indexOf(':')
                    if (separator > 0) {
                        headers[line.substring(0, separator).trim().lowercase(Locale.US)] =
                            line.substring(separator + 1).trim()
                    }
                }

                val path = target.substringBefore('?')
                val query = parseQuery(target.substringAfter('?', ""))
                updateClientHeartbeat(query, activeSocket)

                when (path) {
                    "/events" -> serveEvents(activeSocket, query)
                    "/client-status" -> writeJsonResponse(
                        activeSocket,
                        JSONObject().put("ok", true)
                    )
                    "/time-sync" -> writeJsonResponse(activeSocket, timeSyncJson())
                    "/stream" -> serveClientAudio(activeSocket, headers["range"])
                    else -> writeTextResponse(activeSocket, 404, "Not Found")
                }
            }
        } catch (_: IOException) {
            // Clients may cancel file downloads; each request is independent.
        }
    }

    private fun serveEvents(socket: Socket, query: Map<String, String>) {
        val id = query["clientId"]?.takeIf { it.isNotBlank() }
        if (id == null) {
            writeTextResponse(socket, 400, "Missing clientId")
            return
        }

        val headers = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/x-ndjson; charset=utf-8\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: keep-alive\r\n")
            append("\r\n")
        }
        val output = socket.getOutputStream()
        output.write(headers.toByteArray(Charsets.ISO_8859_1))
        output.flush()

        val eventClient = EventClient(id = id, socket = socket, output = output)
        eventClients[id]?.let { runCatching { it.socket.close() } }
        eventClients[id] = eventClient

        if (!sendEvent(eventClient, playbackStateJson())) return
        try {
            while (_uiState.value.isRunning && !socket.isClosed) {
                Thread.sleep(EVENT_KEEPALIVE_MS)
                if (!sendEvent(eventClient, JSONObject().put("type", "keepalive"))) break
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            eventClients.remove(id, eventClient)
            eventClient.sendJob?.cancel()
        }
    }

    private fun playbackStateJson(): JSONObject {
        val state = _uiState.value
        val commandSnapshot = synchronized(playbackLock) {
            CommandSnapshot(
                id = commandId,
                command = currentCommand,
                syncMode = commandSyncMode,
                startPositionMs = commandStartPositionMs,
                executeAtWallClockMs = commandExecuteAtWallClockMs
            )
        }

        return JSONObject()
            .put("ok", true)
            .put("hasClientAudio", clientAudioFile?.exists() == true)
            .put("fileName", state.clientAudio?.displayName ?: "")
            .put("durationMs", state.clientAudio?.durationMs ?: 0L)
            .put("audioRevision", audioRevision)
            .put("audioSizeBytes", state.clientAudio?.sizeBytes ?: 0L)
            .put("commandId", commandSnapshot.id)
            .put("command", commandSnapshot.command.name)
            .put("syncMode", commandSnapshot.syncMode.name)
            .put("startPositionMs", commandSnapshot.startPositionMs)
            .put("executeAtWallClockMs", commandSnapshot.executeAtWallClockMs)
    }

    private fun timeSyncJson(): JSONObject {
        val receiveWallClockMs = System.currentTimeMillis()
        return JSONObject()
            .put("ok", true)
            .put("serverReceiveWallClockMs", receiveWallClockMs)
            .put("serverSendWallClockMs", System.currentTimeMillis())
    }

    private fun broadcastSnapshot() {
        if (eventClients.isEmpty()) return
        val payload = eventPayload(playbackStateJson())
        eventClients.values.forEach { client ->
            queueEvent(client, payload)
        }
    }

    private fun eventPayload(json: JSONObject): ByteArray =
        (json.toString() + "\n").toByteArray(Charsets.UTF_8)

    private fun queueEvent(client: EventClient, payload: ByteArray) {
        synchronized(client) {
            val previousJob = client.sendJob
            client.sendJob = ioScope.launch {
                previousJob?.join()
                if (!sendEvent(client, payload)) {
                    eventClients.remove(client.id, client)
                }
            }
        }
    }

    private fun sendEvent(client: EventClient, json: JSONObject): Boolean =
        sendEvent(client, eventPayload(json))

    private fun sendEvent(client: EventClient, payload: ByteArray): Boolean =
        try {
            synchronized(client) {
                client.output.write(payload)
                client.output.flush()
            }
            true
        } catch (_: IOException) {
            runCatching { client.socket.close() }
            false
        }

    private fun serveClientAudio(socket: Socket, rangeHeader: String?) {
        val file = clientAudioFile
        if (file == null || !file.exists()) {
            writeTextResponse(socket, 404, "Clients Output is not selected")
            return
        }

        val totalLength = file.length()
        val range = parseRange(rangeHeader, totalLength)
        val start = range?.first ?: 0L
        val end = range?.second ?: (totalLength - 1L)
        if (start !in 0 until totalLength || end < start) {
            writeTextResponse(socket, 416, "Range Not Satisfiable")
            return
        }

        val contentLength = end - start + 1L
        val status = if (range == null) "200 OK" else "206 Partial Content"
        val headers = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Content-Type: ").append(clientAudioMimeType).append("\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Content-Length: ").append(contentLength).append("\r\n")
            if (range != null) {
                append("Content-Range: bytes ")
                    .append(start)
                    .append('-')
                    .append(end)
                    .append('/')
                    .append(totalLength)
                    .append("\r\n")
            }
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }

        val output = socket.getOutputStream()
        output.write(headers.toByteArray(Charsets.ISO_8859_1))
        FileInputStream(file).use { input ->
            skipFully(input, start)
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var remaining = contentLength
            while (remaining > 0L) {
                val read = input.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
                if (read < 0) break
                output.write(buffer, 0, read)
                remaining -= read.toLong()
            }
        }
        output.flush()
    }

    private fun prepareServerPlayer(uri: Uri) {
        runCatching {
            serverPlayer?.release()
            serverPlayerPrepared = false
            serverPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(appContext, uri)
                setOnPreparedListener { player ->
                    serverPlayerPrepared = true
                    val position = currentPlaybackPosition()
                    seekToCompat(player, position)
                    if (synchronized(playbackLock) { isPlaying } && position < player.duration) {
                        player.start()
                    }
                }
                setOnErrorListener { _, _, _ ->
                    serverPlayerPrepared = false
                    _uiState.update { it.copy(errorMessage = "Server Output 播放失败") }
                    true
                }
                prepareAsync()
            }
        }.onFailure { error ->
            _uiState.update {
                it.copy(errorMessage = error.localizedMessage ?: "无法准备 Server Output 播放器")
            }
        }
    }

    private fun startServerPlayerAt(positionMs: Long, targetElapsedMs: Long) {
        val player = serverPlayer ?: return
        if (!serverPlayerPrepared) return
        runCatching {
            val serverAudioDuration = _uiState.value.serverAudio?.durationMs ?: 0L
            if (serverAudioDuration <= 0L || positionMs >= serverAudioDuration) {
                if (player.isPlaying) player.pause()
                return
            }
            if (kotlin.math.abs(player.currentPosition.toLong() - positionMs) > PRE_SEEK_TOLERANCE_MS) {
                seekToCompat(player, positionMs)
            }
            player.start()
            val actualStartElapsedMs = SystemClock.elapsedRealtime()
            if (targetElapsedMs > 0L) {
                val startDeltaMs = actualStartElapsedMs - targetElapsedMs
                _uiState.update {
                    it.copy(status = "正在播放（本机开始偏差 ${startDeltaMs.formatSignedMs()}）")
                }
            }
        }
    }

    private fun preSeekServerPlayerAt(positionMs: Long) {
        val player = serverPlayer ?: return
        if (!serverPlayerPrepared) return
        runCatching {
            val serverAudioDuration = _uiState.value.serverAudio?.durationMs ?: 0L
            if (serverAudioDuration <= 0L || positionMs >= serverAudioDuration) return@runCatching
            if (player.isPlaying) player.pause()
            if (kotlin.math.abs(player.currentPosition.toLong() - positionMs) > PRE_SEEK_TOLERANCE_MS) {
                seekToCompat(player, positionMs)
            }
        }
    }

    private fun pauseServerPlayerAt(positionMs: Long) {
        val player = serverPlayer ?: return
        if (!serverPlayerPrepared) return
        runCatching {
            if (player.isPlaying) player.pause()
            val serverAudioDuration = _uiState.value.serverAudio?.durationMs ?: 0L
            if (positionMs < serverAudioDuration) {
                seekToCompat(player, positionMs)
            }
        }
    }

    private fun currentPlaybackPosition(): Long =
        synchronized(playbackLock) { currentPlaybackPositionLocked() }

    private fun currentPlaybackPositionLocked(): Long {
        val duration = _uiState.value.durationMs
        val position = if (isPlaying) {
            SystemClock.elapsedRealtime() - startedAtElapsedMs
        } else {
            pausedPositionMs
        }
        return if (duration > 0L) position.coerceIn(0L, duration) else 0L
    }

    private fun trimPlaybackToDuration(duration: Long) {
        synchronized(playbackLock) {
            if (duration <= 0L) {
                pausedPositionMs = 0L
                isPlaying = false
                startedAtElapsedMs = 0L
            } else if (pausedPositionMs > duration) {
                pausedPositionMs = duration
                if (isPlaying) {
                    startedAtElapsedMs = SystemClock.elapsedRealtime() - pausedPositionMs
                }
            }
        }
    }

    private fun updateClientHeartbeat(query: Map<String, String>, socket: Socket) {
        val id = query["clientId"]?.takeIf { it.isNotBlank() } ?: return
        val name = query["name"]?.takeIf { it.isNotBlank() } ?: "Client ${id.takeLast(4)}"
        val cachedRevision = query["cachedRevision"]?.toLongOrNull() ?: clients[id]?.cachedAudioRevision ?: -1L
        val cacheStatus = query["cacheStatus"] ?: clients[id]?.cacheStatus ?: "missing"
        val cacheProgress = query["cacheProgress"]?.toIntOrNull()?.coerceIn(0, 100)
            ?: clients[id]?.cacheProgressPercent
            ?: 0
        val signalLatency = query["signalLatencyMs"]?.toLongOrNull()?.takeIf { it >= 0L }
            ?: clients[id]?.signalLatencyMs
        val clockOffset = query["clockOffsetMs"]?.toLongOrNull()
            ?: clients[id]?.clockOffsetMs
        val timeSyncErrorBound = query["timeSyncErrorBoundMs"]?.toLongOrNull()?.takeIf { it >= 0L }
            ?: clients[id]?.timeSyncErrorBoundMs
        val lastStartDelta = query["lastStartDeltaMs"]?.toLongOrNull()
            ?: clients[id]?.lastStartDeltaMs
        val startCorrection = query["startCorrectionMs"]?.toLongOrNull()
            ?: clients[id]?.startCorrectionMs
        val address = socket.inetAddress?.hostAddress ?: "unknown"
        clients[id] = TrackedClient(
            id = id,
            name = name,
            address = address,
            signalLatencyMs = signalLatency,
            clockOffsetMs = clockOffset,
            timeSyncErrorBoundMs = timeSyncErrorBound,
            lastStartDeltaMs = lastStartDelta,
            startCorrectionMs = startCorrection,
            cachedAudioRevision = cachedRevision,
            cacheStatus = cacheStatus,
            cacheProgressPercent = cacheProgress,
            lastSeenElapsedMs = SystemClock.elapsedRealtime()
        )
    }

    private fun currentVisibleClients(): List<ClientConnectionInfo> {
        val now = SystemClock.elapsedRealtime()
        clients.entries.removeIf { now - it.value.lastSeenElapsedMs > 15_000L }
        return clients.values
            .filter { now - it.lastSeenElapsedMs <= 5_000L }
            .sortedWith(compareBy<TrackedClient> { it.name }.thenBy { it.address })
            .map {
                ClientConnectionInfo(
                    id = it.id,
                    name = it.name,
                    address = it.address,
                    signalLatencyMs = it.signalLatencyMs,
                    clockOffsetMs = it.clockOffsetMs,
                    timeSyncErrorBoundMs = it.timeSyncErrorBoundMs,
                    lastStartDeltaMs = it.lastStartDeltaMs,
                    startCorrectionMs = it.startCorrectionMs,
                    cachedAudioRevision = it.cachedAudioRevision,
                    cacheStatus = it.cacheStatus,
                    cacheProgressPercent = it.cacheProgressPercent,
                    isCurrentAudioCached = clientAudioFile?.exists() != true || isClientCachedForCurrentAudio(it),
                    lastSeenAgoMs = now - it.lastSeenElapsedMs
                )
            }
    }

    private fun allVisibleClientsCached(): Boolean {
        if (clientAudioFile?.exists() != true || audioRevision <= 0L) return true
        val now = SystemClock.elapsedRealtime()
        val visibleClients = clients.values.filter { now - it.lastSeenElapsedMs <= 5_000L }
        return visibleClients.all(::isClientCachedForCurrentAudio)
    }

    private fun allVisibleClientsTimeSynced(
        visibleClients: List<ClientConnectionInfo>,
        readyErrorLimitMs: Long
    ): Boolean =
        visibleClients.isEmpty() || visibleClients.all { it.isTimeSyncReady(readyErrorLimitMs) }

    private fun readyTimeSyncErrorLimitMs(clients: List<ClientConnectionInfo>): Long {
        val observed = clients.mapNotNull { client ->
            val latency = client.signalLatencyMs
            val error = client.timeSyncErrorBoundMs
            when {
                error != null -> error
                latency != null -> latency / 2L
                else -> null
            }
        }
        if (observed.isEmpty()) return BASE_READY_TIME_SYNC_ERROR_MS

        val sorted = observed.sorted()
        val median = sorted[sorted.size / 2]
        val high = sorted[((sorted.size - 1) * 75) / 100]
        return (maxOf(median * 2L, high + READY_TIME_SYNC_ERROR_MARGIN_MS))
            .coerceIn(MIN_READY_TIME_SYNC_ERROR_MS, MAX_READY_TIME_SYNC_ERROR_MS)
    }

    private fun ClientConnectionInfo.isTimeSyncReady(errorLimitMs: Long): Boolean =
        clockOffsetMs != null && (timeSyncErrorBoundMs ?: Long.MAX_VALUE) <= errorLimitMs

    private fun isClientCachedForCurrentAudio(client: TrackedClient): Boolean =
        client.cachedAudioRevision == audioRevision && client.cacheStatus == "cached"

    private fun readAudioSelection(uri: Uri): AudioSelection {
        val resolver = appContext.contentResolver
        var displayName = uri.lastPathSegment ?: "audio"
        var size = -1L

        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex >= 0) {
                        displayName = cursor.getString(nameIndex) ?: displayName
                    }
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        size = cursor.getLong(sizeIndex)
                    }
                }
            }

        val duration = MediaMetadataRetriever().useCompat { retriever ->
            retriever.setDataSource(appContext, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 0L
        }

        return AudioSelection(
            uri = uri,
            displayName = displayName,
            durationMs = duration,
            sizeBytes = size,
            mimeType = resolver.getType(uri) ?: guessMimeType(displayName)
        )
    }

    private fun copyToStreamCache(uri: Uri, displayName: String): File {
        val extension = displayName.substringAfterLast('.', "bin").take(12)
        val target = File(appContext.cacheDir, "bandsync-client-output-${System.currentTimeMillis()}.$extension")
        appContext.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法打开音频文件" }
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    private fun findLocalIpv4Addresses(): List<String> {
        val addresses = NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { networkInterface -> networkInterface.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filterNot { it.isLoopbackAddress }
            .map { it.hostAddress ?: "" }
            .filter { it.isNotBlank() }
        return addresses.ifEmpty { listOf("127.0.0.1") }
    }

    private fun writeJsonResponse(socket: Socket, json: JSONObject) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        writeResponse(socket, 200, "OK", "application/json; charset=utf-8", bytes)
    }

    private fun writeTextResponse(socket: Socket, statusCode: Int, message: String) {
        writeResponse(
            socket = socket,
            statusCode = statusCode,
            statusText = when (statusCode) {
                400 -> "Bad Request"
                404 -> "Not Found"
                416 -> "Range Not Satisfiable"
                else -> "Error"
            },
            contentType = "text/plain; charset=utf-8",
            body = message.toByteArray(Charsets.UTF_8)
        )
    }

    private fun writeResponse(
        socket: Socket,
        statusCode: Int,
        statusText: String,
        contentType: String,
        body: ByteArray
    ) {
        val headers = buildString {
            append("HTTP/1.1 ").append(statusCode).append(' ').append(statusText).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        val output = socket.getOutputStream()
        output.write(headers.toByteArray(Charsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

    private fun parseRange(rangeHeader: String?, totalLength: Long): Pair<Long, Long>? {
        if (rangeHeader.isNullOrBlank() || !rangeHeader.startsWith("bytes=")) return null
        val range = rangeHeader.removePrefix("bytes=").substringBefore(',')
        val startText = range.substringBefore('-', "")
        val endText = range.substringAfter('-', "")
        val start = startText.toLongOrNull() ?: return null
        val end = endText.toLongOrNull() ?: (totalLength - 1L)
        return start to min(end, totalLength - 1L)
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split('&')
            .mapNotNull { item ->
                val key = item.substringBefore('=', "")
                if (key.isBlank()) return@mapNotNull null
                val value = item.substringAfter('=', "")
                decodeUrl(key) to decodeUrl(value)
            }
            .toMap()
    }

    private fun decodeUrl(value: String): String =
        URLDecoder.decode(value, Charsets.UTF_8.name())

    private fun readAsciiLine(input: BufferedInputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) return if (buffer.size() == 0) null else buffer.toString(Charsets.UTF_8.name())
            if (value == '\n'.code) break
            if (value != '\r'.code) buffer.write(value)
        }
        return buffer.toString(Charsets.UTF_8.name())
    }

    private fun skipFully(input: FileInputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped <= 0L) {
                if (input.read() == -1) break
                remaining -= 1L
            } else {
                remaining -= skipped
            }
        }
    }

    private fun seekToCompat(player: MediaPlayer, positionMs: Long) {
        player.seekTo(positionMs.coerceAtLeast(0L), MediaPlayer.SEEK_CLOSEST)
    }

    private fun Long.formatSignedMs(): String =
        if (this >= 0L) "+${this} ms" else "$this ms"

    private suspend fun waitUntilElapsedRealtime(targetElapsedMs: Long) {
        while (true) {
            val remainingMs = targetElapsedMs - SystemClock.elapsedRealtime()
            when {
                remainingMs <= 0L -> return
                remainingMs > PRECISE_WAIT_WINDOW_MS -> delay(remainingMs - PRECISE_WAIT_WINDOW_MS)
                remainingMs > 2L -> delay(1L)
                else -> Thread.yield()
            }
        }
    }

    private fun commandStatus(
        command: RemoteCommand,
        mode: PlaybackSyncMode,
        executeAtWallClockMs: Long
    ): String =
        when (command) {
            RemoteCommand.PREPARE -> "网络信号同步预热中，稍后下发开始命令"

            RemoteCommand.PLAY -> if (mode == PlaybackSyncMode.DEVICE_TIME && executeAtWallClockMs > 0L) {
                "已下发开始命令，将在设备时间 $executeAtWallClockMs 执行"
            } else {
                "已下发开始命令，客户端收到后立即执行"
            }

            RemoteCommand.PAUSE -> "已下发暂停命令，客户端收到后立即执行"

            RemoteCommand.STOP -> "已下发停止命令，客户端收到后立即执行"

            RemoteCommand.IDLE -> "等待命令"
        }

    private fun guessMimeType(name: String): String =
        when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
            "aac" -> "audio/aac"
            "flac" -> "audio/flac"
            "m4a", "mp4" -> "audio/mp4"
            "ogg", "oga" -> "audio/ogg"
            "wav" -> "audio/wav"
            else -> "audio/mpeg"
        }

    private fun <T> MediaMetadataRetriever.useCompat(block: (MediaMetadataRetriever) -> T): T =
        try {
            block(this)
        } finally {
            release()
        }

    private data class CommandSnapshot(
        val id: Long,
        val command: RemoteCommand,
        val syncMode: PlaybackSyncMode,
        val startPositionMs: Long,
        val executeAtWallClockMs: Long
    )

    private data class TrackedClient(
        val id: String,
        val name: String,
        val address: String,
        val signalLatencyMs: Long?,
        val clockOffsetMs: Long?,
        val timeSyncErrorBoundMs: Long?,
        val lastStartDeltaMs: Long?,
        val startCorrectionMs: Long?,
        val cachedAudioRevision: Long,
        val cacheStatus: String,
        val cacheProgressPercent: Int,
        val lastSeenElapsedMs: Long
    )

    private data class EventClient(
        val id: String,
        val socket: Socket,
        val output: OutputStream,
        var sendJob: Job? = null
    )

    private companion object {
        const val DEVICE_TIME_COMMAND_LEAD_MS = 3_000L
        const val NETWORK_SIGNAL_WARMUP_MS = 300L
        const val BASE_READY_TIME_SYNC_ERROR_MS = 500L
        const val MIN_READY_TIME_SYNC_ERROR_MS = 250L
        const val MAX_READY_TIME_SYNC_ERROR_MS = 1_500L
        const val READY_TIME_SYNC_ERROR_MARGIN_MS = 200L
        const val PRECISE_WAIT_WINDOW_MS = 12L
        const val PRE_SEEK_TOLERANCE_MS = 25L
        const val EVENT_KEEPALIVE_MS = 30_000L
    }
}
