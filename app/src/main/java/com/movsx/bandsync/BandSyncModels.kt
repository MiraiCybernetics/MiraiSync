package com.movsx.bandsync

import android.net.Uri

const val DEFAULT_BANDSYNC_PORT = 5777

enum class PlaybackSyncMode(val displayName: String) {
    DEVICE_TIME("设备时间同步"),
    NETWORK_SIGNAL("网络信号同步")
}

enum class RemoteCommand {
    IDLE,
    PLAY,
    PAUSE,
    STOP
}

data class AudioSelection(
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val mimeType: String
)

data class ClientConnectionInfo(
    val id: String,
    val name: String,
    val address: String,
    val signalLatencyMs: Long?,
    val clockOffsetMs: Long?,
    val cachedAudioRevision: Long,
    val cacheStatus: String,
    val cacheProgressPercent: Int,
    val isCurrentAudioCached: Boolean,
    val lastSeenAgoMs: Long
)

data class ServerUiState(
    val portInput: String = DEFAULT_BANDSYNC_PORT.toString(),
    val boundPort: Int = DEFAULT_BANDSYNC_PORT,
    val isRunning: Boolean = false,
    val listenAddresses: List<String> = emptyList(),
    val serverAudio: AudioSelection? = null,
    val clientAudio: AudioSelection? = null,
    val isPlaying: Boolean = false,
    val playbackPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val commandId: Long = 0L,
    val command: RemoteCommand = RemoteCommand.IDLE,
    val audioRevision: Long = 0L,
    val syncMode: PlaybackSyncMode = PlaybackSyncMode.DEVICE_TIME,
    val isGeneratingBeat: Boolean = false,
    val detectedBpm: Int? = null,
    val detectedBeatOffsetMs: Long? = null,
    val serverVolume: Float = 1f,
    val clientsVolume: Float = 1f,
    val clients: List<ClientConnectionInfo> = emptyList(),
    val status: String = "服务器未启动",
    val errorMessage: String? = null
)

data class ClientUiState(
    val serverHostInput: String = "",
    val portInput: String = DEFAULT_BANDSYNC_PORT.toString(),
    val isConnected: Boolean = false,
    val hasRemoteAudio: Boolean = false,
    val remoteAudioName: String? = null,
    val isCachingRemoteAudio: Boolean = false,
    val cacheProgress: Float = 0f,
    val cachedAudioRevision: Long = -1L,
    val isPlaying: Boolean = false,
    val playbackPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val signalLatencyMs: Long? = null,
    val clockOffsetMs: Long? = null,
    val localVolume: Float = 1f,
    val remoteVolume: Float = 1f,
    val status: String = "未连接",
    val errorMessage: String? = null
)

data class RemoteControlSnapshot(
    val hasClientAudio: Boolean,
    val fileName: String?,
    val durationMs: Long,
    val audioRevision: Long,
    val audioSizeBytes: Long,
    val clientVolume: Float,
    val commandId: Long,
    val command: RemoteCommand,
    val syncMode: PlaybackSyncMode,
    val startPositionMs: Long,
    val executeAtWallClockMs: Long
)
