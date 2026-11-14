package com.replayer.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.replayer.R
import com.replayer.ui.theme.AccentCyan
import com.replayer.ui.theme.AccentOrange
import com.replayer.ui.theme.TextPrimary
import kotlinx.coroutines.delay

@Composable
fun AppSplashScreen(onTimeout: () -> Unit) {
    var isExiting by remember { mutableStateOf(false) }

    val exitAlpha by animateFloatAsState(
        targetValue = if (isExiting) 0f else 1f,
        animationSpec = tween(durationMillis = 350, easing = LinearEasing),
        label = "splash_exit"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "splash_pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "splash_scale"
    )
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.70f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "splash_glow"
    )

    LaunchedEffect(Unit) {
        // Ощутимое, солидное время заставки (2.2 секунды), после чего плавное растворение в основной интерфейс
        delay(2200)
        isExiting = true
        delay(360)
        onTimeout()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(exitAlpha)
            // 100% непрозрачный глубокий солидный фон: ничего под ним не может просвечивать
            .background(Color(0xFF07090E))
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF0E1E2C), // насыщенный глубокий сине-графитовый центр
                        Color(0xFF07090E)  // плотный глубокий черный край
                    ),
                    center = Offset.Unspecified,
                    radius = 1200f
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.app_logo_transparent),
                contentDescription = "Re:Player Logo",
                modifier = Modifier
                    .size(190.dp)
                    .scale(scale)
                    .alpha(glowAlpha)
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "RE:PLAYER",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 6.sp,
                    fontSize = 15.sp
                ),
                color = TextPrimary.copy(alpha = 0.92f)
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "МЕДИАЦЕНТР",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 3.sp,
                    fontSize = 10.sp
                ),
                color = AccentCyan.copy(alpha = 0.75f)
            )

            Spacer(modifier = Modifier.height(18.dp))

            // Тонкая аккуратная статусная полоска
            Box(
                modifier = Modifier
                    .width(42.dp)
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                AccentCyan.copy(alpha = 0.8f),
                                AccentOrange.copy(alpha = 0.8f)
                            )
                        )
                    )
            )
        }
    }
}
