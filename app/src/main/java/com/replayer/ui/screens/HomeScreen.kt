package com.replayer.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Star
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.replayer.data.FavoritesManager
import com.replayer.data.IptvManager
import com.replayer.data.RecentMediaManager
import com.replayer.data.ServerConfigManager
import com.replayer.model.EpgProgram
import com.replayer.model.FavoriteFolder
import com.replayer.model.IptvChannel
import com.replayer.model.MediaItem
import com.replayer.ui.components.VasilisaRatingBanner
import com.replayer.ui.components.VasilisaReviewDialog
import com.replayer.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@Composable
fun HomeScreen(
    firstItemFocusRequester: FocusRequester,
    navMenuFocusRequester: FocusRequester,
    onOpenFavorite: (FavoriteFolder) -> Unit,
    onPlayMedia: (MediaItem) -> Unit,
    onPlayChannel: (IptvChannel, List<IptvChannel>) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val favoritesManager = remember { FavoritesManager(context) }
    val recentManager = remember { RecentMediaManager.getInstance(context) }
    val iptvManager = remember { IptvManager.getInstance(context) }
    val serverConfigManager = remember { ServerConfigManager(context) }

    val favorites by favoritesManager.favorites.collectAsState()
    val recentList by recentManager.recentList.collectAsState()
    val progressMap by recentManager.progressMap.collectAsState()
    val connectedDevice by serverConfigManager.connectedUpnpDevice.collectAsState()
    val channels by iptvManager.channels.collectAsState()
    val favoriteChannelIds by iptvManager.favoriteChannelIds.collectAsState()
    val watchTimes by iptvManager.watchTimes.collectAsState()
    val epgMap by iptvManager.epgMap.collectAsState()

    val favoriteChannels = remember(channels, favoriteChannelIds, watchTimes) {
        iptvManager.getFavoriteChannels()
    }

    LaunchedEffect(connectedDevice?.contentDirectoryControlUrl, recentList) {
        if (recentList.any { it.thumbnailUrl.isNullOrBlank() }) {
            recentManager.resolveMissingPosters(connectedDevice?.contentDirectoryControlUrl)
        }
    }

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(30_000L)
            nowMs = System.currentTimeMillis()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 28.dp, end = 28.dp, top = 42.dp, bottom = 18.dp)
    ) {
        var showVasilisaDialog by remember { mutableStateOf(false) }

        // -------------------------------------------------------------
        // САМОЕ ВИДНОЕ МЕСТО: ВАСИЛИСИНА ОЦЕНКА
        // -------------------------------------------------------------
        VasilisaRatingBanner(
            focusRequester = firstItemFocusRequester,
            leftFocusRequester = navMenuFocusRequester,
            onClick = { showVasilisaDialog = true }
        )

        Spacer(modifier = Modifier.height(18.dp))

        // -------------------------------------------------------------
        // ВЕРХНИЙ БЛОК: СЛЕВА ПОСТЕРЫ (2:3) | СПРАВА «ИЗБРАННЫЕ ПАПКИ» ДО КРАЯ ЭКРАНА
        // (Выровнен по видимой графике левого логотипа: y ≈ 44..71dp)
        // -------------------------------------------------------------
        val posterSectionHeight = 222.dp
        val recentPosters = remember(recentList) { recentList.take(2) }
        val allHomeShelvesEmpty = recentPosters.isEmpty() && favorites.isEmpty() && favoriteChannels.isEmpty()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            // LEFT COLUMN: Продолжить просмотр (вытянутые постеры формата 2:3)
            Column(modifier = Modifier.width(342.dp)) {
                Text(
                    text = "Продолжить просмотр",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    ),
                    color = TextPrimary,
                    maxLines = 1
                )

                Spacer(modifier = Modifier.height(10.dp))

                if (recentPosters.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(posterSectionHeight)
                            .clip(RoundedCornerShape(12.dp))
                            .background(DarkCard)
                            .then(
                                if (allHomeShelvesEmpty) {
                                    Modifier
                                        .focusProperties { left = navMenuFocusRequester }
                                        .focusable()
                                } else Modifier
                            )
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Outlined.PlayCircle,
                                contentDescription = null,
                                tint = AccentCyan.copy(alpha = 0.55f),
                                modifier = Modifier.size(34.dp)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Нет недавних видео",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        recentPosters.forEachIndexed { idx, recent ->
                            val watchProgress = remember(recent, progressMap) {
                                (progressMap[recent.id]?.progress()
                                    ?: progressMap[recent.uri]?.progress()
                                    ?: recent.progress()).coerceIn(0f, 1f)
                            }

                            HomeShelfFocusCard(
                                onClick = { onPlayMedia(recent.toMediaItem()) },
                                modifier = Modifier
                                    .height(posterSectionHeight)
                                    .aspectRatio(2f / 3f, matchHeightConstraintsFirst = true)
                                    .then(
                                        if (idx == 0) {
                                            Modifier.focusProperties { left = navMenuFocusRequester }
                                        } else Modifier
                                    )
                            ) { isFocused ->
                                Box(modifier = Modifier.fillMaxSize()) {
                                    // Background: Full-bleed vertical cover poster OR sleek slate fallback
                                    if (!recent.thumbnailUrl.isNullOrBlank()) {
                                        AsyncImage(
                                            model = recent.thumbnailUrl,
                                            contentDescription = recent.title,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(
                                                    Brush.verticalGradient(
                                                        colors = listOf(
                                                            Color(0xFF222A36),
                                                            Color(0xFF141820)
                                                        )
                                                    )
                                                )
                                        )
                                        // Subtle gradient watch fill across fallback poster background
                                        if (watchProgress > 0f) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxHeight()
                                                    .fillMaxWidth(fraction = watchProgress)
                                                    .background(
                                                        Brush.horizontalGradient(
                                                            colors = listOf(
                                                                AccentCyan.copy(alpha = 0.06f),
                                                                AccentCyan.copy(alpha = 0.20f)
                                                            )
                                                        )
                                                    )
                                            )
                                        }
                                    }

                                    // Bottom readability scrim
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .fillMaxHeight(0.50f)
                                            .align(Alignment.BottomCenter)
                                            .background(
                                                Brush.verticalGradient(
                                                    colors = listOf(
                                                        Color.Transparent,
                                                        Color(0xE60B0E14)
                                                    )
                                                )
                                            )
                                    )

                                    // Single-line title with marquee on focus
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .align(Alignment.BottomCenter)
                                    ) {
                                        Text(
                                            text = recent.title,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold
                                            ),
                                            color = TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 5.dp)
                                                .then(
                                                    if (isFocused) {
                                                        Modifier.basicMarquee(
                                                            iterations = Int.MAX_VALUE,
                                                            initialDelayMillis = 250,
                                                            repeatDelayMillis = 800,
                                                            velocity = 42.dp
                                                        )
                                                    } else Modifier
                                                )
                                        )

                                        // Raised high-contrast watch progress pill (Amber-Orange vs Cyan FocusBorder)
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(start = 10.dp, end = 10.dp, bottom = 9.dp)
                                                .height(5.dp)
                                                .clip(RoundedCornerShape(3.dp))
                                                .background(Color(0xD907090E))
                                                .border(0.5.dp, Color(0x40FFFFFF), RoundedCornerShape(3.dp))
                                        ) {
                                            if (watchProgress > 0f) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxHeight()
                                                        .fillMaxWidth(fraction = watchProgress)
                                                        .clip(RoundedCornerShape(3.dp))
                                                        .background(
                                                            Brush.horizontalGradient(
                                                                colors = listOf(
                                                                    AccentGold,
                                                                    AccentOrange
                                                                )
                                                            )
                                                        )
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

            Spacer(modifier = Modifier.width(20.dp))

            // RIGHT COLUMN: Избранные папки DLNA (растянуты до правого края экрана)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Избранные папки",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    ),
                    color = TextPrimary,
                    maxLines = 1
                )

                Spacer(modifier = Modifier.height(10.dp))

                if (favorites.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(posterSectionHeight)
                            .clip(RoundedCornerShape(12.dp))
                            .background(DarkCard)
                            .padding(horizontal = 24.dp, vertical = 20.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.FolderOpen,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(26.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Нажимайте звёздочку ★ рядом с папкой в DLNA для быстрого доступа",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    }
                } else {
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(posterSectionHeight)
                    ) {
                        val gridWidth = maxWidth
                        val cardWidth = if (favorites.size <= 2) {
                            (gridWidth - 16.dp).coerceAtLeast(200.dp)
                        } else {
                            ((gridWidth - 28.dp) / 2f).coerceAtLeast(190.dp)
                        }

                        LazyHorizontalGrid(
                            rows = GridCells.Fixed(2),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            gridItemsIndexed(favorites, key = { idx, fav -> "${fav.id}_$idx" }) { idx, fav ->
                                HomeShelfFocusCard(
                                    onClick = { onOpenFavorite(fav) },
                                    onLongClick = {
                                        if (favorites.size <= 1) {
                                            val fallback = if (recentPosters.isNotEmpty()) firstItemFocusRequester else navMenuFocusRequester
                                            runCatching { fallback.requestFocus() }
                                        }
                                        favoritesManager.toggleFavorite(fav)
                                    },
                                    modifier = Modifier
                                        .width(cardWidth)
                                        .fillMaxHeight()
                                        .then(
                                            if (recentPosters.isEmpty() && idx == 0) {
                                                Modifier.focusProperties { left = navMenuFocusRequester }
                                            } else Modifier
                                        )
                                ) { _ ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(40.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(AccentCyan.copy(alpha = 0.14f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.FolderOpen,
                                                    contentDescription = null,
                                                    tint = AccentCyan,
                                                    modifier = Modifier.size(21.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Text(
                                                text = fav.title,
                                                style = MaterialTheme.typography.titleSmall.copy(
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    lineHeight = 18.sp
                                                ),
                                                color = TextPrimary,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Icon(
                                            imageVector = Icons.Rounded.Star,
                                            contentDescription = "В избранном",
                                            tint = AccentOrange,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // -------------------------------------------------------------
        // НИЖНИЙ БЛОК: ТЕЛЕПРОГРАММА ИЗБРАННЫХ КАНАЛОВ (Google TV Live Guide)
        // -------------------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = "Телепрограмма избранных каналов",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                ),
                color = TextPrimary
            )
            if (favoriteChannels.isNotEmpty()) {
                Text(
                    text = "→ на канале — убрать из избранного",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary.copy(alpha = 0.60f)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (favoriteChannels.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(DarkCard)
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.LiveTv,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Отмечайте каналы звёздочкой в разделе «ТВ каналы», чтобы видеть их программу передач здесь.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }
        } else {
            val hasUpperShelf = true
            val guideFirstFocusRequester = remember { FocusRequester() }
            GoogleTvLiveGuide(
                favoriteChannels = favoriteChannels,
                epgMap = epgMap,
                iptvManager = iptvManager,
                nowMs = nowMs,
                firstItemFocusRequester = guideFirstFocusRequester,
                navMenuFocusRequester = navMenuFocusRequester,
                hasUpperShelf = hasUpperShelf,
                onPlayChannel = { ch -> onPlayChannel(ch, favoriteChannels) },
                onRemoveFavorite = { ch -> iptvManager.toggleFavorite(ch.id) }
            )
        }
    }

    if (showVasilisaDialog) {
        VasilisaReviewDialog(
            onDismiss = { showVasilisaDialog = false }
        )
    }
}

private data class TimelineSlot(
    val program: EpgProgram?,
    val startMs: Long,
    val endMs: Long,
    val startedBeforeWindow: Boolean
)

private fun buildTimelineSlots(
    programs: List<EpgProgram>,
    windowStart: Long,
    windowEnd: Long
): List<TimelineSlot> {
    val overlapping = programs
        .filter { it.endTime > windowStart && it.startTime < windowEnd }
        .sortedBy { it.startTime }

    if (overlapping.isEmpty()) {
        return listOf(TimelineSlot(null, windowStart, windowEnd, false))
    }

    val rawSlots = mutableListOf<TimelineSlot>()
    var cursor = windowStart
    val minGapMs = 4 * 60_000L

    for (prog in overlapping) {
        val clippedStart = maxOf(prog.startTime, windowStart)
        val clippedEnd = minOf(prog.endTime, windowEnd)
        if (clippedEnd <= clippedStart) continue

        if (clippedStart - cursor >= minGapMs) {
            rawSlots.add(TimelineSlot(null, cursor, clippedStart, false))
        }
        val actualStart = if (clippedStart - cursor < minGapMs && rawSlots.isEmpty()) windowStart else clippedStart
        rawSlots.add(
            TimelineSlot(
                program = prog,
                startMs = actualStart,
                endMs = clippedEnd,
                startedBeforeWindow = prog.startTime < windowStart
            )
        )
        cursor = maxOf(cursor, clippedEnd)
    }

    if (windowEnd - cursor >= minGapMs) {
        rawSlots.add(TimelineSlot(null, cursor, windowEnd, false))
    } else if (rawSlots.isNotEmpty()) {
        val last = rawSlots.removeAt(rawSlots.lastIndex)
        rawSlots.add(last.copy(endMs = windowEnd))
    }

    return if (rawSlots.isEmpty()) {
        listOf(TimelineSlot(null, windowStart, windowEnd, false))
    } else {
        rawSlots
    }
}

@Composable
private fun GoogleTvLiveGuide(
    favoriteChannels: List<IptvChannel>,
    epgMap: Map<String, List<EpgProgram>>,
    iptvManager: IptvManager,
    nowMs: Long,
    firstItemFocusRequester: FocusRequester,
    navMenuFocusRequester: FocusRequester,
    hasUpperShelf: Boolean,
    onPlayChannel: (IptvChannel) -> Unit,
    onRemoveFavorite: (IptvChannel) -> Unit
) {
    val totalWindowMs = 75 * 60_000L // 1h 15m visible window matching Google TV
    val windowStart = nowMs - 15 * 60_000L // NOW sits cleanly at 20%
    val windowEnd = windowStart + totalWindowMs
    val nowFraction = 0.20f

    val dateStr = remember(nowMs) {
        SimpleDateFormat("EE, dd.MM", Locale("ru")).format(Date(nowMs)).uppercase()
    }
    val nowTimeStr = remember(nowMs) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(nowMs))
    }
    val halfHourTicks = remember(nowMs) {
        val step = 30 * 60_000L
        val firstTick = ((windowStart / step) + 1) * step
        val ticks = mutableListOf<Pair<Float, String>>()
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        var t = firstTick
        while (t < windowEnd - 6 * 60_000L) {
            if (abs(t - nowMs) >= 7 * 60_000L && (t - windowStart) >= 5 * 60_000L) {
                val frac = (t - windowStart).toFloat() / totalWindowMs.toFloat()
                ticks.add(frac to fmt.format(Date(t)))
            }
            t += step
        }
        ticks
    }

    val logoColWidth = 112.dp
    val colGap = 10.dp

    val rowRequesters = remember { mutableStateMapOf<String, FocusRequester>() }
    fun requesterFor(channelId: String): FocusRequester =
        rowRequesters.getOrPut(channelId) { FocusRequester() }

    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Timeline Ruler Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
                    .height(26.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.width(logoColWidth),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = dateStr,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                        color = TextSecondary,
                        maxLines = 1
                    )
                    Text(
                        text = "В ЭФИРЕ",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                        color = TextSecondary,
                        maxLines = 1
                    )
                }

                Spacer(modifier = Modifier.width(colGap))

                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    val widthDp = maxWidth
                    // Current Amber-Orange time label right next to the live dot
                    Text(
                        text = nowTimeStr,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                        color = AccentOrange,
                        modifier = Modifier
                            .offset(x = widthDp * nowFraction + 8.dp)
                            .align(Alignment.CenterStart)
                    )

                    // 30-min grid markers (16:00, 16:30, ...)
                    halfHourTicks.forEach { (frac, label) ->
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                            color = TextSecondary,
                            modifier = Modifier
                                .offset(x = widthDp * frac)
                                .align(Alignment.CenterStart)
                        )
                    }
                }
            }

            // Channel Rows
            favoriteChannels.forEachIndexed { channelIdx, channel ->
                key(channel.id) {
                    val progs = remember(channel, epgMap) {
                        iptvManager.getProgramsForChannel(channel)
                    }
                    val slots = remember(progs, windowStart, windowEnd) {
                        buildTimelineSlots(progs, windowStart, windowEnd)
                    }

                    val rowFocusReq = if (channelIdx == 0 && !hasUpperShelf) {
                        firstItemFocusRequester
                    } else {
                        remember(channel.id) { requesterFor(channel.id) }
                    }
                    SideEffect {
                        rowRequesters[channel.id] = rowFocusReq
                    }

                    val rowInteractionSource = remember { MutableInteractionSource() }
                    val isRowFocused by rowInteractionSource.collectIsFocusedAsState()
                    val removeFocusRequester = remember(channel.id) { FocusRequester() }
                    val removeInteractionSource = remember { MutableInteractionSource() }
                    val isRemoveFocused by removeInteractionSource.collectIsFocusedAsState()

                    var isRowKeyPressed by remember(channel.id) { mutableStateOf(false) }
                    var isRemoveKeyPressed by remember(channel.id) { mutableStateOf(false) }

                    val isAnyFocused = isRowFocused || isRemoveFocused
                    val rowScale by animateFloatAsState(
                        targetValue = when {
                            isRowKeyPressed -> 0.985f
                            isAnyFocused -> 1.015f
                            else -> 1.0f
                        },
                        animationSpec = tween(durationMillis = 120),
                        label = "gtv_row_scale"
                    )
                    val removeScale by animateFloatAsState(
                        targetValue = when {
                            isRemoveKeyPressed -> 0.92f
                            isRemoveFocused -> 1.05f
                            else -> 1.0f
                        },
                        animationSpec = tween(durationMillis = 120),
                        label = "gtv_remove_scale"
                    )

                    val prevRowReq = if (channelIdx > 0) requesterFor(favoriteChannels[channelIdx - 1].id) else null
                    val nextRowReq = if (channelIdx < favoriteChannels.lastIndex) requesterFor(favoriteChannels[channelIdx + 1].id) else null

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .scale(rowScale)
                            .zIndex(if (isAnyFocused) 2f else 0f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isAnyFocused) CardFocusedBackground else Color.Transparent)
                            .border(
                                width = if (isRowFocused) 2.dp else if (isRemoveFocused) 1.5.dp else 1.dp,
                                color = when {
                                    isRowFocused -> FocusBorder
                                    isRemoveFocused -> AccentOrange.copy(alpha = 0.70f)
                                    else -> Color.Transparent
                                },
                                shape = RoundedCornerShape(14.dp)
                            )
                            .padding(horizontal = 4.dp, vertical = 3.dp)
                            .height(56.dp)
                    ) {
                        // Main Full-Width Channel Row
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .focusRequester(rowFocusReq)
                                .focusProperties {
                                    left = navMenuFocusRequester
                                    right = removeFocusRequester
                                    if (prevRowReq != null) up = prevRowReq
                                    if (nextRowReq != null) down = nextRowReq
                                }
                                .onFocusChanged { state ->
                                    if (!state.isFocused) {
                                        isRowKeyPressed = false
                                    }
                                }
                                .onKeyEvent { keyEvent ->
                                    val isConfirm = keyEvent.key == Key.DirectionCenter ||
                                        keyEvent.key == Key.Enter ||
                                        keyEvent.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
                                    if (!isConfirm) return@onKeyEvent false
                                    when (keyEvent.type) {
                                        KeyEventType.KeyDown -> {
                                            if (keyEvent.nativeKeyEvent.repeatCount == 0) {
                                                isRowKeyPressed = true
                                            }
                                            true
                                        }
                                        KeyEventType.KeyUp -> {
                                            val startedHere = isRowKeyPressed
                                            isRowKeyPressed = false
                                            if (startedHere) {
                                                onPlayChannel(channel)
                                            }
                                            true
                                        }
                                        else -> false
                                    }
                                }
                                .pointerInput(channel.id) {
                                    detectTapGestures(onTap = { onPlayChannel(channel) })
                                }
                                .focusable(interactionSource = rowInteractionSource),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Left Channel Logo Box (clean logo only)
                            Box(
                                modifier = Modifier
                                    .width(logoColWidth)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isAnyFocused) Color(0xFF242934) else Color(0xFF191C22))
                                    .border(
                                        width = 1.dp,
                                        color = if (isAnyFocused) Color(0x66FFFFFF) else Color(0xFF262B35),
                                        shape = RoundedCornerShape(10.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (!channel.logoUrl.isNullOrBlank()) {
                                    AsyncImage(
                                        model = channel.logoUrl,
                                        contentDescription = channel.name,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        contentScale = ContentScale.Fit
                                    )
                                } else {
                                    Text(
                                        text = channel.name,
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
                                        color = TextPrimary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 8.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(colGap))

                            // Right Timeline Slots
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                slots.forEach { slot ->
                                    val durationMs = (slot.endMs - slot.startMs).coerceAtLeast(60_000L)
                                    val weight = (durationMs.toFloat() / totalWindowMs.toFloat()).coerceAtLeast(0.05f)
                                    val isTooNarrowForText = durationMs < 6 * 60_000L
                                    val isLiveSlot = nowMs in slot.startMs until slot.endMs
                                    val title = if (slot.program != null) {
                                        if (slot.startedBeforeWindow) "‹  ${slot.program.title}" else slot.program.title
                                    } else {
                                        "Прямой эфир"
                                    }

                                    GoogleTvProgramTile(
                                        title = if (isTooNarrowForText) "" else title,
                                        isHighlighted = isRowFocused && isLiveSlot,
                                        isRowFocused = isAnyFocused,
                                        modifier = Modifier
                                            .weight(weight)
                                            .fillMaxHeight()
                                    )
                                }
                            }
                        }

                        // Orange Minus Button: hidden (2dp alpha 0) until Right is pressed to focus it
                        fun triggerRemove() {
                            val neighborReq = nextRowReq ?: prevRowReq ?: if (hasUpperShelf) firstItemFocusRequester else navMenuFocusRequester
                            try {
                                neighborReq.requestFocus()
                            } catch (_: Exception) {}
                            onRemoveFavorite(channel)
                        }

                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 2.dp)
                                .width(if (isRemoveFocused) 48.dp else 2.dp)
                                .fillMaxHeight()
                                .padding(vertical = 3.dp)
                                .scale(removeScale)
                                .zIndex(5f)
                                .then(
                                    if (isRemoveFocused) {
                                        Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Color(0xF018110B))
                                            .background(AccentOrange.copy(alpha = 0.28f))
                                            .border(
                                                width = 2.dp,
                                                color = AccentOrange,
                                                shape = RoundedCornerShape(10.dp)
                                            )
                                    } else Modifier
                                )
                                .focusRequester(removeFocusRequester)
                                .focusProperties {
                                    left = rowFocusReq
                                    right = FocusRequester.Cancel
                                    if (prevRowReq != null) up = prevRowReq
                                    if (nextRowReq != null) down = nextRowReq
                                }
                                .onFocusChanged { state ->
                                    if (!state.isFocused) {
                                        isRemoveKeyPressed = false
                                    }
                                }
                                .onPreviewKeyEvent { keyEvent ->
                                    if (keyEvent.type == KeyEventType.KeyDown &&
                                        (keyEvent.key == Key.Back || keyEvent.key == Key.Escape)
                                    ) {
                                        try {
                                            rowFocusReq.requestFocus()
                                        } catch (_: Exception) {}
                                        true
                                    } else {
                                        false
                                    }
                                }
                                .onKeyEvent { keyEvent ->
                                    val isConfirm = keyEvent.key == Key.DirectionCenter ||
                                        keyEvent.key == Key.Enter ||
                                        keyEvent.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
                                    if (!isConfirm) return@onKeyEvent false
                                    when (keyEvent.type) {
                                        KeyEventType.KeyDown -> {
                                            if (keyEvent.nativeKeyEvent.repeatCount == 0) {
                                                isRemoveKeyPressed = true
                                            }
                                            true
                                        }
                                        KeyEventType.KeyUp -> {
                                            val startedHere = isRemoveKeyPressed
                                            isRemoveKeyPressed = false
                                            if (startedHere) {
                                                triggerRemove()
                                            }
                                            true
                                        }
                                        else -> false
                                    }
                                }
                                .pointerInput(channel.id) {
                                    detectTapGestures(onTap = { triggerRemove() })
                                }
                                .focusable(interactionSource = removeInteractionSource),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isRemoveFocused) {
                                Icon(
                                    imageVector = Icons.Rounded.Remove,
                                    contentDescription = "Убрать из избранного",
                                    tint = AccentOrange,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Amber-Orange vertical NOW indicator line across the entire timeline
        Row(
            modifier = Modifier
                .matchParentSize()
                .padding(top = 9.dp, start = 4.dp, end = 4.dp)
        ) {
            Spacer(modifier = Modifier.width(logoColWidth + colGap))
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                val nowX = maxWidth * nowFraction
                // Top Amber Circle Dot
                Box(
                    modifier = Modifier
                        .offset(x = nowX - 3.5.dp, y = 0.dp)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(AccentOrange)
                )
                // Continuous Vertical Amber-Orange Line
                Box(
                    modifier = Modifier
                        .offset(x = nowX - 0.75.dp, y = 4.dp)
                        .width(1.5.dp)
                        .fillMaxHeight()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    AccentOrange,
                                    AccentOrange.copy(alpha = 0.85f),
                                    AccentOrange.copy(alpha = 0.2f)
                                )
                            )
                        )
                )
            }
        }
    }
}

@Composable
private fun GoogleTvProgramTile(
    title: String,
    isHighlighted: Boolean,
    isRowFocused: Boolean,
    modifier: Modifier = Modifier
) {
    val bgColor = when {
        isHighlighted -> Color(0xFFE8EAED)
        isRowFocused -> Color(0xFF232732)
        else -> Color(0xFF1A1D24)
    }
    val borderColor = when {
        isHighlighted -> Color.White
        isRowFocused -> Color(0xFF3E4554)
        else -> Color(0xFF282C36)
    }
    val textColor = if (isHighlighted) Color(0xFF111318) else TextPrimary

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (title.isNotEmpty()) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp,
                    fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Medium
                ),
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun HomeShelfFocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.(isFocused: Boolean) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    var isKeyPressed by remember { mutableStateOf(false) }
    var isPointerPressed by remember { mutableStateOf(false) }
    var isLongPressTriggered by remember { mutableStateOf(false) }
    val isPressed = isKeyPressed || isPointerPressed
    val latestOnClick by rememberUpdatedState(onClick)
    val latestOnLongClick by rememberUpdatedState(onLongClick)

    LaunchedEffect(isPressed, onLongClick != null) {
        val longClick = latestOnLongClick
        if (isPressed && longClick != null) {
            delay(450)
            isLongPressTriggered = true
            longClick()
        }
    }

    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> if (isFocused) 1.025f else 0.99f
            isFocused -> 1.05f
            else -> 1.0f
        },
        animationSpec = tween(durationMillis = 120),
        label = "shelf_card_scale"
    )

    Box(
        modifier = modifier
            .padding(3.dp)
            .zIndex(if (isFocused) 10f else 1f)
            .scale(scale)
            .clip(RoundedCornerShape(14.dp))
            .background(if (isFocused) CardFocusedBackground else CardBackground)
            .then(
                if (isFocused || isPressed) {
                    Modifier.border(2.5.dp, FocusBorder, RoundedCornerShape(14.dp))
                } else {
                    Modifier.border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
                }
            )
            .onFocusChanged { state ->
                if (!state.isFocused) {
                    isKeyPressed = false
                    isLongPressTriggered = false
                }
            }
            .onKeyEvent { keyEvent ->
                val isConfirm = keyEvent.key == Key.DirectionCenter ||
                    keyEvent.key == Key.Enter ||
                    keyEvent.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
                if (!isConfirm) return@onKeyEvent false
                when (keyEvent.type) {
                    KeyEventType.KeyDown -> {
                        if (keyEvent.nativeKeyEvent.repeatCount == 0) {
                            isKeyPressed = true
                            isLongPressTriggered = false
                        }
                        true
                    }
                    KeyEventType.KeyUp -> {
                        val startedHere = isKeyPressed
                        val didLongPress = isLongPressTriggered
                        isKeyPressed = false
                        isLongPressTriggered = false
                        if (startedHere && !didLongPress) {
                            latestOnClick()
                        }
                        true
                    }
                    else -> false
                }
            }
            .pointerInput(onLongClick != null) {
                detectTapGestures(
                    onPress = {
                        isPointerPressed = true
                        isLongPressTriggered = false
                        try {
                            tryAwaitRelease()
                        } finally {
                            isPointerPressed = false
                        }
                    },
                    onTap = {
                        if (!isLongPressTriggered) {
                            latestOnClick()
                        }
                        isLongPressTriggered = false
                    }
                )
            }
            .focusable(interactionSource = interactionSource)
    ) {
        content(isFocused)
    }
}
