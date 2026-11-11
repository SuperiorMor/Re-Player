package com.replayer.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.replayer.R
import com.replayer.ui.theme.DarkBackground
import kotlinx.coroutines.delay

@Composable
fun AppSplashScreen(onTimeout: () -> Unit) {
    var visible by remember { mutableStateOf(true) }
    val infiniteTransition = rememberInfiniteTransition(label = "splash")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.9f, targetValue = 1.05f,
        animationSpec = infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "s"
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.6f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "a"
    )

    LaunchedEffect(Unit) {
        delay(1500) // 1.5 секунды
        visible = false
        onTimeout()
    }

    if (visible) {
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF14D4F4).copy(alpha = 0.15f), DarkBackground),
                    center = Offset(0f, 0f), radius = 1500f
                )
            ),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.app_logo_transparent),
                contentDescription = "Splash",
                modifier = Modifier.size(180.dp).scale(scale).alpha(alpha)
            )
        }
    }
}
