package com.replayer.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.replayer.ui.theme.AccentCyan
import com.replayer.ui.theme.AccentGold
import com.replayer.ui.theme.AccentOrange
import com.replayer.ui.theme.BorderSubtle
import com.replayer.ui.theme.CardBackground
import com.replayer.ui.theme.CardFocusedBackground
import com.replayer.ui.theme.FocusBorder
import com.replayer.ui.theme.TextSecondary
import kotlinx.coroutines.delay

@Composable
fun TvFocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusBorderColor: Color = FocusBorder,
    backgroundColor: Color = CardBackground,
    focusedBackgroundColor: Color = CardFocusedBackground,
    isSelected: Boolean = false,
    cornerRadius: Dp = 12.dp,
    enabled: Boolean = true,
    focusedScale: Float = 1.05f,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    var isKeyPressed by remember { mutableStateOf(false) }
    var isPointerPressed by remember { mutableStateOf(false) }
    var isLongPressTriggered by remember { mutableStateOf(false) }
    val isPressed = isKeyPressed || isPointerPressed
    val latestOnClick by rememberUpdatedState(onClick)
    val latestOnLongClick by rememberUpdatedState(onLongClick)
    val latestEnabled by rememberUpdatedState(enabled)

    LaunchedEffect(isPressed, latestEnabled, onLongClick != null) {
        val longClick = latestOnLongClick
        if (isPressed && latestEnabled && longClick != null) {
            delay(450)
            isLongPressTriggered = true
            longClick()
        }
    }

    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> if (isFocused) (focusedScale - 0.025f).coerceAtLeast(0.99f) else 0.99f
            isFocused -> focusedScale
            else -> 1.0f
        },
        animationSpec = tween(durationMillis = 120),
        label = "card_scale"
    )
    val resolvedBg by animateColorAsState(
        targetValue = when {
            isPressed -> focusBorderColor.copy(alpha = 0.24f)
            isFocused -> focusedBackgroundColor
            else -> backgroundColor
        },
        animationSpec = tween(durationMillis = 120),
        label = "card_bg"
    )
    val selectedGradientBg = remember {
        Brush.horizontalGradient(
            colors = listOf(
                AccentCyan.copy(alpha = 0.24f),
                AccentOrange.copy(alpha = 0.20f)
            )
        )
    }
    val selectedFocusedGradientBg = remember {
        Brush.horizontalGradient(
            colors = listOf(
                AccentCyan.copy(alpha = 0.34f),
                AccentOrange.copy(alpha = 0.26f)
            )
        )
    }
    val selectedGradientBorder = remember {
        Brush.horizontalGradient(
            colors = listOf(
                AccentCyan.copy(alpha = 0.75f),
                AccentOrange.copy(alpha = 0.65f)
            )
        )
    }
    val resolvedBorderColor by animateColorAsState(
        targetValue = when {
            isPressed -> focusBorderColor
            isFocused -> focusBorderColor
            else -> BorderSubtle
        },
        animationSpec = tween(durationMillis = 120),
        label = "card_border"
    )
    val shape = RoundedCornerShape(cornerRadius)

    val bgModifier = when {
        isSelected && isFocused -> Modifier.background(selectedFocusedGradientBg)
        isSelected -> Modifier.background(selectedGradientBg)
        else -> Modifier.background(resolvedBg)
    }
    val borderModifier = when {
        isPressed || isFocused -> Modifier.border(width = 2.dp, color = resolvedBorderColor, shape = shape)
        isSelected -> Modifier.border(width = 1.5.dp, brush = selectedGradientBorder, shape = shape)
        else -> Modifier.border(width = 1.dp, color = resolvedBorderColor, shape = shape)
    }

    Box(
        modifier = modifier
            .padding(2.dp)
            .zIndex(if (isFocused) 10f else 1f)
            .scale(scale)
            .clip(shape)
            .then(bgModifier)
            .then(borderModifier)
            .semantics { role = Role.Button }
            .onFocusChanged { state ->
                if (!state.isFocused) {
                    isKeyPressed = false
                    isLongPressTriggered = false
                }
            }
            .onKeyEvent { keyEvent ->
                if (!latestEnabled) return@onKeyEvent false
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
            .pointerInput(enabled, onLongClick != null) {
                if (enabled) {
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
            }
            .focusable(enabled = enabled, interactionSource = interactionSource),
        content = content
    )
}

@Composable
fun ReBrandProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 5.dp,
    trackColor: Color = Color(0x2EFFFFFF)
) {
    val clamped = progress.coerceIn(0f, 1f)
    val shape = RoundedCornerShape(height / 2)
    Box(
        modifier = modifier
            .height(height)
            .clip(shape)
            .background(trackColor)
    ) {
        if (clamped > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction = clamped)
                    .clip(shape)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(AccentCyan, AccentGold, AccentOrange)
                        )
                    )
            )
        }
    }
}

@Composable
fun ReLoadingBadge(
    text: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            color = AccentCyan,
            trackColor = Color(0x22FFFFFF),
            strokeWidth = 2.5.dp,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
    }
}
