package com.replayer.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.replayer.data.FavoritesManager
import com.replayer.data.IptvManager
import com.replayer.data.RecentMediaManager
import com.replayer.model.EpgProgram
import com.replayer.model.FavoriteFolder
import com.replayer.model.IptvChannel
import com.replayer.model.MediaItem
import com.replayer.ui.components.TvFocusCard
import com.replayer.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@Composable
fun HomeScreen(
    onOpenFavorite: (FavoriteFolder) -> Unit,
    onPlayMedia: (MediaItem) -> Unit,
    onPlayChannel: (IptvChannel) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val favoritesManager = remember { FavoritesManager(context) }
    val recentManager = remember { RecentMediaManager(context) }
    val iptvManager = remember { IptvManager.getInstance(context) }

    val favorites by favoritesManager.favorites.collectAsState()
    val recentList by recentManager.recentList.collectAsState()
    val channels by iptvManager.channels.collectAsState()
    val favoriteChannelIds by iptvManager.favoriteChannelIds.collectAsState()
    val epgMap by iptvManager.epgMap.collectAsState()

    val favoriteChannels = remember(channels, favoriteChannelIds) {
        iptvManager.getFavoriteChannels()
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
            .padding(horizontal = 32.dp, vertical = 24.dp)
    ) {
        // App / Screen Header
        Text(
            text = "Главная",
            style = MaterialTheme.typography.displayLarge
        )
        Text(
            text = "Недавнее воспроизведение, избранные телеканалы и папки",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            modifier = Modifier.padding(top = 4.dp, bottom = 28.dp)
        )

        // -------------------------------------------------------------
        // СТРОКА 1: ОСНОВНАЯ ЛЕНТА (Android TV Shelf)
        // Недавние файлы DLNA + Избранные ТВ каналы
        // -------------------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Недавнее и ТВ каналы",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = TextPrimary
            )
            if (recentList.isNotEmpty() || favoriteChannels.isNotEmpty()) {
                Text(
                    text = "${recentList.size + favoriteChannels.size} элементов",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (recentList.isEmpty() && favoriteChannels.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(DarkCard)
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = AccentCyan.copy(alpha = 0.6f),
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Здесь появятся ваши фильмы и избранные телеканалы",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Запустите видео из папки DLNA или отметьте канал сердечком в разделе ТВ",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        } else {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                // 1. Recent DLNA media cards
                items(recentList, key = { "recent_${it.id}" }) { recent ->
                    TvFocusCard(
                        onClick = { onPlayMedia(recent.toMediaItem()) },
                        modifier = Modifier
                            .width(240.dp)
                            .height(138.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(AccentCyan.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.VideoFile,
                                        contentDescription = null,
                                        tint = AccentCyan,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(AccentCyan.copy(alpha = 0.18f))
                                        .padding(horizontal = 7.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = "DLNA",
                                        color = AccentCyan,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Column {
                                Text(
                                    text = recent.title,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = TextPrimary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    lineHeight = 18.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Продолжить просмотр",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }

                // 2. Favorite IPTV channel cards
                itemsIndexed(favoriteChannels, key = { idx, ch -> "iptv_${ch.id}_$idx" }) { _, channel ->
                    val currentProg = remember(channel, epgMap) {
                        iptvManager.getCurrentProgram(channel)
                    }

                    TvFocusCard(
                        onClick = { onPlayChannel(channel) },
                        modifier = Modifier
                            .width(240.dp)
                            .height(138.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFFFF4081).copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Tv,
                                        contentDescription = null,
                                        tint = Color(0xFFFF4081),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    if (!channel.logoUrl.isNullOrBlank()) {
                                        AsyncImage(
                                            model = channel.logoUrl,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(Color(0xFF191C22))
                                                .padding(2.dp),
                                            contentScale = ContentScale.Fit
                                        )
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color(0xFFFF4081).copy(alpha = 0.18f))
                                            .padding(horizontal = 7.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = "ТВ КАНАЛ",
                                            color = Color(0xFFFF4081),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Default.Favorite,
                                        contentDescription = null,
                                        tint = Color(0xFFFF4081),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            Column {
                                Text(
                                    text = channel.name,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                if (currentProg != null) {
                                    Text(
                                        text = currentProg.title,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontSize = 11.sp
                                    )
                                } else {
                                    Text(
                                        text = channel.group,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(34.dp))

        // -------------------------------------------------------------
        // СТРОКА 2: ИЗБРАННЫЕ ПАПКИ DLNA (Потоньше)
        // -------------------------------------------------------------
        Text(
            text = "Избранные папки DLNA",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(14.dp))

        if (favorites.isEmpty()) {
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
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Нет избранных папок. Нажимайте сердечко рядом с папкой в UPnP / DLNA для быстрого доступа.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }
        } else {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(favorites, key = { it.id }) { fav ->
                    TvFocusCard(
                        onClick = { onOpenFavorite(fav) },
                        modifier = Modifier
                            .width(220.dp)
                            .height(68.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = AccentCyan,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = fav.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.Default.Favorite,
                                contentDescription = "В избранном",
                                tint = Color(0xFFFF4081),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(34.dp))

        // -------------------------------------------------------------
        // СТРОКА 3: ТЕЛЕПРОГРАММА ИЗБРАННЫХ КАНАЛОВ (Google TV Live Guide)
        // -------------------------------------------------------------
        Text(
            text = "Телепрограмма избранных каналов",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(14.dp))

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
                        imageVector = Icons.Default.Tv,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Отмечайте каналы сердечком в разделе «ТВ каналы», чтобы видеть их программу передач здесь.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }
        } else {
            GoogleTvLiveGuide(
                favoriteChannels = favoriteChannels,
                epgMap = epgMap,
                iptvManager = iptvManager,
                nowMs = nowMs,
                onPlayChannel = onPlayChannel
            )
        }
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
    onPlayChannel: (IptvChannel) -> Unit
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

    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Timeline Ruler Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
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
                    // Current RED time label right next to the red dot
                    Text(
                        text = nowTimeStr,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                        color = Color(0xFFFF3D57),
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
            favoriteChannels.forEach { channel ->
                val progs = remember(channel, epgMap) {
                    iptvManager.getProgramsForChannel(channel)
                }
                val slots = remember(progs, windowStart, windowEnd) {
                    buildTimelineSlots(progs, windowStart, windowEnd)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left Channel Logo Box with tiny star badge
                    Box(
                        modifier = Modifier
                            .width(logoColWidth)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF191C22))
                            .border(1.dp, Color(0xFF262B35), RoundedCornerShape(10.dp)),
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

                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = Color(0xCCFFFFFF),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(4.dp)
                                .size(11.dp)
                        )
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
                            val title = if (slot.program != null) {
                                if (slot.startedBeforeWindow) "‹  ${slot.program.title}" else slot.program.title
                            } else {
                                "Прямой эфир"
                            }

                            GoogleTvProgramTile(
                                title = if (isTooNarrowForText) "" else title,
                                onClick = { onPlayChannel(channel) },
                                modifier = Modifier
                                    .weight(weight)
                                    .fillMaxHeight()
                            )
                        }
                    }
                }
            }
        }

        // Red vertical NOW indicator line across the entire timeline
        Row(
            modifier = Modifier
                .matchParentSize()
                .padding(top = 9.dp)
        ) {
            Spacer(modifier = Modifier.width(logoColWidth + colGap))
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                val nowX = maxWidth * nowFraction
                // Top Red Circle Dot
                Box(
                    modifier = Modifier
                        .offset(x = nowX - 3.5.dp, y = 0.dp)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFF3D57))
                )
                // Continuous Vertical Red Line
                Box(
                    modifier = Modifier
                        .offset(x = nowX - 0.75.dp, y = 4.dp)
                        .width(1.5.dp)
                        .fillMaxHeight()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFFFF3D57),
                                    Color(0xEEFF3D57),
                                    Color(0x33FF3D57)
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
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (isFocused) 1.02f else 1.0f, label = "gtv_tile_scale")

    val bgColor = if (isFocused) Color(0xFFE8EAED) else Color(0xFF1A1D24)
    val borderColor = if (isFocused) Color.White else Color(0xFF282C36)
    val textColor = if (isFocused) Color(0xFF111318) else TextPrimary

    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .border(if (isFocused) 2.dp else 1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (title.isNotEmpty()) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp,
                    fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium
                ),
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
