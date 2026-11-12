package com.replayer.ui.components

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.replayer.model.EpgProgram
import com.replayer.ui.theme.AccentCyan
import com.replayer.ui.theme.AccentCyanBright
import com.replayer.ui.theme.AccentGold
import com.replayer.ui.theme.AccentOrange
import com.replayer.ui.theme.DarkSurface
import com.replayer.ui.theme.FocusBorder
import com.replayer.ui.theme.TextPrimary
import com.replayer.ui.theme.TextSecondary
import kotlinx.coroutines.delay

@OptIn(ExperimentalFoundationApi::class)
private val TopAnchorBringIntoViewSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float
    ): Float = offset
}

@OptIn(ExperimentalFoundationApi::class)
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
        val exactIdx = programs.indexOfFirst { now >= it.startTime && now < it.endTime }
        if (exactIdx >= 0) return@remember exactIdx
        val idx = programs.indexOfFirst { it.isCurrent(now) }
        if (idx >= 0) idx else programs.indexOfFirst { it.startTime > now }.let { if (it > 0) it - 1 else 0 }
    }
    val initialIndex = currentProgIndex.coerceAtLeast(0)
    var focusedIndex by remember { mutableIntStateOf(initialIndex) }
    var hasUserNavigated by remember { mutableStateOf(false) }
    var isInitialSettled by remember { mutableStateOf(programs.isEmpty()) }
    val initialFocusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialIndex,
        initialFirstVisibleItemScrollOffset = 0
    )

    LaunchedEffect(initialIndex, programs.size) {
        if (programs.isNotEmpty()) {
            isInitialSettled = false
            hasUserNavigated = false
            focusedIndex = initialIndex
            try {
                listState.scrollToItem(initialIndex, 0)
            } catch (_: Exception) {}
            withFrameNanos { }
            runCatching { initialFocusRequester.requestFocus() }
            // Cancel any PivotBringIntoViewSpec animation triggered on focus before revealing
            repeat(3) {
                withFrameNanos { }
                try {
                    listState.scrollToItem(initialIndex, 0)
                } catch (_: Exception) {}
            }
            isInitialSettled = true
        } else {
            isInitialSettled = true
        }
    }

    // Always bring whichever program the user highlights cleanly to the top without downward drop
    LaunchedEffect(focusedIndex, isInitialSettled) {
        if (!isInitialSettled || (!hasUserNavigated && focusedIndex == initialIndex)) {
            return@LaunchedEffect
        }
        try {
            listState.animateScrollToItem(index = focusedIndex, scrollOffset = 0)
        } catch (_: Exception) {}
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .alpha(if (isInitialSettled) 1f else 0f)
                .fillMaxWidth(0.72f)
                .widthIn(min = 520.dp, max = 680.dp)
                .fillMaxHeight(0.88f)
                .heightIn(max = 476.dp)
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, AccentCyan.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color(0xCC151B26))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                AccentCyan.copy(alpha = 0.12f),
                                Color.Transparent,
                                AccentOrange.copy(alpha = 0.07f)
                            )
                        )
                    )
                    .then(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            Modifier.blur(20.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                        } else Modifier
                    )
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
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
                    CompositionLocalProvider(LocalBringIntoViewSpec provides TopAnchorBringIntoViewSpec) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = 6.dp),
                            contentPadding = PaddingValues(start = 4.dp, end = 4.dp, bottom = 280.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                        itemsIndexed(programs) { index, prog ->
                            val isItemCurrent = prog.isCurrent(now)
                            val isItemPast = prog.isPast(now)
                            val isExpanded = (index == focusedIndex)
                            val isInitialTarget = (index == initialIndex)
                            val isPlayingArchiveItem = playingArchiveProgram != null &&
                                playingArchiveProgram.startTime == prog.startTime &&
                                playingArchiveProgram.title == prog.title

                            val interactionSource = remember { MutableInteractionSource() }
                            val isFocused by interactionSource.collectIsFocusedAsState()
                            var isKeyPressed by remember { mutableStateOf(false) }
                            val latestOnSelect by rememberUpdatedState { onSelectProgram(prog) }

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
                                isPlayingArchiveItem || isItemCurrent -> AccentCyanBright
                                isItemPast && hasArchive -> AccentOrange
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
                                    .focusProperties {
                                        canFocus = isInitialSettled || isInitialTarget
                                    }
                                    .onFocusChanged { focusState ->
                                        if (focusState.isFocused) {
                                            if (isInitialSettled && focusedIndex != index) {
                                                hasUserNavigated = true
                                            }
                                            focusedIndex = index
                                        } else {
                                            isKeyPressed = false
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
                                                }
                                                true
                                            }
                                            KeyEventType.KeyUp -> {
                                                val startedHere = isKeyPressed
                                                isKeyPressed = false
                                                if (startedHere) {
                                                    latestOnSelect()
                                                }
                                                true
                                            }
                                            else -> false
                                        }
                                    }
                                    .pointerInput(Unit) {
                                        detectTapGestures(onTap = { latestOnSelect() })
                                    }
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
}
