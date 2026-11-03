package com.replayer.ui.screens

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.replayer.data.IptvManager
import com.replayer.model.AudioTrackInfo
import com.replayer.model.EpgProgram
import com.replayer.model.IptvChannel
import com.replayer.model.MediaItem
import com.replayer.model.MediaSourceType
import com.replayer.model.SubtitleTrackInfo
import com.replayer.player.RePlayerEngine
import com.replayer.ui.components.TvFocusCard
import com.replayer.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun PlayerScreen(
    mediaItem: MediaItem,
    onBack: () -> Unit,
    playlist: List<MediaItem> = emptyList(),
    onPlayMediaItem: ((MediaItem) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val engine = remember { RePlayerEngine(context) }
    val playerState by engine.playerState.collectAsState()

    var showControls by remember { mutableStateOf(true) }
    var showAudioDialog by remember { mutableStateOf(false) }
    var showSubtitleDialog by remember { mutableStateOf(false) }
    var showEpgDialog by remember { mutableStateOf(false) }
    var osdFeedback by remember { mutableStateOf<String?>(null) }
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val isIptv = mediaItem.sourceType == MediaSourceType.IPTV
    val iptvManager = remember { IptvManager.getInstance(context) }
    var currentProgram by remember(mediaItem) {
        mutableStateOf(if (isIptv) iptvManager.getCurrentProgram(IptvChannel(id = mediaItem.id, name = mediaItem.title, streamUrl = mediaItem.uri)) else null)
    }
    val channelSchedule = remember(mediaItem) {
        if (isIptv) iptvManager.getProgramsForChannel(IptvChannel(id = mediaItem.id, name = mediaItem.title, streamUrl = mediaItem.uri)) else emptyList()
    }

    // Periodic EPG check and watch duration tracking
    LaunchedEffect(isIptv) {
        if (isIptv) {
            while (isActive) {
                delay(5000)
                currentProgram = iptvManager.getCurrentProgram(IptvChannel(id = mediaItem.id, name = mediaItem.title, streamUrl = mediaItem.uri))
                if (playerState.isPlaying) {
                    iptvManager.recordWatchTime(mediaItem.id, 5)
                }
            }
        }
    }

    val playButtonFocusRequester = remember { FocusRequester() }
    val rootFocusRequester = remember { FocusRequester() }

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

    // Auto-hide TV controls after 5 seconds of inactivity (PAUSED if any dialog is open!)
    LaunchedEffect(showControls, lastInteractionTime, showAudioDialog, showSubtitleDialog, showEpgDialog) {
        if (showControls && !showAudioDialog && !showSubtitleDialog && !showEpgDialog) {
            delay(5000)
            showControls = false
        }
    }

    // Focus management: when controls are shown, focus play button; when hidden, focus root box
    LaunchedEffect(showControls, showAudioDialog, showSubtitleDialog, showEpgDialog) {
        delay(100)
        try {
            if (!showAudioDialog && !showSubtitleDialog && !showEpgDialog) {
                if (showControls) playButtonFocusRequester.requestFocus() else rootFocusRequester.requestFocus()
            }
        } catch (_: Exception) {}
    }

    LaunchedEffect(osdFeedback) {
        if (osdFeedback != null) {
            delay(3000)
            osdFeedback = null
        }
    }

    BackHandler(enabled = true) {
        if (showEpgDialog) {
            showEpgDialog = false
            lastInteractionTime = System.currentTimeMillis()
        } else if (showAudioDialog || showSubtitleDialog) {
            showAudioDialog = false
            showSubtitleDialog = false
            lastInteractionTime = System.currentTimeMillis()
        } else if (showControls) {
            showControls = false
            try {
                rootFocusRequester.requestFocus()
            } catch (_: Exception) {}
        } else {
            onBack()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocusRequester)
            .focusable(enabled = !showControls && !showAudioDialog && !showSubtitleDialog && !showEpgDialog)
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown && (keyEvent.key == Key.Back || keyEvent.key == Key.Escape)) {
                    if (showEpgDialog) {
                        showEpgDialog = false
                        lastInteractionTime = System.currentTimeMillis()
                        return@onPreviewKeyEvent true
                    } else if (showAudioDialog || showSubtitleDialog) {
                        showAudioDialog = false
                        showSubtitleDialog = false
                        lastInteractionTime = System.currentTimeMillis()
                        return@onPreviewKeyEvent true
                    } else if (showControls) {
                        showControls = false
                        try {
                            rootFocusRequester.requestFocus()
                        } catch (_: Exception) {}
                        return@onPreviewKeyEvent true
                    } else {
                        onBack()
                        return@onPreviewKeyEvent true
                    }
                }
                false
            }
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    if (!showControls) {
                        when (keyEvent.key) {
                            Key.DirectionRight -> {
                                // 10s forward without showing controls
                                engine.seekRelative(10000)
                                true
                            }
                            Key.DirectionLeft -> {
                                // 10s back without showing controls
                                engine.seekRelative(-10000)
                                true
                            }
                            Key.DirectionCenter, Key.Enter,
                            Key.DirectionUp, Key.DirectionDown,
                            Key.MediaPlay, Key.MediaPause, Key.MediaPlayPause,
                            Key.Spacebar -> {
                                showControls = true
                                lastInteractionTime = System.currentTimeMillis()
                                true
                            }
                            else -> false
                        }
                    } else {
                        lastInteractionTime = System.currentTimeMillis()
                        false
                    }
                } else false
            }
    ) {
        // Video Surface via LibVLC
        AndroidView(
            factory = { ctx ->
                VLCVideoLayout(ctx).apply {
                    keepScreenOn = true
                    isFocusable = false
                    isFocusableInTouchMode = false
                    engine.attachVideoLayout(this)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    showControls = !showControls
                    lastInteractionTime = System.currentTimeMillis()
                }
        )

        // OSD Toast / Feedback overlay
        AnimatedVisibility(
            visible = osdFeedback != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 80.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xEE1A1A24))
                    .border(1.dp, AccentCyan.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text(
                    text = osdFeedback ?: "",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = AccentCyan
                )
            }
        }

        // TV On-Screen Display (OSD) Overlay
        AnimatedVisibility(
            visible = showControls && !showAudioDialog && !showSubtitleDialog && !showEpgDialog,
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
                    // Time Labels & Interactive Seek Bar / IPTV EPG Timebar (Requirement 10)
                    if (isIptv) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            if (currentProgram != null) {
                                Text(
                                    text = currentProgram!!.title,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = TextPrimary,
                                    maxLines = 1,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = currentProgram?.formattedStartTime() ?: "В эфире",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = AccentCyan
                                )

                                Spacer(modifier = Modifier.width(16.dp))

                                LinearProgressIndicator(
                                    progress = { currentProgram?.progress() ?: 0.5f },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    color = AccentCyan,
                                    trackColor = DarkSurface
                                )

                                Spacer(modifier = Modifier.width(16.dp))

                                Text(
                                    text = currentProgram?.formattedEndTime() ?: "Прямой эфир",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary
                                )
                            }
                        }
                    } else {
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
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Buttons Row for TV Remote
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusProperties {
                                if (isIptv) {
                                    up = FocusRequester.Cancel
                                    down = FocusRequester.Cancel
                                }
                            },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!isIptv) {
                            TvPlayerIconButton(
                                icon = Icons.Default.Replay10,
                                contentDescription = "Назад на 10 сек",
                                onClick = {
                                    engine.seekRelative(-10000)
                                    lastInteractionTime = System.currentTimeMillis()
                                }
                            )

                            Spacer(modifier = Modifier.width(20.dp))
                        } else {
                            // IPTV: Previous Program / Rewind to program start
                            TvPlayerIconButton(
                                icon = Icons.Default.SkipPrevious,
                                contentDescription = "Предыдущая передача",
                                onClick = {
                                    val now = System.currentTimeMillis()
                                    val currentIdx = channelSchedule.indexOfFirst { it.isCurrent(now) }
                                    val prevProg = if (currentIdx > 0) channelSchedule[currentIdx - 1] else null
                                    if (prevProg != null) {
                                        val seekDelta = -(now - prevProg.startTime).coerceAtLeast(10000L)
                                        engine.seekRelative(seekDelta)
                                        osdFeedback = "Предыдущая: ${prevProg.title} (${prevProg.formattedStartTime()})"
                                    } else if (currentProgram != null && (now - currentProgram!!.startTime) > 15000L) {
                                        val seekDelta = -(now - currentProgram!!.startTime)
                                        engine.seekRelative(seekDelta)
                                        osdFeedback = "К началу: ${currentProgram!!.title}"
                                    } else {
                                        osdFeedback = "Предыдущая передача не найдена"
                                    }
                                    lastInteractionTime = System.currentTimeMillis()
                                }
                            )

                            Spacer(modifier = Modifier.width(20.dp))
                        }

                        TvPlayerIconButton(
                            icon = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playerState.isPlaying) "Пауза" else "Воспроизведение",
                            focusRequester = playButtonFocusRequester,
                            onClick = {
                                engine.togglePlayPause()
                                lastInteractionTime = System.currentTimeMillis()
                            }
                        )

                        if (!isIptv) {
                            Spacer(modifier = Modifier.width(20.dp))

                            TvPlayerIconButton(
                                icon = Icons.Default.Forward10,
                                contentDescription = "Вперед на 10 сек",
                                onClick = {
                                    engine.seekRelative(10000)
                                    lastInteractionTime = System.currentTimeMillis()
                                }
                            )

                            Spacer(modifier = Modifier.width(20.dp))

                            // Next Episode / File in folder
                            val currentFileIdx = playlist.indexOfFirst { it.id == mediaItem.id }
                            val nextFile = if (currentFileIdx in 0 until playlist.size - 1) playlist[currentFileIdx + 1] else null
                            TvPlayerIconButton(
                                icon = Icons.Default.SkipNext,
                                contentDescription = "Следующая серия",
                                onClick = {
                                    if (nextFile != null) {
                                        osdFeedback = "Следующая серия: ${nextFile.title}"
                                        onPlayMediaItem?.invoke(nextFile)
                                    } else {
                                        osdFeedback = "Следующая серия отсутствует"
                                    }
                                    lastInteractionTime = System.currentTimeMillis()
                                }
                            )
                        }

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

                        if (isIptv) {
                            Spacer(modifier = Modifier.width(16.dp))

                            // EPG Schedule Button (Accordion & Archives)
                            TvPlayerIconButton(
                                icon = Icons.Default.MenuBook,
                                contentDescription = "Телепрограмма и архив",
                                onClick = {
                                    showEpgDialog = true
                                    lastInteractionTime = System.currentTimeMillis()
                                }
                            )
                        }
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

        // IPTV EPG Schedule Dialog (Accordion & Archives)
        if (showEpgDialog) {
            EpgAccordionDialog(
                channelTitle = mediaItem.title,
                programs = channelSchedule,
                onSelectProgram = { prog ->
                    val now = System.currentTimeMillis()
                    if (prog.isCurrent(now)) {
                        val seekDelta = -(now - prog.startTime)
                        engine.seekRelative(seekDelta)
                        osdFeedback = "К началу эфира: ${prog.title}"
                    } else if (prog.isPast(now)) {
                        val seekDelta = -(now - prog.startTime)
                        engine.seekRelative(seekDelta)
                        osdFeedback = "Архив: ${prog.title} (${prog.formattedStartTime()})"
                    } else {
                        osdFeedback = "Передача еще не началась"
                    }
                    showEpgDialog = false
                    lastInteractionTime = System.currentTimeMillis()
                },
                onDismiss = {
                    showEpgDialog = false
                    lastInteractionTime = System.currentTimeMillis()
                }
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
    val closeFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(120)
        try {
            if (tracks.isNotEmpty()) {
                firstFocusRequester.requestFocus()
            } else {
                closeFocusRequester.requestFocus()
            }
        } catch (_: Exception) {}
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .width(480.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(DarkCard)
                .border(1.dp, AccentCyan.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                .padding(24.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Выбор аудиодорожки",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                    TvFocusCard(
                        onClick = onDismiss,
                        modifier = Modifier
                            .width(100.dp)
                            .height(36.dp)
                            .then(if (tracks.isEmpty()) Modifier.focusRequester(closeFocusRequester) else Modifier)
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Закрыть", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (tracks.isEmpty()) {
                    Text(
                        text = "Дорожки не найдены",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(tracks) { index, track ->
                        val isFirst = index == 0
                        val isSelected = track.id == selectedId
                        TvFocusCard(
                            onClick = { onSelect(track) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
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
        delay(120)
        try {
            firstFocusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .width(480.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(DarkCard)
                .border(1.dp, AccentCyan.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                .padding(24.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Выбор субтитров",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                    TvFocusCard(
                        onClick = onDismiss,
                        modifier = Modifier
                            .width(100.dp)
                            .height(36.dp)
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Закрыть", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        TvFocusCard(
                            onClick = { onSelect(null) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
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
                    }

                    items(tracks) { track ->
                        val isSelected = track.id == selectedId
                        TvFocusCard(
                            onClick = { onSelect(track) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
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
}

@Composable
fun EpgAccordionDialog(
    channelTitle: String,
    programs: List<EpgProgram>,
    onSelectProgram: (EpgProgram) -> Unit,
    onDismiss: () -> Unit
) {
    val now = remember { System.currentTimeMillis() }
    val currentProgIndex = remember(programs) {
        val idx = programs.indexOfFirst { it.isCurrent(now) }
        if (idx >= 0) idx else programs.indexOfFirst { it.startTime > now }.let { if (it > 0) it - 1 else 0 }
    }
    var focusedIndex by remember { mutableIntStateOf(currentProgIndex.coerceAtLeast(0)) }
    val initialFocusRequester = remember { FocusRequester() }
    val closeFocusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        delay(120)
        try {
            if (programs.isNotEmpty()) {
                val scrollTarget = (currentProgIndex - 1).coerceAtLeast(0)
                listState.scrollToItem(scrollTarget)
                initialFocusRequester.requestFocus()
            } else {
                closeFocusRequester.requestFocus()
            }
        } catch (_: Exception) {}
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .width(620.dp)
                .height(560.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(DarkCard)
                .border(1.dp, AccentCyan.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                .padding(24.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = channelTitle,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Телепрограмма и архив (↑ назад / ↓ вперед)",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    TvFocusCard(
                        onClick = onDismiss,
                        modifier = Modifier
                            .width(100.dp)
                            .height(36.dp)
                            .then(if (programs.isEmpty()) Modifier.focusRequester(closeFocusRequester) else Modifier)
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Закрыть", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (programs.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Для этого канала нет данных программы передач",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(programs) { index, prog ->
                            val isItemCurrent = prog.isCurrent(now)
                            val isItemPast = prog.isPast(now)
                            val isExpanded = (index == focusedIndex)
                            val isInitialTarget = (index == currentProgIndex)

                            TvFocusCard(
                                onClick = { onSelectProgram(prog) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateContentSize()
                                    .onFocusChanged { focusState ->
                                        if (focusState.isFocused) {
                                            focusedIndex = index
                                        }
                                    }
                                    .then(if (isInitialTarget) Modifier.focusRequester(initialFocusRequester) else Modifier)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = if (isExpanded) 14.dp else 10.dp)
                                ) {
                                    if (isExpanded) {
                                        // Expanded Accordion Program View
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "${prog.formattedTimeRange()} (${prog.durationMinutes()} мин.)",
                                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                                color = AccentCyan
                                            )

                                            val badgeText = when {
                                                isItemCurrent -> "В ЭФИРЕ"
                                                isItemPast -> "АРХИВ"
                                                else -> "СКОРО"
                                            }
                                            val badgeColor = when {
                                                isItemCurrent -> AccentCyan
                                                isItemPast -> Color(0xFF81C784)
                                                else -> TextSecondary
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(badgeColor.copy(alpha = 0.2f))
                                                    .border(1.dp, badgeColor, RoundedCornerShape(6.dp))
                                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = badgeText,
                                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                    color = badgeColor
                                                )
                                            }
                                        }

                                        if (isItemCurrent) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            LinearProgressIndicator(
                                                progress = { prog.progress(now) },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(6.dp)
                                                    .clip(RoundedCornerShape(3.dp)),
                                                color = AccentCyan,
                                                trackColor = DarkSurface
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = prog.title,
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                            color = TextPrimary
                                        )

                                        if (!prog.description.isNullOrBlank()) {
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = prog.description,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = TextSecondary
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = if (isItemPast) "▶ Нажмите OK для просмотра записи из архива" else if (isItemCurrent) "▶ В эфире сейчас (OK - к началу)" else "⏰ Запланировано в эфире",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isItemPast || isItemCurrent) AccentCyan else TextSecondary
                                        )
                                    } else {
                                        // Collapsed Compact View
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = prog.formattedStartTime(),
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                                color = if (isItemCurrent) AccentCyan else TextSecondary,
                                                modifier = Modifier.width(55.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = prog.title,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = if (isItemCurrent) AccentCyan else TextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            if (isItemCurrent) {
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "В ЭФИРЕ",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                    color = AccentCyan
                                                )
                                            } else if (isItemPast) {
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "Архив",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color(0xFF81C784)
                                                )
                                            }
                                        }
                                    }
                                }
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
