package com.replayer.ui.screens

import android.app.Activity
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.replayer.model.AudioTrackInfo
import com.replayer.model.MediaItem
import com.replayer.model.SubtitleTrackInfo
import com.replayer.player.RePlayerEngine
import com.replayer.ui.components.TvFocusCard
import com.replayer.ui.theme.AccentCyan
import com.replayer.ui.theme.SurfaceDark
import com.replayer.ui.theme.TextPrimary
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    mediaItem: MediaItem,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val engine = remember { RePlayerEngine(context) }
    val playerState by engine.playerState.collectAsState()

    var showControls by remember { mutableStateOf(true) }
    var showAudioDialog by remember { mutableStateOf(false) }
    var showSubtitleDialog by remember { mutableStateOf(false) }

    // KEEP SCREEN ON during video playback!
    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        engine.play(mediaItem)

        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            engine.release()
        }
    }

    // Auto-hide TV controls after 4 seconds of inactivity
    LaunchedEffect(showControls) {
        if (showControls) {
            delay(4000)
            showControls = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusable()
            .onKeyEvent { keyEvent ->
                // Remote D-Pad key handling
                if (keyEvent.type == KeyEventType.KeyDown) {
                    when (keyEvent.key) {
                        Key.DirectionCenter, Key.Enter -> {
                            if (!showControls) {
                                showControls = true
                                true
                            } else {
                                engine.togglePlayPause()
                                true
                            }
                        }
                        Key.DirectionLeft -> {
                            engine.seekRelative(-10000)
                            showControls = true
                            true
                        }
                        Key.DirectionRight -> {
                            engine.seekRelative(10000)
                            showControls = true
                            true
                        }
                        Key.DirectionUp, Key.DirectionDown -> {
                            showControls = true
                            true
                        }
                        Key.Back -> {
                            if (showAudioDialog || showSubtitleDialog) {
                                showAudioDialog = false
                                showSubtitleDialog = false
                                true
                            } else {
                                onBack()
                                true
                            }
                        }
                        else -> false
                    }
                } else false
            }
    ) {
        // Video Surface
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = engine.exoPlayer
                    useController = false
                    keepScreenOn = true
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // TV On-Screen Display (OSD) Overlay
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x99000000))
                    .padding(40.dp)
            ) {
                // Top Bar: Title & Source
                Column(modifier = Modifier.align(Alignment.TopStart)) {
                    Text(
                        text = playerState.currentMediaTitle.ifEmpty { mediaItem.title },
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "Источник: ${mediaItem.sourceType.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AccentCyan
                    )
                }

                // Bottom Controls: Progress & Audio/Subtitle selection buttons
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                ) {
                    // Timeline
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatTime(playerState.currentPositionMs),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = formatTime(playerState.durationMs),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Buttons Row for TV Remote
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TvPlayerIconButton(
                            icon = Icons.Default.Replay10,
                            contentDescription = "Rewind 10s",
                            onClick = { engine.seekRelative(-10000) }
                        )

                        Spacer(modifier = Modifier.width(20.dp))

                        TvPlayerIconButton(
                            icon = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            onClick = { engine.togglePlayPause() }
                        )

                        Spacer(modifier = Modifier.width(20.dp))

                        TvPlayerIconButton(
                            icon = Icons.Default.Forward10,
                            contentDescription = "Forward 10s",
                            onClick = { engine.seekRelative(10000) }
                        )

                        Spacer(modifier = Modifier.width(36.dp))

                        // Audio Track Selector Button
                        TvPlayerIconButton(
                            icon = Icons.Default.Audiotrack,
                            contentDescription = "Аудиодорожки",
                            onClick = { showAudioDialog = true }
                        )

                        Spacer(modifier = Modifier.width(16.dp))

                        // Subtitle Track Selector Button
                        TvPlayerIconButton(
                            icon = Icons.Default.ClosedCaption,
                            contentDescription = "Субтитры",
                            onClick = { showSubtitleDialog = true }
                        )
                    }
                }
            }
        }

        // Audio Track Selection Dialog
        if (showAudioDialog) {
            AudioTrackDialog(
                tracks = playerState.audioTracks,
                selectedId = playerState.selectedAudioTrackId,
                onSelect = { track ->
                    engine.selectAudioTrack(track)
                    showAudioDialog = false
                },
                onDismiss = { showAudioDialog = false }
            )
        }

        // Subtitle Track Selection Dialog
        if (showSubtitleDialog) {
            SubtitleTrackDialog(
                tracks = playerState.subtitleTracks,
                selectedId = playerState.selectedSubtitleTrackId,
                onSelect = { track ->
                    engine.selectSubtitleTrack(track)
                    showSubtitleDialog = false
                },
                onDismiss = { showSubtitleDialog = false }
            )
        }
    }
}

@Composable
fun TvPlayerIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TvFocusCard(
        onClick = onClick,
        modifier = modifier
            .width(52.dp)
            .height(52.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = TextPrimary
            )
        }
    }
}

@Composable
fun AudioTrackDialog(
    tracks: List<AudioTrackInfo>,
    selectedId: String?,
    onSelect: (AudioTrackInfo) -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(440.dp)
                .background(SurfaceDark, RoundedCornerShape(16.dp))
                .padding(24.dp)
        ) {
            Text(
                text = "Выбор аудиодорожки",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            tracks.forEach { track ->
                TvFocusCard(
                    onClick = { onSelect(track) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .padding(vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = track.label,
                            color = if (track.id == selectedId) AccentCyan else TextPrimary,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SubtitleTrackDialog(
    tracks: List<SubtitleTrackInfo>,
    selectedId: String?,
    onSelect: (SubtitleTrackInfo?) -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(440.dp)
                .background(SurfaceDark, RoundedCornerShape(16.dp))
                .padding(24.dp)
        ) {
            Text(
                text = "Выбор субтитров",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Option to disable subtitles
            TvFocusCard(
                onClick = { onSelect(null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .padding(vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Отключить",
                        color = if (selectedId == null) AccentCyan else TextPrimary,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            tracks.forEach { track ->
                TvFocusCard(
                    onClick = { onSelect(track) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .padding(vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = track.label,
                            color = if (track.id == selectedId) AccentCyan else TextPrimary,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        }
    }
}

private fun formatTime(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
