package com.replayer.ui.components

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.replayer.ui.theme.AccentCyan
import com.replayer.ui.theme.AccentGold
import com.replayer.ui.theme.AccentOrange
import com.replayer.ui.theme.DarkBackground
import com.replayer.ui.theme.DarkCard
import com.replayer.ui.theme.DarkSurface
import com.replayer.ui.theme.TextPrimary
import com.replayer.ui.theme.TextSecondary

private const val PIRATES_POSTER_URL =
    "https://avatars.mds.yandex.net/get-kinopoisk-image/1600647/f3113919-4f7d-41dd-93fe-a90e38a2e5d7/300x450"

@Composable
fun VasilisaRatingBanner(
    focusRequester: FocusRequester,
    leftFocusRequester: FocusRequester,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TvFocusCard(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .focusProperties { left = leftFocusRequester },
        focusBorderColor = AccentGold,
        backgroundColor = DarkCard,
        focusedBackgroundColor = Color(0xFF1E2638),
        cornerRadius = 14.dp,
        focusedScale = 1.015f
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            AccentGold.copy(alpha = 0.45f),
                            AccentOrange.copy(alpha = 0.35f),
                            AccentCyan.copy(alpha = 0.25f)
                        )
                    ),
                    shape = RoundedCornerShape(14.dp)
                )
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color(0x33FFB733),
                            Color(0x1A171C28),
                            Color(0x2214D4F4)
                        )
                    )
                )
                .padding(horizontal = 18.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left: Mini poster / icon + Text info
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Movie poster with fallback
                    Box(
                        modifier = Modifier
                            .size(width = 46.dp, height = 66.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(DarkSurface)
                            .border(1.dp, AccentGold.copy(alpha = 0.4f), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = PIRATES_POSTER_URL,
                            contentDescription = "Пираты Карибского моря: На странных берегах (2011)",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        Icon(
                            imageVector = Icons.Outlined.Movie,
                            contentDescription = null,
                            tint = AccentGold.copy(alpha = 0.35f),
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(
                                        Brush.horizontalGradient(
                                            listOf(AccentGold.copy(alpha = 0.25f), AccentOrange.copy(alpha = 0.25f))
                                        )
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "САМОЕ ВИДНОЕ МЕСТО • ЭКСКЛЮЗИВ",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = AccentGold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(3.dp))

                        Text(
                            text = "Василисина оценка: Пираты Карибского моря: На странных берегах (2011)",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            ),
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 6 Gold Stars + 4 Dimmed
                            repeat(6) {
                                Icon(
                                    imageVector = Icons.Rounded.Star,
                                    contentDescription = null,
                                    tint = AccentGold,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                            repeat(4) {
                                Icon(
                                    imageVector = Icons.Rounded.Star,
                                    contentDescription = null,
                                    tint = TextSecondary.copy(alpha = 0.22f),
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "6 звёзд из 10",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = AccentGold
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "· «А как еще»",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                maxLines = 1
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Right: Big rating badge + click hint
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.End) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(AccentGold, AccentOrange)
                                    )
                                )
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "6.0",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Black,
                                    fontSize = 20.sp
                                ),
                                color = DarkBackground
                            )
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "OK — рецензия",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun VasilisaReviewDialog(
    onDismiss: () -> Unit
) {
    val closeFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        closeFocusRequester.requestFocus()
    }

    BackHandler(enabled = true) {
        onDismiss()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .width(540.dp)
                .clip(RoundedCornerShape(22.dp))
                .border(
                    width = 1.dp,
                    brush = Brush.horizontalGradient(
                        colors = listOf(AccentGold.copy(alpha = 0.7f), AccentOrange.copy(alpha = 0.6f))
                    ),
                    shape = RoundedCornerShape(22.dp)
                )
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color(0xF0121722))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                AccentGold.copy(alpha = 0.12f),
                                Color.Transparent,
                                AccentOrange.copy(alpha = 0.08f)
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
                modifier = Modifier.padding(26.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(AccentGold.copy(alpha = 0.20f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "ОФИЦИАЛЬНАЯ РЕЦЕНЗИЯ",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        ),
                        color = AccentGold
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Василисина оценка",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "Пираты Карибского моря: На странных берегах (2011)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Stars row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    repeat(6) {
                        Icon(
                            imageVector = Icons.Rounded.Star,
                            contentDescription = null,
                            tint = AccentGold,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    repeat(4) {
                        Icon(
                            imageVector = Icons.Rounded.Star,
                            contentDescription = null,
                            tint = TextSecondary.copy(alpha = 0.25f),
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "6 звёзд",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = AccentGold
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Chat Quotes Container
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(DarkSurface)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ChatBubble(
                        author = "Никита",
                        text = "Мне настолько не понравилась эта четвёртая, что я и не собирался пятую смотреть",
                        isRight = false
                    )
                    ChatBubble(
                        author = "Василиса",
                        text = "6 ? А где моя оценка?",
                        isRight = true
                    )
                    ChatBubble(
                        author = "Никита",
                        text = "Ты хочешь чтобы в моём профиле на самом видном месте была твоя оценка, а моя вместе с остальными?",
                        isRight = false
                    )
                    ChatBubble(
                        author = "Василиса",
                        text = "А как еще",
                        isRight = true,
                        isHighlight = true
                    )
                }

                Spacer(modifier = Modifier.height(22.dp))

                // Close Button
                TvFocusCard(
                    onClick = onDismiss,
                    modifier = Modifier
                        .width(260.dp)
                        .height(44.dp)
                        .focusRequester(closeFocusRequester),
                    focusBorderColor = AccentGold,
                    backgroundColor = AccentGold.copy(alpha = 0.20f),
                    focusedBackgroundColor = AccentGold,
                    cornerRadius = 12.dp
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Ладно, 6 так 6!",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(
    author: String,
    text: String,
    isRight: Boolean,
    isHighlight: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isRight) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    when {
                        isHighlight -> AccentGold.copy(alpha = 0.28f)
                        isRight -> Color(0xFF2C394F)
                        else -> Color(0xFF1B2230)
                    }
                )
                .border(
                    width = if (isHighlight) 1.dp else 0.5.dp,
                    color = if (isHighlight) AccentGold else Color(0x22FFFFFF),
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = author,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = if (isRight) AccentGold else AccentCyan
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = TextPrimary
            )
        }
    }
}
