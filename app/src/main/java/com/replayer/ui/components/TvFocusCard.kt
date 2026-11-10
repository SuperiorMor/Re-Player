package com.replayer.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
    content: @Composable BoxScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.025f else 1.0f,
        animationSpec = tween(durationMillis = 140),
        label = "card_scale"
    )
    val resolvedBg by animateColorAsState(
        targetValue = when {
            isFocused -> focusedBackgroundColor
            isSelected -> AccentCyan.copy(alpha = 0.13f)
            else -> backgroundColor
        },
        animationSpec = tween(durationMillis = 140),
        label = "card_bg"
    )
    val resolvedBorderColor by animateColorAsState(
        targetValue = when {
            isFocused -> focusBorderColor
            isSelected -> AccentCyan.copy(alpha = 0.48f)
            else -> BorderSubtle
        },
        animationSpec = tween(durationMillis = 140),
        label = "card_border"
    )
    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier
            .padding(2.dp)
            .zIndex(if (isFocused) 10f else 1f)
            .scale(scale)
            .clip(shape)
            .background(resolvedBg)
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = resolvedBorderColor,
                shape = shape
            )
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .focusable(interactionSource = interactionSource),
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
