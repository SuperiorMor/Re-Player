package com.replayer.ui.screens

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.replayer.model.AudioTrackInfo
import com.replayer.model.MediaItem
import com.replayer.model.SubtitleTrackInfo
import com.replayer.player.RePlayerEngine
import com.replayer.ui.components.TvFocusCard
import com.replayer.ui.theme.AccentCyan
import com.replayer.ui.theme.DarkCard
import com.replayer.ui.theme.FocusBorder
import com.replayer.ui.theme.SurfaceDark
import com.replayer.ui.theme.TextPrimary
import com.replayer.ui.theme.TextSecondary
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
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val playButtonFocusRequester = remember { FocusRequester() }

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

    // Auto-hide TV controls after 5 seconds of inactivity
    LaunchedEffect(showControls, lastInteractionTime) {
        if (showControls) {
            delay(5000)
            showControls = false
        }
    }

    // When controls become visible, automatically focus the Play/Pause button
    LaunchedEffect(showControls) {
        if (showControls) {
            delay(100)
            try {
                playButtonFocusRequester.requestFocus()
            } catch (e: Exception) {
                // Ignore focus request failure
            }
        }
    }

    BackHandler(enabled = true) {
        if (showAudioDialog || showSubtitleDialog) {
            showAudioDialog = false
            showSubtitleDialog = false
            lastInteractionTime = System.currentTimeMillis()
        } else if (showControls) {
            showControls = false
        } else {
            onBack()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(
                if (!showControls) {
                    Modifier
                        .focusable()
                        .onKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown) {
                                when (keyEvent.key) {
                                    Key.DirectionCenter, Key.Enter,
                                    Key.DirectionUp, Key.DirectionDown,
                                    Key.DirectionLeft, Key.DirectionRight -> {
                                        showControls = true
                                        lastInteractionTime = System.currentTimeMillis()
                                        true
                                    }
                                    else -> false
                                }
                            } else false
                        }
                } else Modifier
            )
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
            visible = showControls && !showAudioDialog && !showSubtitleDialog,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xB3000000))
                    .padding(horizontal = 48.dp, vertical = 36.dp)
            ) {
                // Top Bar: Title & Source & Active Audio Track
                Column(modifier = Modifier.align(Alignment.TopStart)) {
                    Text(
                        text = playerState.currentMediaTitle.ifEmpty { mediaItem.title },
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val selectedAudio = playerState.audioTracks.find { it.id == playerState.selectedAudioTrackId }
                    val audioInfo = selectedAudio?.let { " • Звук: ${it.label}" } ?: ""
                    Text(
                        text = "Источник: ${mediaItem.sourceType.name}$audioInfo",
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
                    // Time Labels & Interactive Seek Bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatTime(playerState.currentPositionMs),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )

                        Spacer(modifier = Modifier.width(16.dp))

                        // Interactive Focusable Timeline Seek Bar
                        TvTimelineBar(
                            currentPositionMs = playerState.currentPositionMs,
                            durationMs = playerState.durationMs,
                            onSeekRelative = { delta ->
                                engine.seekRelative(delta)
                                lastInteractionTime = System.currentTimeMillis()
                            },
                            modifier = Modifier.weight(1f)
                        )

                        Spacer(modifier = Modifier.width(16.dp))

                        Text(
                            text = formatTime(playerState.durationMs),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Buttons Row for TV Remote
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TvPlayerIconButton(
                            icon = Icons.Default.Replay10,
                            contentDescription = "Назад на 10 сек",
                            onClick = {
                                engine.seekRelative(-10000)
                                lastInteractionTime = System.currentTimeMillis()
                            }
                        )

                        Spacer(modifier = Modifier.width(20.dp))

                        TvPlayerIconButton(
                            icon = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playerState.isPlaying) "Пауза" else "Воспроизведение",
                            focusRequester = playButtonFocusRequester,
                            onClick = {
                                engine.togglePlayPause()
                                lastInteractionTime = System.currentTimeMillis()
                            }
                        )

                        Spacer(modifier = Modifier.width(20.dp))

                        TvPlayerIconButton(
                            icon = Icons.Default.Forward10,
                            contentDescription = "Вперед на 10 сек",
                            onClick = {
                                engine.seekRelative(10000)
                                lastInteractionTime = System.currentTimeMillis()
                            }
                        )

                        Spacer(modifier = Modifier.width(36.dp))

                        // Audio Track Selector Button
                        TvPlayerIconButton(
                            icon = Icons.Default.Audiotrack,
                            contentDescription = "Аудиодорожки",
                            onClick = {
                                showAudioDialog = true
                                lastInteractionTime = System.currentTimeMillis()
                            }
                        )

                        Spacer(modifier = Modifier.width(16.dp))

                        // Subtitle Track Selector Button
                        TvPlayerIconButton(
                            icon = Icons.Default.ClosedCaption,
                            contentDescription = "Субтитры",
                            onClick = {
                                showSubtitleDialog = true
                                lastInteractionTime = System.currentTimeMillis()
                            }
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
                    lastInteractionTime = System.currentTimeMillis()
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
                    lastInteractionTime = System.currentTimeMillis()
                },
                onDismiss = { showSubtitleDialog = false }
            )
        }
    }
}

@Composable
fun TvTimelineBar(
    currentPositionMs: Long,
    durationMs: Long,
    onSeekRelative: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val progress = if (durationMs > 0) (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    BoxWithConstraints(
        modifier = modifier
            .height(36.dp)
            .focusable(interactionSource = interactionSource)
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    when (keyEvent.key) {
                        Key.DirectionLeft -> {
                            onSeekRelative(-10000)
                            true
                        }
                        Key.DirectionRight -> {
                            onSeekRelative(10000)
                            true
                        }
                        else -> false
                    }
                } else false
            },
        contentAlignment = Alignment.Center
    ) {
        val totalWidth = maxWidth

        // Background track bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (isFocused) 10.dp else 6.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (isFocused) Color(0x66FFFFFF) else Color(0x33FFFFFF))
        ) {
            // Played progress fill
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = progress)
                    .fillMaxHeight()
                    .background(AccentCyan)
            )
        }

        // Focused Scrubber Thumb
        if (isFocused) {
            val thumbOffset = (totalWidth - 16.dp) * progress
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .padding(start = thumbOffset.coerceAtLeast(0.dp))
                        .size(16.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White)
                        .border(2.dp, AccentCyan, RoundedCornerShape(8.dp))
                )
            }
        }
    }
}

@Composable
fun TvPlayerIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (isFocused) 1.15f else 1.0f, label = "btn_scale")

    Box(
        modifier = modifier
            .size(56.dp)
            .padding(4.dp)
            .scale(scale)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isFocused) AccentCyan.copy(alpha = 0.25f) else Color(0x33FFFFFF))
            .then(
                if (isFocused) {
                    Modifier.border(2.5.dp, FocusBorder, RoundedCornerShape(12.dp))
                } else {
                    Modifier.border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(12.dp))
                }
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .focusable(interactionSource = interactionSource),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (isFocused) AccentCyan else TextPrimary,
            modifier = Modifier.size(28.dp)
        )
    }
}

@Composable
fun AudioTrackDialog(
    tracks: List<AudioTrackInfo>,
    selectedId: String?,
    onSelect: (AudioTrackInfo) -> Unit,
    onDismiss: () -> Unit
) {
    val firstFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(100)
        try {
            firstFocusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(480.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(DarkCard)
                .border(1.dp, AccentCyan.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                .padding(24.dp)
        ) {
            Column {
                Text(
                    text = "Выбор аудиодорожки",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                if (tracks.isEmpty()) {
                    Text(
                        text = "Дорожки не найдены",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }

                tracks.forEachIndexed { index, track ->
                    val isFirst = index == 0
                    val isSelected = track.id == selectedId
                    TvFocusCard(
                        onClick = { onSelect(track) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .padding(vertical = 4.dp)
                            .then(if (isFirst) Modifier.focusRequester(firstFocusRequester) else Modifier)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = track.label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isSelected) AccentCyan else TextPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            if (isSelected) {
                                Text(text = "✓", color = AccentCyan, fontWeight = FontWeight.Bold)
                            }
                        }
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
    val firstFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(100)
        try {
            firstFocusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(480.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(DarkCard)
                .border(1.dp, AccentCyan.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                .padding(24.dp)
        ) {
            Column {
                Text(
                    text = "Выбор субтитров",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                TvFocusCard(
                    onClick = { onSelect(null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(vertical = 4.dp)
                        .focusRequester(firstFocusRequester)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Отключить субтитры",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (selectedId == null) AccentCyan else TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        if (selectedId == null) {
                            Text(text = "✓", color = AccentCyan, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                tracks.forEach { track ->
                    val isSelected = track.id == selectedId
                    TvFocusCard(
                        onClick = { onSelect(track) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
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
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isSelected) AccentCyan else TextPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            if (isSelected) {
                                Text(text = "✓", color = AccentCyan, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
