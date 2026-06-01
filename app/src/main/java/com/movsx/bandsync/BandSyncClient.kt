package com.movsx.bandsync

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

class BandSyncClient(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val clientId = UUID.randomUUID().toString()
    private val clientName = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "Android Client" }
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(
        ClientUiState(
            serverHostInput = preferences.getString(KEY_SERVER_HOST, "").orEmpty(),
            portInput = preferences.getString(KEY_SERVER_PORT, DEFAULT_BANDSYNC_PORT.toString())
                ?: DEFAULT_BANDSYNC_PORT.toString()
        )
    )
    val uiState: StateFlow<ClientUiState> = _uiState.asStateFlow()

    private var baseUrl: String = ""
    private var eventJob: Job? = null
    private var cacheJob: Job? = null
    private var commandJob: Job? = null
    private var timeSyncJob: Job? = null
    private var eventConnection: HttpURLConnection? = null
    private var player: MediaPlayer? = null
    private var playerPrepared = false
    private var currentAudioRevision = -1L
    private var latestSnapshot: RemoteControlSnapshot? = null
    private var cachedAudioFile: File? = null
    private var cachedAudioRevision: Long = -1L
    private var cachingAudioRevision: Long = -1L
    private var cacheStatus: String = "missing"
    private var cacheProgressPercent: Int = 0
    private var lastReportedCacheProgress: Int = -1
    private var lastAppliedCommandId: Long = 0L
    private var signalLatencyMs: Long? = null
    private var clockOffsetMs: Long? = null
    private var timeSyncErrorBoundMs: Long? = null
    private var successfulTimeSyncCount: Int = 0
    private val timeSamples = ArrayDeque<TimeSample>()

    fun setHostInput(value: String) {
        val normalized = value.trim()
        preferences.edit().putString(KEY_SERVER_HOST, normalized).apply()
        _uiState.update { it.copy(serverHostInput = normalized, errorMessage = null) }
    }

    fun setPortInput(value: String) {
        val filtered = value.filter { it.isDigit() }.take(5)
        preferences.edit().putString(KEY_SERVER_PORT, filtered).apply()
        _uiState.update { it.copy(portInput = filtered, errorMessage = null) }
    }

    fun connect() {
        if (_uiState.value.isConnected) return

        val host = normalizeHost(_uiState.value.serverHostInput)
        val port = _uiState.value.portInput.toIntOrNull()
        if (host.isBlank()) {
            _uiState.update { it.copy(errorMessage = "请输入服务器 IP") }
            return
        }
        if (port == null || port !in 1..65535) {
            _uiState.update { it.copy(errorMessage = "端口必须在 1 到 65535 之间") }
            return
        }

        baseUrl = "http://$host:$port"
        _uiState.update { it.copy(status = "正在连接事件通道...", errorMessage = null) }
        eventJob = ioScope.launch { eventLoop() }
        timeSyncJob = ioScope.launch { timeSyncLoop() }
    }

    fun disconnect() {
        eventJob?.cancel()
        cacheJob?.cancel()
        commandJob?.cancel()
        timeSyncJob?.cancel()
        runCatching { eventConnection?.disconnect() }
        eventConnection = null
        eventJob = null
        cacheJob = null
        commandJob = null
        timeSyncJob = null
        releasePlayer()
        latestSnapshot = null
        currentAudioRevision = -1L
        lastAppliedCommandId = 0L
        signalLatencyMs = null
        clockOffsetMs = null
        timeSyncErrorBoundMs = null
        successfulTimeSyncCount = 0
        timeSamples.clear()
        if (cacheStatus == "downloading") {
            cachingAudioRevision = -1L
            cacheStatus = if (cachedAudioRevision >= 0L) "cached" else "missing"
            cacheProgressPercent = if (cachedAudioRevision >= 0L) 100 else 0
        }
        _uiState.update {
            it.copy(
                isConnected = false,
                hasRemoteAudio = false,
                remoteAudioName = null,
                isPlaying = false,
                playbackPositionMs = 0L,
                durationMs = 0L,
                signalLatencyMs = null,
                clockOffsetMs = null,
                timeSyncErrorBoundMs = null,
                isCachingRemoteAudio = false,
                cacheProgress = cacheProgressPercent / 100f,
                cachedAudioRevision = cachedAudioRevision,
                status = "已断开"
            )
        }
    }

    fun clearCache() {
        cacheJob?.cancel()
        commandJob?.cancel()
        cacheJob = null
        commandJob = null
        releasePlayer()
        cachedAudioFile = null
        cachedAudioRevision = -1L
        cachingAudioRevision = -1L
        cacheStatus = "missing"
        cacheProgressPercent = 0
        currentAudioRevision = -1L
        lastAppliedCommandId = 0L
        appContext.cacheDir.listFiles()
            ?.filter { it.name.startsWith(CLIENT_CACHE_PREFIX) }
            ?.forEach { it.delete() }
        _uiState.update {
            it.copy(
                isCachingRemoteAudio = false,
                cacheProgress = 0f,
                cachedAudioRevision = -1L,
                isPlaying = false,
                playbackPositionMs = 0L,
                status = if (it.isConnected) "缓存已清除，等待重新缓存" else "缓存已清除"
            )
        }
        reportClientStatus(force = true)
        latestSnapshot?.let { snapshot ->
            if (snapshot.hasClientAudio && _uiState.value.isConnected) {
                startCacheDownloadIfNeeded(snapshot)
            }
        }
    }

    override fun close() {
        disconnect()
        ioScope.cancel()
        mainScope.cancel()
    }

    private suspend fun eventLoop() = withContext(Dispatchers.IO) {
        var connected = false
        val connection = (URL("$baseUrl/events?${clientQuery()}").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 3_000
            readTimeout = 0
            useCaches = false
        }
        eventConnection = connection

        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val body = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                throw IOException("HTTP $code $body")
            }
            connected = true
            withContext(Dispatchers.Main.immediate) {
                _uiState.update { it.copy(isConnected = true, status = "已连接事件通道", errorMessage = null) }
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val json = JSONObject(line)
                    if (json.optString("type") == "keepalive") continue
                    val snapshot = parseSnapshot(json)
                    withContext(Dispatchers.Main.immediate) {
                        applySnapshot(snapshot)
                    }
                }
            }
        } catch (error: Exception) {
            withContext(Dispatchers.Main.immediate) {
                if (_uiState.value.status != "已断开") {
                    val fallback = if (connected) "服务器事件通道已断开" else "无法连接服务器事件通道"
                    handleConnectionLost(error.localizedMessage ?: fallback)
                }
            }
        } finally {
            if (eventConnection === connection) {
                eventConnection = null
            }
            connection.disconnect()
        }
    }

    private suspend fun timeSyncLoop() {
        while (true) {
            if (_uiState.value.isConnected) {
                try {
                    measureTimeDifference()
                } catch (_: Exception) {
                    // The event connection is responsible for connection loss; diagnostics are best effort.
                }
                delay(
                    if (successfulTimeSyncCount < INITIAL_TIME_SYNC_SAMPLES) {
                        FAST_TIME_SYNC_INTERVAL_MS
                    } else {
                        TIME_SYNC_INTERVAL_MS
                    }
                )
            } else {
                delay(250L)
            }
        }
    }

    private suspend fun measureTimeDifference() {
        val clientSendWallClockMs = System.currentTimeMillis()
        val response = httpGet("$baseUrl/time-sync?${clientQuery()}&clientSendWallClockMs=$clientSendWallClockMs")
        val clientReceiveWallClockMs = System.currentTimeMillis()
        val json = JSONObject(response)
        val serverReceiveWallClockMs = json.optLong("serverReceiveWallClockMs", 0L)
        val serverSendWallClockMs = json.optLong("serverSendWallClockMs", serverReceiveWallClockMs)
        if (serverReceiveWallClockMs <= 0L || serverSendWallClockMs <= 0L) return

        val serverProcessingMs = (serverSendWallClockMs - serverReceiveWallClockMs).coerceAtLeast(0L)
        val roundTripMs = (
            clientReceiveWallClockMs - clientSendWallClockMs - serverProcessingMs
        ).coerceAtLeast(0L)
        val estimatedOffsetMs = (
            (serverReceiveWallClockMs - clientSendWallClockMs) +
                (serverSendWallClockMs - clientReceiveWallClockMs)
        ) / 2L

        recordTimeSample(roundTripMs, estimatedOffsetMs)
        val stableLatencyMs = signalLatencyMs ?: roundTripMs
        val stableOffsetMs = clockOffsetMs ?: estimatedOffsetMs
        val stableErrorBoundMs = timeSyncErrorBoundMs ?: estimatedErrorBound(roundTripMs, 0L)
        _uiState.update {
            it.copy(
                signalLatencyMs = stableLatencyMs,
                clockOffsetMs = stableOffsetMs,
                timeSyncErrorBoundMs = stableErrorBoundMs
            )
        }
        reportClientStatus(force = true)
    }

    private fun recordTimeSample(roundTripMs: Long, offsetMs: Long) {
        if (roundTripMs > MAX_ACCEPTED_TIME_SYNC_RTT_MS) return

        timeSamples.addLast(TimeSample(roundTripMs = roundTripMs, offsetMs = offsetMs))
        while (timeSamples.size > TIME_SYNC_SAMPLE_WINDOW) {
            timeSamples.removeFirst()
        }

        val bestOffsetSample = timeSamples.minByOrNull { it.roundTripMs } ?: return
        val sortedLatencies = timeSamples.map { it.roundTripMs }.sorted()
        val medianLatency = sortedLatencies[sortedLatencies.size / 2]
        val latencyJitterMs = (sortedLatencies.last() - sortedLatencies.first()).coerceAtLeast(0L)

        signalLatencyMs = smoothValue(signalLatencyMs, medianLatency, previousWeight = 3)
        clockOffsetMs = smoothOffset(clockOffsetMs, bestOffsetSample.offsetMs)
        timeSyncErrorBoundMs = smoothValue(
            timeSyncErrorBoundMs,
            estimatedErrorBound(bestOffsetSample.roundTripMs, latencyJitterMs),
            previousWeight = 2
        )
        successfulTimeSyncCount += 1
    }

    private fun smoothValue(current: Long?, candidate: Long, previousWeight: Int): Long =
        if (current == null) {
            candidate
        } else {
            ((current * previousWeight) + candidate) / (previousWeight + 1)
        }

    private fun estimatedErrorBound(bestRoundTripMs: Long, latencyJitterMs: Long): Long =
        (bestRoundTripMs / 2L + latencyJitterMs / 2L).coerceAtLeast(1L)

    private fun smoothOffset(current: Long?, candidate: Long): Long {
        if (current == null) return candidate
        if (kotlin.math.abs(candidate - current) > CLOCK_OFFSET_JUMP_RESET_MS) return candidate
        return smoothValue(current, candidate, previousWeight = 4)
    }

    private fun applySnapshot(snapshot: RemoteControlSnapshot) {
        latestSnapshot = snapshot
        _uiState.update {
            it.copy(
                isConnected = true,
                hasRemoteAudio = snapshot.hasClientAudio,
                remoteAudioName = snapshot.fileName,
                isCachingRemoteAudio = cacheStatus == "downloading",
                cacheProgress = cacheProgressPercent / 100f,
                cachedAudioRevision = cachedAudioRevision,
                durationMs = snapshot.durationMs,
                status = statusForSnapshot(snapshot),
                errorMessage = null
            )
        }

        if (!snapshot.hasClientAudio || snapshot.audioRevision <= 0L) {
            clearRemoteAudioState()
            return
        }

        startCacheDownloadIfNeeded(snapshot)

        if (cachedAudioRevision != snapshot.audioRevision || cachedAudioFile?.exists() != true) {
            releasePlayer()
            return
        }

        if (player == null || currentAudioRevision != snapshot.audioRevision) {
            prepareLocalPlayer(snapshot.audioRevision)
        }
        applyRemoteCommandIfReady(snapshot)
    }

    private fun statusForSnapshot(snapshot: RemoteControlSnapshot): String =
        when {
            !snapshot.hasClientAudio -> "已连接，等待服务端加载 Clients Output"
            cacheStatus == "downloading" -> "正在缓存整首音频 $cacheProgressPercent%"
            cachedAudioRevision == snapshot.audioRevision -> if (_uiState.value.isPlaying) {
                "正在按服务端命令播放"
            } else {
                "已缓存，等待服务端命令"
            }
            else -> "等待缓存 Clients Output"
        }

    private fun clearRemoteAudioState() {
        commandJob?.cancel()
        releasePlayer()
        cachedAudioFile?.delete()
        cachedAudioFile = null
        cachedAudioRevision = -1L
        cachingAudioRevision = -1L
        cacheStatus = "missing"
        cacheProgressPercent = 0
        currentAudioRevision = -1L
        lastAppliedCommandId = 0L
        _uiState.update {
            it.copy(
                isCachingRemoteAudio = false,
                cacheProgress = 0f,
                cachedAudioRevision = -1L,
                isPlaying = false,
                playbackPositionMs = 0L
            )
        }
        reportClientStatus(force = true)
    }

    private fun prepareLocalPlayer(audioRevision: Long) {
        val localFile = cachedAudioFile ?: return
        if (!localFile.exists()) return

        releasePlayer()
        currentAudioRevision = audioRevision
        playerPrepared = false

        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setOnPreparedListener {
                    playerPrepared = true
                    latestSnapshot?.let { snapshot -> applyRemoteCommandIfReady(snapshot) }
                }
                setOnCompletionListener {
                    _uiState.update { state ->
                        state.copy(isPlaying = false, playbackPositionMs = state.durationMs)
                    }
                }
                setOnErrorListener { _, _, _ ->
                    playerPrepared = false
                    releasePlayer()
                    currentAudioRevision = -1L
                    _uiState.update { it.copy(errorMessage = "本地缓存音频播放失败，请清除缓存后重试") }
                    true
                }
                setDataSource(localFile.absolutePath)
                prepareAsync()
            }
        } catch (error: Exception) {
            releasePlayer()
            currentAudioRevision = -1L
            _uiState.update {
                it.copy(errorMessage = error.localizedMessage ?: "无法打开本地缓存音频")
            }
        }
    }

    private fun applyRemoteCommandIfReady(snapshot: RemoteControlSnapshot) {
        if (snapshot.commandId <= lastAppliedCommandId) return

        if (snapshot.command == RemoteCommand.PLAY) {
            if (cachedAudioRevision != snapshot.audioRevision || cachedAudioFile?.exists() != true) {
                _uiState.update { it.copy(status = "已收到开始命令，等待缓存完成") }
                return
            }
            if (player == null || currentAudioRevision != snapshot.audioRevision) {
                prepareLocalPlayer(snapshot.audioRevision)
                return
            }
            if (!playerPrepared) return
        }

        lastAppliedCommandId = snapshot.commandId
        scheduleRemoteCommand(snapshot)
    }

    private fun scheduleRemoteCommand(snapshot: RemoteControlSnapshot) {
        commandJob?.cancel()
        commandJob = mainScope.launch {
            if (snapshot.command == RemoteCommand.PLAY && snapshot.syncMode == PlaybackSyncMode.DEVICE_TIME) {
                repeat(PRE_PLAY_TIME_SYNC_ATTEMPTS) {
                    runCatching { measureTimeDifference() }
                }
            }
            val delayMs = commandDelayMs(snapshot)
            if (delayMs > 0L) delay(delayMs)
            when (snapshot.command) {
                RemoteCommand.PLAY -> startLocalPlayback(snapshot)
                RemoteCommand.PAUSE -> pauseLocalPlayback("已按服务端命令暂停")
                RemoteCommand.STOP -> stopLocalPlayback("已按服务端命令停止")
                RemoteCommand.IDLE -> Unit
            }
        }
    }

    private fun startLocalPlayback(snapshot: RemoteControlSnapshot) {
        val mediaPlayer = player ?: return
        if (!playerPrepared) return

        val latenessMs = if (
            snapshot.syncMode == PlaybackSyncMode.DEVICE_TIME &&
            snapshot.executeAtWallClockMs > 0L
        ) {
            (estimatedServerWallClockMs() - snapshot.executeAtWallClockMs).coerceAtLeast(0L)
        } else {
            0L
        }
        val target = (snapshot.startPositionMs + latenessMs).coerceIn(0L, snapshot.durationMs)
        if (target >= snapshot.durationMs) {
            stopLocalPlayback("开始命令已过期，保持停止")
            return
        }

        runCatching {
            seekToCompat(mediaPlayer, target)
            mediaPlayer.start()
            _uiState.update {
                it.copy(
                    isPlaying = true,
                    playbackPositionMs = target,
                    status = "正在按服务端命令播放",
                    errorMessage = null
                )
            }
        }.onFailure { error ->
            _uiState.update { it.copy(errorMessage = error.localizedMessage ?: "播放失败") }
        }
    }

    private fun pauseLocalPlayback(status: String) {
        val position = runCatching {
            val mediaPlayer = player
            if (playerPrepared && mediaPlayer != null) {
                if (mediaPlayer.isPlaying) mediaPlayer.pause()
                mediaPlayer.currentPosition.toLong()
            } else {
                _uiState.value.playbackPositionMs
            }
        }.getOrDefault(_uiState.value.playbackPositionMs)

        _uiState.update {
            it.copy(
                isPlaying = false,
                playbackPositionMs = position,
                status = status,
                errorMessage = null
            )
        }
    }

    private fun stopLocalPlayback(status: String) {
        runCatching {
            val mediaPlayer = player
            if (playerPrepared && mediaPlayer != null) {
                if (mediaPlayer.isPlaying) mediaPlayer.pause()
                seekToCompat(mediaPlayer, 0L)
            }
        }
        _uiState.update {
            it.copy(
                isPlaying = false,
                playbackPositionMs = 0L,
                status = status,
                errorMessage = null
            )
        }
    }

    private fun releasePlayer() {
        runCatching {
            player?.release()
            player = null
            playerPrepared = false
        }
    }

    private fun startCacheDownloadIfNeeded(snapshot: RemoteControlSnapshot) {
        if (!snapshot.hasClientAudio || snapshot.audioRevision <= 0L) return
        if (cachedAudioRevision == snapshot.audioRevision && cachedAudioFile?.exists() == true) {
            cacheStatus = "cached"
            cacheProgressPercent = 100
            reportClientStatus(force = true)
            return
        }
        if (
            cacheJob?.isActive == true &&
            cacheStatus == "downloading" &&
            cachingAudioRevision == snapshot.audioRevision
        ) {
            return
        }

        cacheJob?.cancel()
        commandJob?.cancel()
        releasePlayer()
        currentAudioRevision = -1L
        cachingAudioRevision = snapshot.audioRevision
        cacheStatus = "downloading"
        cacheProgressPercent = 0
        _uiState.update {
            it.copy(
                isCachingRemoteAudio = true,
                cacheProgress = 0f,
                status = "正在缓存整首音频 0%",
                errorMessage = null
            )
        }
        reportClientStatus(force = true)

        cacheJob = ioScope.launch {
            val revision = snapshot.audioRevision
            val tempFile = File(appContext.cacheDir, "$CLIENT_CACHE_PREFIX$revision.tmp")
            val targetFile = File(appContext.cacheDir, "$CLIENT_CACHE_PREFIX$revision.audio")

            try {
                downloadClientAudio(snapshot, tempFile) { progress ->
                    cacheProgressPercent = progress
                    _uiState.update {
                        it.copy(
                            isCachingRemoteAudio = true,
                            cacheProgress = progress / 100f,
                            status = "正在缓存整首音频 $progress%"
                        )
                    }
                    reportClientStatus()
                }

                if (targetFile.exists()) targetFile.delete()
                if (!tempFile.renameTo(targetFile)) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }

                cachedAudioFile?.takeIf { it.absolutePath != targetFile.absolutePath }?.delete()
                cachedAudioFile = targetFile
                cachedAudioRevision = revision
                cachingAudioRevision = -1L
                cacheStatus = "cached"
                cacheProgressPercent = 100
                reportClientStatus(force = true)

                _uiState.update {
                    it.copy(
                        isCachingRemoteAudio = false,
                        cacheProgress = 1f,
                        cachedAudioRevision = revision,
                        status = "整首音频已缓存，等待服务端命令",
                        errorMessage = null
                    )
                }

                withContext(Dispatchers.Main.immediate) {
                    latestSnapshot?.let { latest ->
                        if (latest.audioRevision == revision) {
                            prepareLocalPlayer(revision)
                            applyRemoteCommandIfReady(latest)
                        }
                    }
                }
            } catch (error: Exception) {
                tempFile.delete()
                if (error is CancellationException) throw error
                if (cachedAudioRevision != revision) {
                    cachingAudioRevision = -1L
                    cacheStatus = "missing"
                    cacheProgressPercent = 0
                }
                _uiState.update {
                    it.copy(
                        isCachingRemoteAudio = false,
                        cacheProgress = cacheProgressPercent / 100f,
                        status = "缓存失败",
                        errorMessage = error.localizedMessage ?: "无法缓存服务器音频"
                    )
                }
                reportClientStatus(force = true)
            }
        }
    }

    private suspend fun downloadClientAudio(
        snapshot: RemoteControlSnapshot,
        targetFile: File,
        onProgress: (Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val url = "$baseUrl/stream?${clientQuery()}&rev=${snapshot.audioRevision}"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 3_000
            readTimeout = 30_000
            useCaches = false
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val body = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                throw IOException("HTTP $code $body")
            }

            val totalBytes = when {
                snapshot.audioSizeBytes > 0L -> snapshot.audioSizeBytes
                connection.contentLengthLong > 0L -> connection.contentLengthLong
                else -> -1L
            }
            var copied = 0L
            var lastProgress = -1
            connection.inputStream.use { input ->
                targetFile.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read.toLong()
                        val progress = if (totalBytes > 0L) {
                            ((copied * 100L) / totalBytes).toInt().coerceIn(0, 99)
                        } else {
                            0
                        }
                        if (progress != lastProgress) {
                            lastProgress = progress
                            onProgress(progress)
                        }
                    }
                }
            }
            onProgress(100)
        } finally {
            connection.disconnect()
        }
    }

    private fun handleConnectionLost(message: String) {
        eventJob?.cancel()
        cacheJob?.cancel()
        commandJob?.cancel()
        timeSyncJob?.cancel()
        runCatching { eventConnection?.disconnect() }
        eventConnection = null
        eventJob = null
        cacheJob = null
        commandJob = null
        timeSyncJob = null
        releasePlayer()
        signalLatencyMs = null
        clockOffsetMs = null
        timeSyncErrorBoundMs = null
        successfulTimeSyncCount = 0
        timeSamples.clear()
        _uiState.update {
            it.copy(
                isConnected = false,
                isPlaying = false,
                signalLatencyMs = null,
                clockOffsetMs = null,
                timeSyncErrorBoundMs = null,
                status = "连接已断开",
                errorMessage = message
            )
        }
    }

    private fun reportClientStatus(force: Boolean = false) {
        if (baseUrl.isBlank() || !_uiState.value.isConnected) return

        val progressBucket = (cacheProgressPercent / 10) * 10
        if (force) {
            lastReportedCacheProgress = progressBucket
        } else {
            if (cacheStatus != "downloading" || progressBucket == lastReportedCacheProgress) return
            lastReportedCacheProgress = progressBucket
        }

        ioScope.launch {
            try {
                httpGet("$baseUrl/client-status?${clientQuery()}")
            } catch (_: Exception) {
                // Status reports are best effort; the event connection owns disconnect handling.
            }
        }
    }

    private fun clientQuery(): String = buildString {
        append("clientId=").append(urlEncode(clientId))
        append("&name=").append(urlEncode(clientName))
        append("&cachedRevision=").append(cachedAudioRevision)
        append("&cacheStatus=").append(urlEncode(cacheStatus))
        append("&cacheProgress=").append(cacheProgressPercent)
        signalLatencyMs?.let { append("&signalLatencyMs=").append(it) }
        clockOffsetMs?.let { append("&clockOffsetMs=").append(it) }
        timeSyncErrorBoundMs?.let { append("&timeSyncErrorBoundMs=").append(it) }
    }

    private fun parseSnapshot(response: String): RemoteControlSnapshot =
        parseSnapshot(JSONObject(response))

    private fun parseSnapshot(json: JSONObject): RemoteControlSnapshot {
        val fileName = json.optString("fileName", "").takeIf { it.isNotBlank() }
        return RemoteControlSnapshot(
            hasClientAudio = json.optBoolean("hasClientAudio", false),
            fileName = fileName,
            durationMs = json.optLong("durationMs", 0L),
            audioRevision = json.optLong("audioRevision", 0L),
            audioSizeBytes = json.optLong("audioSizeBytes", 0L),
            commandId = json.optLong("commandId", 0L),
            command = parseCommand(json.optString("command", RemoteCommand.IDLE.name)),
            syncMode = parseSyncMode(json.optString("syncMode", PlaybackSyncMode.DEVICE_TIME.name)),
            startPositionMs = json.optLong("startPositionMs", 0L),
            executeAtWallClockMs = json.optLong("executeAtWallClockMs", 0L)
        )
    }

    private suspend fun httpGet(url: String): String = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 2_000
            readTimeout = 4_000
            useCaches = false
        }

        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IOException("HTTP $code $body")
            }
            body
        } finally {
            connection.disconnect()
        }
    }

    private fun commandDelayMs(snapshot: RemoteControlSnapshot): Long =
        if (snapshot.syncMode == PlaybackSyncMode.DEVICE_TIME) {
            (snapshot.executeAtWallClockMs - estimatedServerWallClockMs()).coerceAtLeast(0L)
        } else {
            0L
        }

    private fun estimatedServerWallClockMs(): Long =
        System.currentTimeMillis() + (clockOffsetMs ?: 0L)

    private fun seekToCompat(player: MediaPlayer, positionMs: Long) {
        player.seekTo(positionMs.coerceAtLeast(0L), MediaPlayer.SEEK_CLOSEST)
    }

    private fun normalizeHost(value: String): String =
        value.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore('/')
            .substringBefore(':')

    private fun parseCommand(value: String): RemoteCommand =
        runCatching { RemoteCommand.valueOf(value) }.getOrDefault(RemoteCommand.IDLE)

    private fun parseSyncMode(value: String): PlaybackSyncMode =
        runCatching { PlaybackSyncMode.valueOf(value) }.getOrDefault(PlaybackSyncMode.DEVICE_TIME)

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private data class TimeSample(
        val roundTripMs: Long,
        val offsetMs: Long
    )

    private companion object {
        const val PREFS_NAME = "bandsync-client"
        const val KEY_SERVER_HOST = "server_host"
        const val KEY_SERVER_PORT = "server_port"
        const val CLIENT_CACHE_PREFIX = "bandsync-client-cache-"
        const val INITIAL_TIME_SYNC_SAMPLES = 5
        const val FAST_TIME_SYNC_INTERVAL_MS = 500L
        const val TIME_SYNC_INTERVAL_MS = 3_000L
        const val TIME_SYNC_SAMPLE_WINDOW = 9
        const val MAX_ACCEPTED_TIME_SYNC_RTT_MS = 2_000L
        const val CLOCK_OFFSET_JUMP_RESET_MS = 1_000L
        const val PRE_PLAY_TIME_SYNC_ATTEMPTS = 2
    }
}
