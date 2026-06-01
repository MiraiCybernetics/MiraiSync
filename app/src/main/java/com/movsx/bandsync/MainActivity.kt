package com.movsx.bandsync

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.movsx.bandsync.ui.theme.BandSyncTheme
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val server = remember { BandSyncServer(applicationContext) }
            val client = remember { BandSyncClient(applicationContext) }

            DisposableEffect(Unit) {
                onDispose {
                    server.close()
                    client.close()
                }
            }

            BandSyncTheme {
                BandSyncApp(server = server, client = client)
            }
        }
    }
}

@Composable
private fun BandSyncApp(server: BandSyncServer, client: BandSyncClient) {
    var mode by rememberSaveable { mutableStateOf("home") }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (mode) {
            "server" -> ServerScreen(server = server, onBack = { mode = "home" })
            "client" -> ClientScreen(client = client, onBack = { mode = "home" })
            else -> HomeScreen(
                onServer = { mode = "server" },
                onClient = { mode = "client" }
            )
        }
    }
}

@Composable
private fun HomeScreen(onServer: () -> Unit, onClient: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "BandSync",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "局域网音频同步工具",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(12.dp))
        ModeCard(
            title = "服务器模式",
            body = "选择 Server Output 和 Clients Output，统一控制播放、暂停和回到开头。",
            action = "进入服务器",
            onClick = onServer
        )
        ModeCard(
            title = "客户端模式",
            body = "连接服务器后先缓存 Clients Output，再跟随服务器播放指令。",
            action = "进入客户端",
            onClick = onClient
        )
    }
}

@Composable
private fun ModeCard(title: String, body: String, action: String, onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(text = body, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
                Text(action)
            }
        }
    }
}

@Composable
private fun ServerScreen(server: BandSyncServer, onBack: () -> Unit) {
    val state by server.uiState.collectAsState()
    val context = LocalContext.current
    val serverAudioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            persistReadPermission(context.contentResolver, it)
            server.selectServerAudio(it)
        }
    }
    val clientAudioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            persistReadPermission(context.contentResolver, it)
            server.selectClientAudio(it)
        }
    }

    AppScaffold(title = "服务器模式", onBack = onBack) {
        Section(title = "监听") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.portInput,
                    onValueChange = server::setPortInput,
                    label = { Text("端口") },
                    enabled = !state.isRunning,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = { if (state.isRunning) server.stop() else server.start() }) {
                    Text(if (state.isRunning) "停止" else "启动")
                }
            }
            Text(text = state.status, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.listenAddresses.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.listenAddresses.forEach { address ->
                        Text(
                            text = "$address:${state.boundPort}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        Section(title = "音频") {
            AudioPickerRow(
                title = "Server Output",
                description = "服务器本机播放",
                audio = state.serverAudio,
                buttonText = "选择",
                onPick = { serverAudioLauncher.launch(arrayOf("audio/*")) }
            )
            HorizontalDivider()
            AudioPickerRow(
                title = "Clients Output",
                description = "客户端缓存后播放",
                audio = state.clientAudio,
                buttonText = "选择",
                onPick = { clientAudioLauncher.launch(arrayOf("audio/*")) }
            )
            OutlinedButton(
                onClick = server::generateClientBeatFromServerOutput,
                enabled = state.serverAudio != null && !state.isGeneratingBeat,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.isGeneratingBeat) "正在分析并生成节拍..." else "从 Server Output 生成节拍")
            }
            state.detectedBpm?.let { bpm ->
                Text(
                    text = "已检测：$bpm BPM，首拍偏移 ${state.detectedBeatOffsetMs ?: 0L} ms",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(
                onClick = server::clearCache,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("清除服务端缓存")
            }
        }

        Section(title = "同步方式") {
            Text(
                text = "设备时间同步仅开始时下发未来 3 秒的执行时间；暂停和停止始终立即执行。",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                if (state.syncMode == PlaybackSyncMode.DEVICE_TIME) {
                    Button(
                        onClick = { server.setSyncMode(PlaybackSyncMode.DEVICE_TIME) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("设备时间")
                    }
                } else {
                    OutlinedButton(
                        onClick = { server.setSyncMode(PlaybackSyncMode.DEVICE_TIME) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("设备时间")
                    }
                }
                if (state.syncMode == PlaybackSyncMode.NETWORK_SIGNAL) {
                    Button(
                        onClick = { server.setSyncMode(PlaybackSyncMode.NETWORK_SIGNAL) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("网络信号")
                    }
                } else {
                    OutlinedButton(
                        onClick = { server.setSyncMode(PlaybackSyncMode.NETWORK_SIGNAL) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("网络信号")
                    }
                }
            }
            Text(
                text = "当前：${state.syncMode.displayName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Section(title = "播放控制") {
            PlaybackProgress(positionMs = state.playbackPositionMs, durationMs = state.durationMs)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = server::play,
                    enabled = state.durationMs > 0L,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("开始")
                }
                OutlinedButton(
                    onClick = server::pausePlayback,
                    enabled = state.durationMs > 0L,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("暂停")
                }
                OutlinedButton(
                    onClick = server::stopPlayback,
                    enabled = state.durationMs > 0L,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("停止")
                }
            }
        }

        Section(title = "音量") {
            VolumeSlider(label = "本机 Server Output", value = state.serverVolume, onValueChange = server::setServerVolume)
            VolumeSlider(label = "客户端 Clients Output", value = state.clientsVolume, onValueChange = server::setClientsVolume)
        }

        Section(title = "客户端缓存") {
            if (state.clients.isEmpty()) {
                Text("暂无客户端连接", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    state.clients.forEach { client ->
                        ClientInfoRow(client)
                    }
                }
            }
        }

        state.errorMessage?.let { ErrorText(it) }
    }
}

@Composable
private fun ClientScreen(client: BandSyncClient, onBack: () -> Unit) {
    val state by client.uiState.collectAsState()

    AppScaffold(title = "客户端模式", onBack = onBack) {
        Section(title = "连接") {
            OutlinedTextField(
                value = state.serverHostInput,
                onValueChange = client::setHostInput,
                label = { Text("服务器 IP") },
                enabled = !state.isConnected,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.portInput,
                    onValueChange = client::setPortInput,
                    label = { Text("端口") },
                    enabled = !state.isConnected,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = { if (state.isConnected) client.disconnect() else client.connect() }) {
                    Text(if (state.isConnected) "断开" else "连接")
                }
            }
            Text(text = state.status, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = "信号延迟：${state.signalLatencyMs?.let { "$it ms" } ?: "--"}",
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "时间差：${state.clockOffsetMs?.let { formatSignedMs(it) } ?: "--"}（服务端 - 本机）",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Section(title = "远端音频") {
            Text(
                text = state.remoteAudioName ?: "等待服务器加载 Clients Output",
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium
            )
            if (state.hasRemoteAudio) {
                LinearProgressIndicator(
                    progress = { state.cacheProgress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = if (state.isCachingRemoteAudio) {
                        "缓存中 ${(state.cacheProgress * 100).roundToInt()}%"
                    } else if (state.cachedAudioRevision >= 0L) {
                        "已缓存"
                    } else {
                        "未缓存"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            PlaybackProgress(positionMs = state.playbackPositionMs, durationMs = state.durationMs)
            Text(
                text = if (state.isPlaying) "播放中" else "暂停",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = client::clearCache,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("清除客户端缓存")
            }
        }

        Section(title = "本机音量") {
            VolumeSlider(label = "客户端输出", value = state.localVolume, onValueChange = client::setLocalVolume)
            Text(
                text = "服务器音量指令：${(state.remoteVolume * 100).roundToInt()}%",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        state.errorMessage?.let { ErrorText(it) }
    }
}

@Composable
private fun AppScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = onBack) {
                Text("返回")
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun AudioPickerRow(
    title: String,
    description: String,
    audio: AudioSelection?,
    buttonText: String,
    onPick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = audio?.displayName ?: "未选择",
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (audio == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            )
            audio?.let {
                Text(
                    text = "${formatDuration(it.durationMs)} · ${formatBytes(it.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        OutlinedButton(onClick = onPick) {
            Text(buttonText)
        }
    }
}

@Composable
private fun PlaybackProgress(positionMs: Long, durationMs: Long) {
    val progress = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(positionMs), style = MaterialTheme.typography.bodySmall)
            Text(formatDuration(durationMs), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun VolumeSlider(label: String, value: Float, onValueChange: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            Text("${(value * 100).roundToInt()}%")
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = 0f..1f)
    }
}

@Composable
private fun ClientInfoRow(client: ClientConnectionInfo) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(client.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(client.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = "信号延迟：${client.signalLatencyMs?.let { "$it ms" } ?: "--"} · 时间差：${client.clockOffsetMs?.let { formatSignedMs(it) } ?: "--"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = client.cacheDescription(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun ClientConnectionInfo.cacheDescription(): String =
    when {
        isCurrentAudioCached -> "缓存完成"
        cacheStatus == "downloading" -> "缓存中 $cacheProgressPercent%"
        cacheStatus == "cached" -> "已缓存旧版本"
        else -> "未缓存"
    }

@Composable
private fun ErrorText(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium
            )
            .padding(14.dp)
    ) {
        Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

private fun persistReadPermission(resolver: android.content.ContentResolver, uri: Uri) {
    runCatching {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d".format(minutes, seconds)
}

private fun formatSignedMs(ms: Long): String =
    if (ms >= 0L) "+$ms ms" else "$ms ms"

private fun formatBytes(bytes: Long): String {
    if (bytes < 0L) return "大小未知"
    val mib = bytes / 1024.0 / 1024.0
    return if (mib >= 1.0) "%.1f MB".format(mib) else "${bytes / 1024L} KB"
}
