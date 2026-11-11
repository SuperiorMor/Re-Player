package com.replayer.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.replayer.ui.theme.AccentCyan
import com.replayer.ui.theme.AccentCyanBright
import com.replayer.ui.theme.AccentGold
import com.replayer.ui.theme.AccentOrange
import com.replayer.ui.theme.CardFocusedBackground
import com.replayer.ui.theme.FocusBorder
import com.replayer.ui.theme.TextPrimary
import com.replayer.ui.theme.TextSecondary

@Composable
fun TvTimelineBar(
    currentPositionMs: Long,
    durationMs: Long,
    onScrubStep: (dir: Int, repeatCount: Int) -> Unit,
    onScrubRelease: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null
) {
    val progress = if (durationMs > 0) (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    TvScrubberTrack(
        progress = progress,
        focusRequester = focusRequester,
        downFocusRequester = downFocusRequester,
        onScrubStep = onScrubStep,
        onScrubRelease = onScrubRelease,
        modifier = modifier
    )
}

@Composable
fun TvIptvTimelineBar(
    progress: Float,
    isArchiveMode: Boolean,
    onScrubStep: (dir: Int, repeatCount: Int) -> Unit,
    onScrubRelease: () -> Unit,
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
        onScrubStep = onScrubStep,
        onScrubRelease = onScrubRelease,
        onCenterClick = onOpenSchedule,
        modifier = modifier
    )
}

@Composable
fun TvScrubberTrack(
    progress: Float,
    focusRequester: FocusRequester?,
    downFocusRequester: FocusRequester?,
    onScrubStep: (dir: Int, repeatCount: Int) -> Unit,
    onScrubRelease: () -> Unit,
    modifier: Modifier = Modifier,
    thumbBorderColor: Color = FocusBorder,
    onCenterClick: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val clampedProgress = progress.coerceIn(0f, 1f)

    BoxWithConstraints(
        modifier = modifier
            .height(30.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { state ->
                if (!state.hasFocus) {
                    onScrubRelease()
                }
            }
            .focusProperties {
                up = FocusRequester.Cancel
                left = FocusRequester.Cancel
                right = FocusRequester.Cancel
                if (downFocusRequester != null) {
                    down = downFocusRequester
                }
            }
            .onKeyEvent { keyEvent ->
                when (keyEvent.key) {
                    Key.DirectionLeft -> {
                        when (keyEvent.type) {
                            KeyEventType.KeyDown -> onScrubStep(-1, keyEvent.nativeKeyEvent.repeatCount)
                            KeyEventType.KeyUp -> onScrubRelease()
                        }
                        true
                    }
                    Key.DirectionRight -> {
                        when (keyEvent.type) {
                            KeyEventType.KeyDown -> onScrubStep(1, keyEvent.nativeKeyEvent.repeatCount)
                            KeyEventType.KeyUp -> onScrubRelease()
                        }
                        true
                    }
                    Key.DirectionCenter, Key.Enter -> {
                        if (keyEvent.type == KeyEventType.KeyDown && onCenterClick != null) {
                            onCenterClick()
                            true
                        } else false
                    }
                    else -> false
                }
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

        // Ultra-slim track (4dp unfocused -> 7dp focused)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (isFocused) 7.dp else 4.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (isFocused) Color(0x4DFFFFFF) else Color(0x2BFFFFFF))
        ) {
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
            val thumbOffset = ((totalWidth - 14.dp) * clampedProgress).coerceAtLeast(0.dp)
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .padding(start = thumbOffset)
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(2.dp, thumbBorderColor, CircleShape)
                )
            }
        }
    }
}

@Composable
fun TvControlCapsule(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    isActive: Boolean = false,
    isPrimaryGradient: Boolean = false,
    content: @Composable BoxScope.(isFocused: Boolean) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.09f else 1.0f,
        animationSpec = tween(durationMillis = 130),
        label = "capsule_scale"
    )
    val shape = RoundedCornerShape(22.dp)
    val brandGradient = Brush.horizontalGradient(listOf(AccentCyan, AccentGold, AccentOrange))

    val bgModifier = when {
        isPrimaryGradient -> Modifier.background(
            Brush.horizontalGradient(
                colors = if (isFocused) {
                    listOf(AccentCyan.copy(alpha = 0.34f), AccentOrange.copy(alpha = 0.32f))
                } else {
                    listOf(AccentCyan.copy(alpha = 0.16f), AccentOrange.copy(alpha = 0.16f))
                }
            )
        )
        isFocused -> Modifier.background(CardFocusedBackground.copy(alpha = 0.92f))
        isActive -> Modifier.background(AccentCyan.copy(alpha = 0.15f))
        else -> Modifier.background(Color(0x520C1018))
    }

    val borderModifier = when {
        isPrimaryGradient -> Modifier.border(
            width = if (isFocused) 2.2.dp else 1.4.dp,
            brush = brandGradient,
            shape = shape
        )
        isFocused -> Modifier.border(1.8.dp, FocusBorder, shape)
        isActive -> Modifier.border(1.1.dp, AccentCyan.copy(alpha = 0.65f), shape)
        else -> Modifier.border(1.dp, Color(0x2EFFFFFF), shape)
    }

    Box(
        modifier = Modifier
            .scale(scale)
            .then(
                if (isPrimaryGradient) {
                    Modifier.shadow(
                        elevation = if (isFocused) 20.dp else 8.dp,
                        shape = shape,
                        ambientColor = AccentCyan,
                        spotColor = AccentOrange
                    )
                } else Modifier
            )
            .clip(shape)
            .then(bgModifier)
            .then(borderModifier)
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
        content(isFocused)
    }
}

@Composable
fun TvPrimaryPlayButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null
) {
    TvControlCapsule(
        onClick = onClick,
        focusRequester = focusRequester,
        isPrimaryGradient = true,
        modifier = modifier
            .width(76.dp)
            .height(44.dp)
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Outlined.Pause else Icons.Rounded.PlayArrow,
            contentDescription = if (isPlaying) "Пауза" else "Воспроизведение",
            tint = TextPrimary,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
fun TvPlayerPillButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TvControlCapsule(
        onClick = onClick,
        modifier = modifier
            .width(50.dp)
            .height(42.dp)
    ) { isFocused ->
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (isFocused) AccentCyanBright else TextPrimary.copy(alpha = 0.88f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
fun TvPlayerDualIconButton(
    leftIcon: ImageVector,
    rightIcon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TvControlCapsule(
        onClick = onClick,
        modifier = modifier
            .width(74.dp)
            .height(42.dp)
    ) { isFocused ->
        val tint = if (isFocused) AccentCyanBright else TextPrimary.copy(alpha = 0.88f)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Icon(
                imageVector = leftIcon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(18.dp)
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(14.dp)
                    .background(Color.White.copy(alpha = 0.22f))
            )
            Icon(
                imageVector = rightIcon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
fun TvLiveStatusButton(
    isLiveActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TvControlCapsule(
        onClick = onClick,
        isActive = isLiveActive,
        modifier = modifier
            .height(42.dp)
    ) { isFocused ->
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isLiveActive -> AccentCyanBright
                            isFocused -> AccentCyanBright.copy(alpha = 0.75f)
                            else -> TextSecondary.copy(alpha = 0.45f)
                        }
                    )
            )
            Text(
                text = "Эфир",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 13.sp,
                    fontWeight = if (isLiveActive) FontWeight.Bold else FontWeight.Medium
                ),
                color = when {
                    isLiveActive || isFocused -> AccentCyanBright
                    else -> TextSecondary
                }
            )
        }
    }
}
