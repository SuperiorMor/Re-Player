package com.replayer.ui.screens

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.ui.graphics.Brush
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
import org.videolan.libvlc.util.VLCVideoLayout
import com.replayer.data.IptvManager
import com.replayer.data.RecentMediaManager
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
    val recentManager = remember { RecentMediaManager.getInstance(context) }
    val allChannels by iptvManager.channels.collectAsState()
    val fullChannel = remember(mediaItem.id, allChannels) {
        iptvManager.findChannelById(mediaItem.id)
            ?: IptvChannel(id = mediaItem.id, name = mediaItem.title, streamUrl = mediaItem.uri)
    }

    var playingArchiveProgram by remember(mediaItem.uri) { mutableStateOf<EpgProgram?>(null) }
    var currentProgram by remember(fullChannel) {
        mutableStateOf(if (isIptv) iptvManager.getCurrentProgram(fullChannel) else null)
    }
    val channelSchedule = remember(fullChannel) {
        if (isIptv) iptvManager.getProgramsForChannel(fullChannel) else emptyList()
    }

    val latestMediaItem by rememberUpdatedState(mediaItem)
    val latestPlayerState by rememberUpdatedState(playerState)
    var pendingResumePositionMs by remember(mediaItem.id, mediaItem.uri) {
        mutableLongStateOf(
            if (!isIptv) recentManager.getSavedResumePosition(mediaItem.id, mediaItem.uri) else 0L
        )
    }

    fun playArchiveProgram(prog: EpgProgram) {
        val archiveUrl = iptvManager.buildArchiveUrl(fullChannel, prog)
        if (archiveUrl != null) {
            playingArchiveProgram = prog
            engine.play(mediaItem.copy(title = "${fullChannel.name} • ${prog.title}", uri = archiveUrl))
        } else {
            osdFeedback = "Архив недоступен для этого канала"
        }
    }

    // Resume saved playback position when duration becomes ready on DLNA files
    LaunchedEffect(playerState.durationMs, pendingResumePositionMs, isIptv) {
        if (!isIptv && pendingResumePositionMs > 0L && playerState.durationMs > pendingResumePositionMs + 5000L) {
            val pos = pendingResumePositionMs
            pendingResumePositionMs = 0L
            engine.seekTo(pos)
            osdFeedback = "Продолжено с ${formatTime(pos)}"
        }
    }

    // Periodic EPG check / watch duration / DLNA progress tracking
    LaunchedEffect(isIptv, mediaItem.id, mediaItem.uri) {
        while (isActive) {
            delay(5000)
            if (isIptv) {
                currentProgram = iptvManager.getCurrentProgram(fullChannel)
                if (latestPlayerState.isPlaying) {
                    iptvManager.recordWatchTime(mediaItem.id, 5)
                }
            } else if (latestPlayerState.durationMs > 0L && latestPlayerState.currentPositionMs > 0L) {
                recentManager.updateProgress(
                    id = mediaItem.id,
                    title = mediaItem.title,
                    uri = mediaItem.uri,
                    positionMs = latestPlayerState.currentPositionMs,
                    durationMs = latestPlayerState.durationMs
                )
            }
        }
    }

    val playButtonFocusRequester = remember { FocusRequester() }
    val timelineFocusRequester = remember { FocusRequester() }
    val rootFocusRequester = remember { FocusRequester() }

    // Trigger playback whenever mediaItem switches (initial file, next episode, etc.)
    LaunchedEffect(mediaItem.id, mediaItem.uri) {
        playingArchiveProgram = null
        if (!isIptv) {
            recentManager.addRecent(
                id = mediaItem.id,
                title = mediaItem.title,
                uri = mediaItem.uri,
                parentId = mediaItem.parentId,
                thumbnailUrl = mediaItem.thumbnailUrl
            )
        }
        engine.play(mediaItem)
    }

    // KEEP SCREEN ON during video playback & save final DLNA progress on exit
    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (!isIptv && latestPlayerState.durationMs > 0L && latestPlayerState.currentPositionMs > 0L) {
                recentManager.updateProgress(
                    id = latestMediaItem.id,
                    title = latestMediaItem.title,
                    uri = latestMediaItem.uri,
                    positionMs = latestPlayerState.currentPositionMs,
                    durationMs = latestPlayerState.durationMs
                )
            }
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
                if (keyEvent.type == KeyEventType.KeyDown && showControls) {
                    lastInteractionTime = System.currentTimeMillis()
                }
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
                    .clip(RoundedCornerShape(14.dp))
                    .background(SurfaceDark.copy(alpha = 0.94f))
                    .border(1.5.dp, FocusBorder.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text(
                    text = osdFeedback ?: "",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = AccentCyanBright
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
                    if (!isIptv) {
                        Text(
                            text = "Источник: ${mediaItem.sourceType.name}$audioInfo",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AccentCyan
                        )
                    }
                }

                // Bottom Controls: Progress & Audio/Subtitle selection buttons
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                ) {
                    // Time Labels & Interactive Seek Bar / IPTV EPG Timebar
                    if (isIptv) {
                        val displayProg = playingArchiveProgram ?: currentProgram
                        val progFraction = if (playingArchiveProgram != null) {
                            val arch = playingArchiveProgram!!
                            val archDurMs = (arch.endTime - arch.startTime).coerceAtLeast(1L)
                            if (playerState.currentPositionMs in 1L..(archDurMs + 60_000L)) {
                                (playerState.currentPositionMs.toFloat() / archDurMs.toFloat()).coerceIn(0f, 1f)
                            } else if (playerState.durationMs > 0L && playerState.currentPositionMs > 0L) {
                                (playerState.currentPositionMs.toFloat() / playerState.durationMs.toFloat()).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                        } else {
                            (currentProgram?.progress() ?: 0.5f).coerceIn(0f, 1f)
                        }
                        Column(modifier = Modifier.fillMaxWidth()) {
                            if (displayProg != null) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(
                                                if (playingArchiveProgram != null) AccentOrange.copy(alpha = 0.2f)
                                                else AccentCyan.copy(alpha = 0.18f)
                                            )
                                            .padding(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = if (playingArchiveProgram != null) "АРХИВ" else "В ЭФИРЕ",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = if (playingArchiveProgram != null) AccentOrange else AccentCyanBright
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = displayProg.title,
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = displayProg?.formattedStartTime() ?: "В эфире",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = AccentCyanBright
                                )

                                Spacer(modifier = Modifier.width(16.dp))

                                TvIptvTimelineBar(
                                    progress = progFraction,
                                    isArchiveMode = playingArchiveProgram != null,
                                    focusRequester = timelineFocusRequester,
                                    downFocusRequester = playButtonFocusRequester,
                                    onSeekLeft = {
                                        if (playingArchiveProgram != null) {
                                            engine.seekRelative(-10000)
                                            osdFeedback = "⏪ -10 сек"
                                        } else if (fullChannel.hasArchive && currentProgram != null) {
                                            playArchiveProgram(currentProgram!!)
                                        } else {
                                            osdFeedback = "Архив недоступен в прямом эфире"
                                        }
                                        lastInteractionTime = System.currentTimeMillis()
                                    },
                                    onSeekRight = {
                                        if (playingArchiveProgram != null) {
                                            engine.seekRelative(10000)
                                            osdFeedback = "⏩ +10 сек"
                                        } else {
                                            osdFeedback = "Сейчас прямой эфир"
                                        }
                                        lastInteractionTime = System.currentTimeMillis()
                                    },
                                    onOpenSchedule = {
                                        showEpgDialog = true
                                        lastInteractionTime = System.currentTimeMillis()
                                    },
                                    modifier = Modifier.weight(1f)
                                )

                                Spacer(modifier = Modifier.width(16.dp))

                                Text(
                                    text = displayProg?.formattedEndTime() ?: "Прямой эфир",
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
                                focusRequester = timelineFocusRequester,
                                downFocusRequester = playButtonFocusRequester,
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

                    // Buttons Row for TV Remote — locked vertically (timeline up, cancel down) & horizontally on edges
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusProperties {
                                up = timelineFocusRequester
                                down = FocusRequester.Cancel
                            },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!isIptv) {
                            TvPlayerIconButton(
                                icon = Icons.Default.Replay10,
                                contentDescription = "Назад на 10 сек",
                                modifier = Modifier.focusProperties {
                                    left = FocusRequester.Cancel
                                    up = timelineFocusRequester
                                    down = FocusRequester.Cancel
                                },
                                onClick = {
                                    engine.seekRelative(-10000)
                                    lastInteractionTime = System.currentTimeMillis()
                                }
                            )

                            Spacer(modifier = Modifier.width(20.dp))
                        } else {
                            // IPTV: Switch to previous program in archive
                            TvPlayerIconButton(
                                icon = Icons.Default.SkipPrevious,
                                contentDescription = "Предыдущая передача",
                                modifier = Modifier.focusProperties {
                                    left = FocusRequester.Cancel
                                    up = timelineFocusRequester
                                    down = FocusRequester.Cancel
                                },
                                onClick = {
                                    val now = System.currentTimeMillis()
                                    val anchor = playingArchiveProgram ?: currentProgram
                                    val anchorIdx = if (anchor != null) {
                                        channelSchedule.indexOfFirst { it.startTime == anchor.startTime }
                                    } else {
                                        channelSchedule.indexOfFirst { it.isCurrent(now) }
                                    }
                                    val prevProg = when {
                                        anchorIdx > 0 -> channelSchedule[anchorIdx - 1]
                                        else -> channelSchedule.lastOrNull { it.isPast(now) }
                                    }

                                    if (prevProg != null) {
                                        playArchiveProgram(prevProg)
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
                            modifier = Modifier.focusProperties {
                                up = timelineFocusRequester
                                down = FocusRequester.Cancel
                            },
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
                                modifier = Modifier.focusProperties {
                                    up = timelineFocusRequester
                                    down = FocusRequester.Cancel
                                },
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
                                modifier = Modifier.focusProperties {
                                    up = timelineFocusRequester
                                    down = FocusRequester.Cancel
                                },
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
                            modifier = Modifier.focusProperties {
                                up = timelineFocusRequester
                                down = FocusRequester.Cancel
                            },
                            onClick = {
                                showAudioDialog = true
                                lastInteractionTime = System.currentTimeMillis()
                            }
                        )

                        Spacer(modifier = Modifier.width(16.dp))

                        // Subtitle Track Selector Button (rightmost edge when !isIptv)
                        TvPlayerIconButton(
                            icon = Icons.Default.ClosedCaption,
                            contentDescription = "Субтитры",
                            modifier = Modifier.focusProperties {
                                up = timelineFocusRequester
                                down = FocusRequester.Cancel
                                if (!isIptv) right = FocusRequester.Cancel
                            },
                            onClick = {
                                showSubtitleDialog = true
                                lastInteractionTime = System.currentTimeMillis()
                            }
                        )

                        if (isIptv) {
                            Spacer(modifier = Modifier.width(16.dp))

                            // EPG Schedule Button (rightmost edge when isIptv)
                            TvPlayerIconButton(
                                icon = Icons.Default.MenuBook,
                                contentDescription = "Телепрограмма и архив",
                                modifier = Modifier.focusProperties {
                                    up = timelineFocusRequester
                                    down = FocusRequester.Cancel
                                    right = FocusRequester.Cancel
                                },
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
                channelTitle = fullChannel.name,
                hasArchive = fullChannel.hasArchive,
                playingArchiveProgram = playingArchiveProgram,
                programs = channelSchedule,
                onSelectProgram = { prog ->
                    val now = System.currentTimeMillis()
                    if (prog.isPast(now)) {
                        playArchiveProgram(prog)
                    } else if (prog.isCurrent(now)) {
                        if (playingArchiveProgram != null) {
                            playingArchiveProgram = null
                            engine.play(mediaItem)
                            osdFeedback = "Прямой эфир: ${fullChannel.name}"
                        } else if (fullChannel.hasArchive) {
                            playArchiveProgram(prog)
                        } else {
                            osdFeedback = "Сейчас в эфире: ${prog.title}"
                        }
                    } else {
                        osdFeedback = "Передача начнётся в ${prog.formattedStartTime()}"
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
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null
) {
    val progress = if (durationMs > 0) (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    TvScrubberTrack(
        progress = progress,
        focusRequester = focusRequester,
        downFocusRequester = downFocusRequester,
        onStepLeft = { onSeekRelative(-10000) },
        onStepRight = { onSeekRelative(10000) },
        modifier = modifier
    )
}

@Composable
fun TvIptvTimelineBar(
    progress: Float,
    isArchiveMode: Boolean,
    onSeekLeft: () -> Unit,
    onSeekRight: () -> Unit,
    onOpenSchedule: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null
) {
    TvScrubberTrack(
        progress = progress.coerceIn(0f, 1f),
        focusRequester = focusRequester,
        downFocusRequester = downFocusRequester,
        thumbBorderColor = if (isArchiveMode) AccentOrange else FocusBorder,
        onStepLeft = onSeekLeft,
        onStepRight = onSeekRight,
        onCenterClick = onOpenSchedule,
        modifier = modifier
    )
}

@Composable
private fun TvScrubberTrack(
    progress: Float,
    focusRequester: FocusRequester?,
    downFocusRequester: FocusRequester?,
    onStepLeft: () -> Unit,
    onStepRight: () -> Unit,
    modifier: Modifier = Modifier,
    thumbBorderColor: Color = FocusBorder,
    onCenterClick: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val clampedProgress = progress.coerceIn(0f, 1f)

    BoxWithConstraints(
        modifier = modifier
            .height(36.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusProperties {
                up = FocusRequester.Cancel
                left = FocusRequester.Cancel
                right = FocusRequester.Cancel
                if (downFocusRequester != null) {
                    down = downFocusRequester
                }
            }
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    when (keyEvent.key) {
                        Key.DirectionLeft -> {
                            onStepLeft()
                            true
                        }
                        Key.DirectionRight -> {
                            onStepRight()
                            true
                        }
                        Key.DirectionCenter, Key.Enter -> {
                            if (onCenterClick != null) {
                                onCenterClick()
                                true
                            } else false
                        }
                        else -> false
                    }
                } else false
            }
            .then(
                if (onCenterClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onCenterClick
                    )
                } else Modifier
            )
            .focusable(interactionSource = interactionSource),
        contentAlignment = Alignment.Center
    ) {
        val totalWidth = maxWidth

        // Background track bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (isFocused) 10.dp else 6.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (isFocused) Color(0x55FFFFFF) else Color(0x30FFFFFF))
                .then(
                    if (isFocused) Modifier.border(1.dp, thumbBorderColor.copy(alpha = 0.7f), RoundedCornerShape(5.dp))
                    else Modifier
                )
        ) {
            // Played progress fill in brand gradient
            if (clampedProgress > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = clampedProgress)
                        .fillMaxHeight()
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(AccentCyan, AccentGold, AccentOrange)
                            )
                        )
                )
            }
        }

        // Focused Scrubber Thumb
        if (isFocused) {
            val thumbOffset = ((totalWidth - 18.dp) * clampedProgress).coerceAtLeast(0.dp)
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .padding(start = thumbOffset)
                        .size(18.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Color.White)
                        .border(2.5.dp, thumbBorderColor, RoundedCornerShape(9.dp))
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
    val scale by animateFloatAsState(if (isFocused) 1.14f else 1.0f, label = "btn_scale")
    val bgColor by animateColorAsState(
        targetValue = if (isFocused) CardFocusedBackground else Color(0x40121824),
        label = "btn_bg"
    )

    Box(
        modifier = Modifier
            .size(56.dp)
            .padding(4.dp)
            .scale(scale)
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .then(
                if (isFocused) {
                    Modifier.border(2.5.dp, FocusBorder, RoundedCornerShape(14.dp))
                } else {
                    Modifier.border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
                }
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .then(modifier)
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
            tint = if (isFocused) AccentCyanBright else TextPrimary,
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
    hasArchive: Boolean,
    playingArchiveProgram: EpgProgram?,
    programs: List<EpgProgram>,
    onSelectProgram: (EpgProgram) -> Unit,
    onDismiss: () -> Unit
) {
    val now = remember { System.currentTimeMillis() }
    val currentProgIndex = remember(programs, playingArchiveProgram) {
        if (playingArchiveProgram != null) {
            val archIdx = programs.indexOfFirst {
                it.startTime == playingArchiveProgram.startTime && it.title == playingArchiveProgram.title
            }
            if (archIdx >= 0) return@remember archIdx
        }
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

    // Ensure newly expanded accordion item's description is never clipped below the viewport
    LaunchedEffect(focusedIndex) {
        delay(235)
        try {
            val visibleInfo = listState.layoutInfo.visibleItemsInfo.find { it.index == focusedIndex }
            val viewportEnd = listState.layoutInfo.viewportEndOffset
            val viewportStart = listState.layoutInfo.viewportStartOffset
            val viewportHeight = viewportEnd - viewportStart
            if (visibleInfo != null && (visibleInfo.offset + visibleInfo.size) > viewportEnd) {
                val targetTopOffset = -(viewportHeight - visibleInfo.size).coerceAtLeast(0)
                listState.animateScrollToItem(
                    index = focusedIndex,
                    scrollOffset = targetTopOffset
                )
            }
        } catch (_: Exception) {}
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .widthIn(min = 520.dp, max = 680.dp)
                .fillMaxHeight(0.88f)
                .heightIn(max = 476.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(DarkCard)
                .border(1.dp, AccentCyan.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
                .padding(20.dp)
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
                            text = if (hasArchive) "Телепрограмма и архив (↑ назад / ↓ вперед)" else "Телепрограмма (без архива)",
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

                Spacer(modifier = Modifier.height(14.dp))

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
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(programs) { index, prog ->
                            val isItemCurrent = prog.isCurrent(now)
                            val isItemPast = prog.isPast(now)
                            val isExpanded = (index == focusedIndex)
                            val isInitialTarget = (index == currentProgIndex)
                            val isPlayingArchiveItem = playingArchiveProgram != null &&
                                playingArchiveProgram.startTime == prog.startTime &&
                                playingArchiveProgram.title == prog.title

                            val interactionSource = remember { MutableInteractionSource() }
                            val isFocused by interactionSource.collectIsFocusedAsState()

                            val cardBg by animateColorAsState(
                                targetValue = when {
                                    isFocused -> Color(0xFF1F2B38)
                                    isItemCurrent -> Color(0xFF19242E)
                                    else -> DarkSurface
                                },
                                animationSpec = tween(durationMillis = 180),
                                label = "epg_bg"
                            )
                            val cardBorder by animateColorAsState(
                                targetValue = when {
                                    isFocused -> FocusBorder
                                    isItemCurrent -> AccentCyan.copy(alpha = 0.4f)
                                    else -> Color(0x18FFFFFF)
                                },
                                animationSpec = tween(durationMillis = 180),
                                label = "epg_border"
                            )

                            val badgeText: String? = when {
                                isPlayingArchiveItem -> "ИДЁТ"
                                isItemCurrent -> "В ЭФИРЕ"
                                isItemPast && hasArchive -> "АРХИВ"
                                isItemPast -> "ПРОШЛА"
                                else -> null
                            }
                            val badgeColor = when {
                                isPlayingArchiveItem || isItemCurrent -> AccentCyan
                                isItemPast && hasArchive -> Color(0xFF81C784)
                                else -> TextSecondary
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 2.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(cardBg)
                                    .border(
                                        width = if (isFocused) 2.dp else 1.dp,
                                        color = cardBorder,
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .then(if (isInitialTarget) Modifier.focusRequester(initialFocusRequester) else Modifier)
                                    .onFocusChanged { focusState ->
                                        if (focusState.isFocused) {
                                            focusedIndex = index
                                        }
                                    }
                                    .clickable(
                                        interactionSource = interactionSource,
                                        indication = null,
                                        onClick = { onSelectProgram(prog) }
                                    )
                                    .focusable(interactionSource = interactionSource)
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    // Consistent single-line header row (never jumps on focus)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = prog.formattedStartTime(),
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                            color = if (isItemCurrent || isFocused) AccentCyan else TextSecondary,
                                            modifier = Modifier.width(52.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = prog.title,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = if (isExpanded || isItemCurrent) FontWeight.Bold else FontWeight.Medium
                                            ),
                                            color = TextPrimary,
                                            maxLines = if (isExpanded) 4 else 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (badgeText != null) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(badgeColor.copy(alpha = 0.16f))
                                                    .padding(horizontal = 7.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = badgeText,
                                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                    color = badgeColor
                                                )
                                            }
                                        }
                                    }

                                    // Smooth Accordion Expandable Drawer
                                    AnimatedVisibility(
                                        visible = isExpanded,
                                        enter = expandVertically(
                                            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
                                        ) + fadeIn(animationSpec = tween(durationMillis = 180)),
                                        exit = shrinkVertically(
                                            animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)
                                        ) + fadeOut(animationSpec = tween(durationMillis = 120))
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 10.dp)
                                        ) {
                                            Text(
                                                text = "${prog.formattedTimeRange()} • ${prog.durationMinutes()} мин.",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = AccentCyan
                                            )

                                            if (isItemCurrent) {
                                                Spacer(modifier = Modifier.height(6.dp))
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(5.dp)
                                                        .clip(RoundedCornerShape(3.dp))
                                                        .background(Color(0x33FFFFFF))
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxWidth(fraction = prog.progress(now).coerceIn(0f, 1f))
                                                            .fillMaxHeight()
                                                            .background(
                                                                Brush.horizontalGradient(
                                                                    colors = listOf(AccentCyan, AccentGold, AccentOrange)
                                                                )
                                                            )
                                                    )
                                                }
                                            }

                                            if (!prog.description.isNullOrBlank()) {
                                                Spacer(modifier = Modifier.height(8.dp))
                                                Text(
                                                    text = prog.description,
                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                        fontSize = 13.sp,
                                                        lineHeight = 18.sp
                                                    ),
                                                    color = TextSecondary
                                                )
                                            }

                                            Spacer(modifier = Modifier.height(8.dp))
                                            val actionHint = when {
                                                isItemPast && hasArchive -> "▶ Нажмите OK для запуска из архива"
                                                isItemPast -> "Архив отсутствует для этого канала"
                                                isItemCurrent && playingArchiveProgram != null -> "▶ Нажмите OK для возврата в прямой эфир"
                                                isItemCurrent && hasArchive -> "▶ В эфире (OK — смотреть с начала)"
                                                isItemCurrent -> "Сейчас в эфире"
                                                else -> "⏰ Запланировано в эфире"
                                            }
                                            Text(
                                                text = actionHint,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if ((isItemPast && hasArchive) || isItemCurrent) AccentCyan else TextSecondary
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
