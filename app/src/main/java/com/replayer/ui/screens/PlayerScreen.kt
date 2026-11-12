package com.replayer.ui.screens

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import org.videolan.libvlc.util.VLCVideoLayout
import com.replayer.data.IptvManager
import com.replayer.data.RecentMediaManager
import com.replayer.model.EpgProgram
import com.replayer.model.IptvChannel
import com.replayer.model.MediaItem
import com.replayer.model.MediaSourceType
import com.replayer.player.RePlayerEngine
import com.replayer.ui.components.EpgAccordionDialog
import com.replayer.ui.components.TrackSelectionDialog
import com.replayer.ui.components.TvIptvTimelineBar
import com.replayer.ui.components.TvLiveStatusButton
import com.replayer.ui.components.TvPlayerDualIconButton
import com.replayer.ui.components.TvPlayerPillButton
import com.replayer.ui.components.TvPrimaryPlayButton
import com.replayer.ui.components.TvTimelineBar
import com.replayer.ui.theme.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

private data class ZapBannerInfo(
    val index: Int,
    val total: Int,
    val title: String,
    val programTitle: String?,
    val timeRange: String?
)

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

    var showControls by remember { mutableStateOf(mediaItem.sourceType != MediaSourceType.IPTV) }
    var showTracksDialog by remember { mutableStateOf(false) }
    var showEpgDialog by remember { mutableStateOf(false) }
    var osdFeedback by remember { mutableStateOf<String?>(null) }
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val scope = rememberCoroutineScope()
    var isSeekScrubbing by remember { mutableStateOf(false) }
    var activeScrubTargetMs by remember { mutableLongStateOf(-1L) }
    var pendingCommitTargetMs by remember { mutableLongStateOf(-1L) }
    var displaySeekDeltaMs by remember { mutableLongStateOf(0L) }
    var lastSeekDir by remember { mutableIntStateOf(0) }
    var lastSeekStepTimeMs by remember { mutableLongStateOf(0L) }
    var consecutiveSeekTicks by remember { mutableIntStateOf(0) }
    var seekCommitJob by remember { mutableStateOf<Job?>(null) }
    var seekFadeJob by remember { mutableStateOf<Job?>(null) }
    var liveWatermarkVisible by remember { mutableStateOf(false) }
    var liveWatermarkJob by remember { mutableStateOf<Job?>(null) }
    var zapPreviewItem by remember { mutableStateOf<MediaItem?>(null) }
    var zapBanner by remember { mutableStateOf<ZapBannerInfo?>(null) }
    var zapCommitJob by remember { mutableStateOf<Job?>(null) }
    var zapHideJob by remember { mutableStateOf<Job?>(null) }
    var handledBackKeyDown by remember { mutableStateOf(false) }

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

    fun showLiveWatermark() {
        liveWatermarkVisible = true
        liveWatermarkJob?.cancel()
        liveWatermarkJob = scope.launch {
            delay(1350)
            liveWatermarkVisible = false
        }
    }

    fun switchIptvChannel(dir: Int) {
        val rawList = if (playlist.isNotEmpty()) playlist else allChannels.map { it.toMediaItem() }
        if (rawList.size <= 1) {
            osdFeedback = "В списке только один канал"
            return
        }
        val referenceItem = zapPreviewItem ?: mediaItem
        val curIdx = rawList.indexOfFirst { it.id == referenceItem.id }.let { idx ->
            if (idx >= 0) idx else rawList.indexOfFirst { it.uri == referenceItem.uri }
        }
        val nextIdx = if (curIdx >= 0) {
            (curIdx + dir + rawList.size) % rawList.size
        } else {
            0
        }
        val nextItem = rawList[nextIdx]
        zapPreviewItem = nextItem
        osdFeedback = null

        val nextChannelObj = iptvManager.findChannelById(nextItem.id)
        val nextProg = nextChannelObj?.let { iptvManager.getCurrentProgram(it) }
        zapBanner = ZapBannerInfo(
            index = nextIdx + 1,
            total = rawList.size,
            title = nextItem.title,
            programTitle = nextProg?.title,
            timeRange = nextProg?.formattedTimeRange()
        )
        zapHideJob?.cancel()
        zapHideJob = scope.launch {
            delay(2800)
            zapBanner = null
        }

        zapCommitJob?.cancel()
        zapCommitJob = scope.launch {
            delay(220)
            val target = zapPreviewItem
            zapPreviewItem = null
            if (target != null && (target.id != mediaItem.id || target.uri != mediaItem.uri)) {
                playingArchiveProgram = null
                onPlayMediaItem?.invoke(target)
            }
        }
    }

    fun commitScrubIfPending() {
        val target = pendingCommitTargetMs
        if (target < 0L) return
        pendingCommitTargetMs = -1L
        seekCommitJob?.cancel()
        engine.seekTo(target)
        seekFadeJob?.cancel()
        seekFadeJob = scope.launch {
            delay(950)
            isSeekScrubbing = false
            delay(260)
            displaySeekDeltaMs = 0L
            activeScrubTargetMs = -1L
        }
    }

    fun handleScrubStep(dir: Int, repeatCount: Int) {
        if (isIptv && playingArchiveProgram == null) {
            if (dir > 0) {
                if (showControls) {
                    osdFeedback = "Прямой эфир"
                } else {
                    showLiveWatermark()
                }
            } else if (repeatCount == 0) {
                val cur = currentProgram
                if (fullChannel.hasArchive && cur != null) {
                    playArchiveProgram(cur)
                } else {
                    osdFeedback = "Архив недоступен для этого канала"
                }
            }
            return
        }

        val nowMs = System.currentTimeMillis()
        val tickCount = if (repeatCount > 0) {
            repeatCount
        } else {
            if (lastSeekDir == dir && (nowMs - lastSeekStepTimeMs) < 650L) {
                consecutiveSeekTicks + 1
            } else {
                0
            }
        }
        lastSeekDir = dir
        lastSeekStepTimeMs = nowMs
        consecutiveSeekTicks = tickCount

        val stepMs = when {
            tickCount <= 3 -> 15_000L
            tickCount <= 7 -> 30_000L
            tickCount <= 12 -> 60_000L
            else -> 120_000L
        }

        val maxDur = if (playingArchiveProgram != null) {
            val archDur = (playingArchiveProgram!!.endTime - playingArchiveProgram!!.startTime).coerceAtLeast(0L)
            maxOf(playerState.durationMs, archDur)
        } else {
            playerState.durationMs
        }

        val currentBase = if (isSeekScrubbing && activeScrubTargetMs >= 0L) {
            activeScrubTargetMs
        } else {
            playerState.currentPositionMs.coerceAtLeast(0L)
        }
        val rawTarget = (currentBase + dir * stepMs).coerceAtLeast(0L)
        val clampedTarget = if (maxDur > 0L) rawTarget.coerceAtMost(maxDur) else rawTarget
        val actualStep = clampedTarget - currentBase

        activeScrubTargetMs = clampedTarget
        pendingCommitTargetMs = clampedTarget
        displaySeekDeltaMs = if (isSeekScrubbing) displaySeekDeltaMs + actualStep else actualStep
        isSeekScrubbing = true

        seekFadeJob?.cancel()
        seekCommitJob?.cancel()
        seekCommitJob = scope.launch {
            delay(280)
            commitScrubIfPending()
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

    // Show initial compact channel info banner when opening IPTV in fullscreen zap mode
    LaunchedEffect(Unit) {
        if (isIptv && !showControls) {
            val rawList = if (playlist.isNotEmpty()) playlist else allChannels.map { it.toMediaItem() }
            if (rawList.isNotEmpty()) {
                val curIdx = rawList.indexOfFirst { it.id == mediaItem.id }
                    .let { if (it >= 0) it else rawList.indexOfFirst { item -> item.uri == mediaItem.uri }.coerceAtLeast(0) }
                zapBanner = ZapBannerInfo(
                    index = curIdx + 1,
                    total = rawList.size,
                    title = mediaItem.title,
                    programTitle = currentProgram?.title,
                    timeRange = currentProgram?.formattedTimeRange()
                )
                zapHideJob?.cancel()
                zapHideJob = scope.launch {
                    delay(3000)
                    zapBanner = null
                }
            }
        }
    }

    // Trigger playback whenever mediaItem switches (initial file, next episode, etc.)
    LaunchedEffect(mediaItem.id, mediaItem.uri) {
        playingArchiveProgram = null
        seekCommitJob?.cancel()
        seekFadeJob?.cancel()
        liveWatermarkJob?.cancel()
        isSeekScrubbing = false
        activeScrubTargetMs = -1L
        pendingCommitTargetMs = -1L
        displaySeekDeltaMs = 0L
        liveWatermarkVisible = false
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
            zapCommitJob?.cancel()
            zapHideJob?.cancel()
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
    LaunchedEffect(showControls, lastInteractionTime, showTracksDialog, showEpgDialog) {
        if (showControls && !showTracksDialog && !showEpgDialog) {
            delay(5000)
            showControls = false
        }
    }

    // Focus management: when controls are shown, focus play button; when hidden, focus root box reliably
    LaunchedEffect(showControls, showTracksDialog, showEpgDialog) {
        if (showTracksDialog || showEpgDialog) return@LaunchedEffect
        withFrameNanos { }
        for (attempt in 0..5) {
            val focused = try {
                if (showControls) {
                    playButtonFocusRequester.requestFocus()
                } else {
                    rootFocusRequester.requestFocus()
                }
                true
            } catch (_: Exception) {
                false
            }
            if (focused) break
            delay(25)
        }
    }

    LaunchedEffect(osdFeedback) {
        if (osdFeedback != null) {
            delay(2800)
            osdFeedback = null
        }
    }

    BackHandler(enabled = true) {
        if (showEpgDialog) {
            showEpgDialog = false
            lastInteractionTime = System.currentTimeMillis()
        } else if (showTracksDialog) {
            showTracksDialog = false
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
            .focusRequester(rootFocusRequester)
            .focusable(enabled = !showControls && !showTracksDialog && !showEpgDialog)
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown && showControls) {
                    lastInteractionTime = System.currentTimeMillis()
                }
                if (isIptv && !showTracksDialog && !showEpgDialog && keyEvent.type == KeyEventType.KeyDown &&
                    keyEvent.nativeKeyEvent.repeatCount == 0
                ) {
                    val nativeCode = keyEvent.nativeKeyEvent.keyCode
                    when (nativeCode) {
                        android.view.KeyEvent.KEYCODE_CHANNEL_UP, android.view.KeyEvent.KEYCODE_PAGE_UP -> {
                            showControls = false
                            switchIptvChannel(1)
                            return@onPreviewKeyEvent true
                        }
                        android.view.KeyEvent.KEYCODE_CHANNEL_DOWN, android.view.KeyEvent.KEYCODE_PAGE_DOWN -> {
                            showControls = false
                            switchIptvChannel(-1)
                            return@onPreviewKeyEvent true
                        }
                    }
                }
                if (keyEvent.key == Key.Back || keyEvent.key == Key.Escape) {
                    return@onPreviewKeyEvent when (keyEvent.type) {
                        KeyEventType.KeyDown -> {
                            handledBackKeyDown = true
                            if (showEpgDialog) {
                                showEpgDialog = false
                                lastInteractionTime = System.currentTimeMillis()
                            } else if (showTracksDialog) {
                                showTracksDialog = false
                                lastInteractionTime = System.currentTimeMillis()
                            } else if (showControls) {
                                showControls = false
                            } else {
                                onBack()
                            }
                            true
                        }
                        KeyEventType.KeyUp -> {
                            if (handledBackKeyDown) {
                                handledBackKeyDown = false
                                true
                            } else false
                        }
                        else -> false
                    }
                }
                false
            }
            .onKeyEvent { keyEvent ->
                if (!showControls && !showTracksDialog && !showEpgDialog) {
                    if (keyEvent.type == KeyEventType.KeyDown) {
                        when (keyEvent.key) {
                            Key.DirectionRight -> {
                                if (isIptv && playingArchiveProgram == null) {
                                    if (keyEvent.nativeKeyEvent.repeatCount == 0) {
                                        switchIptvChannel(1)
                                    }
                                } else {
                                    handleScrubStep(dir = 1, repeatCount = keyEvent.nativeKeyEvent.repeatCount)
                                }
                                true
                            }
                            Key.DirectionLeft -> {
                                if (isIptv && playingArchiveProgram == null) {
                                    if (keyEvent.nativeKeyEvent.repeatCount == 0) {
                                        switchIptvChannel(-1)
                                    }
                                } else {
                                    handleScrubStep(dir = -1, repeatCount = keyEvent.nativeKeyEvent.repeatCount)
                                }
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
                            else -> if (keyEvent.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER) {
                                showControls = true
                                lastInteractionTime = System.currentTimeMillis()
                                true
                            } else false
                        }
                    } else if (keyEvent.type == KeyEventType.KeyUp &&
                        (keyEvent.key == Key.DirectionLeft || keyEvent.key == Key.DirectionRight)
                    ) {
                        if (!isIptv || playingArchiveProgram != null) {
                            commitScrubIfPending()
                        }
                        true
                    } else {
                        false
                    }
                } else {
                    if (keyEvent.type == KeyEventType.KeyDown) {
                        lastInteractionTime = System.currentTimeMillis()
                    }
                    false
                }
            }
    ) {
        // Video Surface via LibVLC (pointerInput instead of clickable so AndroidView never takes focus!)
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
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        showControls = !showControls
                        lastInteractionTime = System.currentTimeMillis()
                    })
                }
        )

        // Minimalist Transparent Seek Watermark (without controls or timeline)
        AnimatedVisibility(
            visible = isSeekScrubbing && !showControls && !showTracksDialog && !showEpgDialog,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(240)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 56.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val isForward = lastSeekDir >= 0
                val deltaLabel = if (displaySeekDeltaMs != 0L) {
                    formatSignedSeekDelta(displaySeekDeltaMs)
                } else {
                    if (isForward) "+ 0:00" else "- 0:00"
                }
                Text(
                    text = deltaLabel,
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontSize = 54.sp,
                        fontWeight = FontWeight.ExtraBold,
                        shadow = Shadow(
                            color = Color(0xBB000000),
                            offset = Offset(0f, 3f),
                            blurRadius = 18f
                        )
                    ),
                    color = if (isForward) AccentCyanBright.copy(alpha = 0.92f) else AccentOrange.copy(alpha = 0.92f)
                )
                if (activeScrubTargetMs >= 0L) {
                    Spacer(modifier = Modifier.height(6.dp))
                    val targetSub = if (!isIptv && playerState.durationMs > 0L) {
                        "${formatTime(activeScrubTargetMs)}  /  ${formatTime(playerState.durationMs)}"
                    } else {
                        formatTime(activeScrubTargetMs)
                    }
                    Text(
                        text = targetSub,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            shadow = Shadow(
                                color = Color(0xBB000000),
                                offset = Offset(0f, 2f),
                                blurRadius = 14f
                            )
                        ),
                        color = Color.White.copy(alpha = 0.80f)
                    )
                }
            }
        }

        // Faint Transparent "ЭФИР" Watermark when trying to fast-forward Live TV
        AnimatedVisibility(
            visible = liveWatermarkVisible && !showControls && !isSeekScrubbing,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(320)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 74.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(AccentCyanBright.copy(alpha = 0.55f))
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "ЭФИР",
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontSize = 28.sp,
                        letterSpacing = 3.sp,
                        fontWeight = FontWeight.Bold,
                        shadow = Shadow(
                            color = Color(0x88000000),
                            offset = Offset(0f, 2f),
                            blurRadius = 12f
                        )
                    ),
                    color = Color.White.copy(alpha = 0.42f)
                )
            }
        }

        // Large Transparent Cover-Style Informational Warnings (no heavy card box)
        AnimatedVisibility(
            visible = osdFeedback != null && !isSeekScrubbing,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(300)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 74.dp, start = 48.dp, end = 48.dp)
        ) {
            Text(
                text = osdFeedback ?: "",
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    shadow = Shadow(
                        color = Color(0xAA000000),
                        offset = Offset(0f, 2f),
                        blurRadius = 14f
                    )
                ),
                color = Color.White.copy(alpha = 0.64f),
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        }

        // Sleek IPTV Channel Zap Banner + Buffering / Stream Status when controls are hidden
        AnimatedVisibility(
            visible = zapBanner != null && !showControls && !isSeekScrubbing,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(260)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 36.dp)
        ) {
            val info = zapBanner
            if (info != null) {
                Box(
                    modifier = Modifier
                        .widthIn(min = 340.dp, max = 560.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xD4101620))
                        .border(1.dp, AccentCyan.copy(alpha = 0.42f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(AccentCyan.copy(alpha = 0.18f))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "${info.index} / ${info.total}",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = AccentCyanBright
                                )
                            }
                            Text(
                                text = info.title,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (playerState.isBuffering && zapPreviewItem == null) {
                                CircularProgressIndicator(
                                    color = AccentCyanBright,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        if (!info.programTitle.isNullOrBlank()) {
                            Text(
                                text = buildString {
                                    if (!info.timeRange.isNullOrBlank()) append("${info.timeRange}  •  ")
                                    append(info.programTitle)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = "‹ Пред. канал   •   OK — меню   •   След. канал ›",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary.copy(alpha = 0.65f)
                        )
                    }
                }
            }
        }

        // Subtle buffering / stream error indicator when controls & zap banner are hidden
        AnimatedVisibility(
            visible = !showControls && zapBanner == null && !isSeekScrubbing && (playerState.isBuffering || playerState.errorMessage != null),
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(250)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 36.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xBA0C1018))
                    .border(
                        1.dp,
                        if (playerState.errorMessage != null) AccentOrange.copy(alpha = 0.65f) else AccentCyan.copy(alpha = 0.35f),
                        RoundedCornerShape(20.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                if (playerState.errorMessage != null) {
                    Text(
                        text = playerState.errorMessage!!,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = AccentOrange
                    )
                } else {
                    CircularProgressIndicator(
                        color = AccentCyanBright,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Буферизация потока...",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary.copy(alpha = 0.85f)
                    )
                }
            }
        }

        // TV On-Screen Display (OSD) Overlay
        AnimatedVisibility(
            visible = showControls && !showTracksDialog && !showEpgDialog,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .focusProperties {
                        canFocus = showControls && !showTracksDialog && !showEpgDialog
                    }
                    .background(
                        Brush.verticalGradient(
                            0.0f to Color(0x8804060A),
                            0.24f to Color.Transparent,
                            0.56f to Color.Transparent,
                            1.0f to Color(0xE006080E)
                        )
                    )
                    .padding(horizontal = 48.dp, vertical = 32.dp)
            ) {
                // Top Bar: Title & Video Resolution
                Column(modifier = Modifier.align(Alignment.TopStart)) {
                    Text(
                        text = playerState.currentMediaTitle.ifEmpty { mediaItem.title },
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                    val resolutionText = if (playerState.videoWidth > 0 && playerState.videoHeight > 0) {
                        "${playerState.videoWidth}x${playerState.videoHeight}"
                    } else {
                        mediaItem.resolution?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
                    }
                    if (!resolutionText.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = resolutionText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = AccentCyanBright
                        )
                    }
                }

                // Bottom Controls: Sleek thin timeline & control pills
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                ) {
                    val displayedPosMs = if (isSeekScrubbing && activeScrubTargetMs >= 0L) {
                        activeScrubTargetMs
                    } else {
                        playerState.currentPositionMs
                    }

                    // Time Labels & Interactive Seek Bar / IPTV EPG Timebar
                    if (isIptv) {
                        val displayProg = playingArchiveProgram ?: currentProgram
                        val progFraction = if (playingArchiveProgram != null) {
                            val arch = playingArchiveProgram!!
                            val archDurMs = (arch.endTime - arch.startTime).coerceAtLeast(1L)
                            if (displayedPosMs in 1L..(archDurMs + 60_000L)) {
                                (displayedPosMs.toFloat() / archDurMs.toFloat()).coerceIn(0f, 1f)
                            } else if (playerState.durationMs > 0L && displayedPosMs > 0L) {
                                (displayedPosMs.toFloat() / playerState.durationMs.toFloat()).coerceIn(0f, 1f)
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
                                                if (playingArchiveProgram != null) AccentOrange.copy(alpha = 0.14f)
                                                else AccentCyan.copy(alpha = 0.14f)
                                            )
                                            .border(
                                                0.75.dp,
                                                if (playingArchiveProgram != null) AccentOrange.copy(alpha = 0.5f)
                                                else AccentCyan.copy(alpha = 0.5f),
                                                RoundedCornerShape(6.dp)
                                            )
                                            .padding(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = if (playingArchiveProgram != null) "АРХИВ" else "В ЭФИРЕ",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                            color = if (playingArchiveProgram != null) AccentOrange else AccentCyanBright
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = displayProg.title,
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
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
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = AccentCyanBright
                                )

                                Spacer(modifier = Modifier.width(16.dp))

                                TvIptvTimelineBar(
                                    progress = progFraction,
                                    isArchiveMode = playingArchiveProgram != null,
                                    focusRequester = timelineFocusRequester,
                                    downFocusRequester = playButtonFocusRequester,
                                    onScrubStep = { dir, repeat ->
                                        lastInteractionTime = System.currentTimeMillis()
                                        handleScrubStep(dir, repeat)
                                    },
                                    onScrubRelease = {
                                        commitScrubIfPending()
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
                                text = formatTime(displayedPosMs),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = TextPrimary
                            )

                            Spacer(modifier = Modifier.width(16.dp))

                            TvTimelineBar(
                                currentPositionMs = displayedPosMs,
                                durationMs = playerState.durationMs,
                                focusRequester = timelineFocusRequester,
                                downFocusRequester = playButtonFocusRequester,
                                onScrubStep = { dir, repeat ->
                                    lastInteractionTime = System.currentTimeMillis()
                                    handleScrubStep(dir, repeat)
                                },
                                onScrubRelease = {
                                    commitScrubIfPending()
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

                    Spacer(modifier = Modifier.height(16.dp))

                    // Sleek Thin Player Control Bar
                    // DLNA: [ Play/Pause (Gradient) ] [ Next File ] [ Audio & Subtitles ]
                    // TV:   [ Play/Pause (Gradient) ] [ Audio & Subtitles ] [ Schedule ] [ Эфир ]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusGroup()
                            .focusProperties {
                                up = timelineFocusRequester
                                down = FocusRequester.Cancel
                            },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Primary Centerpiece: Play / Pause (Cyan -> Gold -> Orange accent)
                        TvPrimaryPlayButton(
                            isPlaying = playerState.isPlaying,
                            focusRequester = playButtonFocusRequester,
                            modifier = Modifier.focusProperties {
                                left = FocusRequester.Cancel
                                up = timelineFocusRequester
                                down = FocusRequester.Cancel
                            },
                            onClick = {
                                engine.togglePlayPause()
                                lastInteractionTime = System.currentTimeMillis()
                            }
                        )

                        Spacer(modifier = Modifier.width(14.dp))

                        if (!isIptv) {
                            // Next File in DLNA Folder
                            val currentFileIdx = playlist.indexOfFirst { it.id == mediaItem.id }
                            val nextFile = if (currentFileIdx in 0 until playlist.size - 1) playlist[currentFileIdx + 1] else null
                            TvPlayerPillButton(
                                icon = Icons.Outlined.SkipNext,
                                contentDescription = "Следующий файл",
                                modifier = Modifier.focusProperties {
                                    up = timelineFocusRequester
                                    down = FocusRequester.Cancel
                                },
                                onClick = {
                                    if (nextFile != null) {
                                        osdFeedback = "Следующий файл: ${nextFile.title}"
                                        onPlayMediaItem?.invoke(nextFile)
                                    } else {
                                        osdFeedback = "Следующий файл отсутствует"
                                    }
                                    lastInteractionTime = System.currentTimeMillis()
                                }
                            )

                            Spacer(modifier = Modifier.width(14.dp))
                        }

                        // Unified Audio & Subtitles Button
                        TvPlayerDualIconButton(
                            leftIcon = Icons.Outlined.Audiotrack,
                            rightIcon = Icons.Outlined.ClosedCaption,
                            contentDescription = "Аудиодорожки и субтитры",
                            modifier = Modifier.focusProperties {
                                up = timelineFocusRequester
                                down = FocusRequester.Cancel
                                if (!isIptv) right = FocusRequester.Cancel
                            },
                            onClick = {
                                showTracksDialog = true
                                lastInteractionTime = System.currentTimeMillis()
                            }
                        )

                        if (isIptv) {
                            Spacer(modifier = Modifier.width(14.dp))

                            // EPG Schedule Button
                            TvPlayerPillButton(
                                icon = Icons.Outlined.EventNote,
                                contentDescription = "Программа передач",
                                modifier = Modifier.focusProperties {
                                    up = timelineFocusRequester
                                    down = FocusRequester.Cancel
                                },
                                onClick = {
                                    showEpgDialog = true
                                    lastInteractionTime = System.currentTimeMillis()
                                }
                            )

                            Spacer(modifier = Modifier.width(14.dp))

                            // Live TV ("Эфир") Pill Button: active when live, muted when watching archive
                            TvLiveStatusButton(
                                isLiveActive = (playingArchiveProgram == null),
                                modifier = Modifier.focusProperties {
                                    up = timelineFocusRequester
                                    down = FocusRequester.Cancel
                                    right = FocusRequester.Cancel
                                },
                                onClick = {
                                    if (playingArchiveProgram != null) {
                                        playingArchiveProgram = null
                                        engine.play(mediaItem)
                                        osdFeedback = "Прямой эфир: ${fullChannel.name}"
                                    } else {
                                        osdFeedback = "Сейчас прямой эфир"
                                    }
                                    lastInteractionTime = System.currentTimeMillis()
                                }
                            )
                        }
                    }
                }
            }
        }

        // Unified Audio & Subtitles Popup
        if (showTracksDialog) {
            TrackSelectionDialog(
                audioTracks = playerState.audioTracks,
                selectedAudioId = playerState.selectedAudioTrackId,
                subtitleTracks = playerState.subtitleTracks,
                selectedSubtitleId = playerState.selectedSubtitleTrackId,
                onSelectAudio = { track ->
                    engine.selectAudioTrack(track)
                    lastInteractionTime = System.currentTimeMillis()
                },
                onSelectSubtitle = { track ->
                    engine.selectSubtitleTrack(track)
                    lastInteractionTime = System.currentTimeMillis()
                },
                onDismiss = {
                    showTracksDialog = false
                    lastInteractionTime = System.currentTimeMillis()
                }
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

private fun formatSignedSeekDelta(deltaMs: Long): String {
    val sign = if (deltaMs >= 0L) "+ " else "- "
    val totalSec = abs(deltaMs) / 1000L
    val sec = totalSec % 60L
    val min = (totalSec / 60L) % 60L
    val hrs = totalSec / 3600L
    return if (hrs > 0L) {
        String.format("%s%d:%02d:%02d", sign, hrs, min, sec)
    } else {
        String.format("%s%d:%02d", sign, min, sec)
    }
}
