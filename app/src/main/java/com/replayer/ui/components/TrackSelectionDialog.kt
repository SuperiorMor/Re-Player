package com.replayer.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.replayer.model.AudioTrackInfo
import com.replayer.model.SubtitleTrackInfo
import com.replayer.ui.theme.AccentCyan
import com.replayer.ui.theme.AccentCyanBright
import com.replayer.ui.theme.AccentOrange
import com.replayer.ui.theme.BorderSubtle
import com.replayer.ui.theme.TextPrimary
import com.replayer.ui.theme.TextSecondary
import kotlinx.coroutines.delay

@Composable
fun TrackSelectionDialog(
    audioTracks: List<AudioTrackInfo>,
    selectedAudioId: String?,
    subtitleTracks: List<SubtitleTrackInfo>,
    selectedSubtitleId: String?,
    onSelectAudio: (AudioTrackInfo) -> Unit,
    onSelectSubtitle: (SubtitleTrackInfo?) -> Unit,
    onDismiss: () -> Unit
) {
    val firstAudioFocusRequester = remember { FocusRequester() }
    val firstSubFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(120)
        try {
            if (audioTracks.isNotEmpty()) {
                firstAudioFocusRequester.requestFocus()
            } else {
                firstSubFocusRequester.requestFocus()
            }
        } catch (_: Exception) {}
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .width(680.dp)
                .clip(RoundedCornerShape(20.dp))
                .border(
                    width = 1.dp,
                    brush = Brush.horizontalGradient(
                        listOf(AccentCyan.copy(alpha = 0.55f), AccentOrange.copy(alpha = 0.45f))
                    ),
                    shape = RoundedCornerShape(20.dp)
                )
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
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Аудиодорожки и субтитры",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary
                )

                Spacer(modifier = Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    // Left Column: Audio Tracks
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(bottom = 10.dp)
                        ) {
                            Icon(Icons.Outlined.Audiotrack, contentDescription = null, tint = AccentCyanBright, modifier = Modifier.size(17.dp))
                            Text(
                                text = "Звук",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = AccentCyanBright
                            )
                        }

                        if (audioTracks.isEmpty()) {
                            Text(
                                text = "Дорожки не найдены",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 280.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                itemsIndexed(audioTracks) { idx, track ->
                                    val isSelected = track.id == selectedAudioId
                                    TvFocusCard(
                                        onClick = { onSelectAudio(track) },
                                        isSelected = isSelected,
                                        cornerRadius = 12.dp,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(46.dp)
                                            .then(if (idx == 0) Modifier.focusRequester(firstAudioFocusRequester) else Modifier)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(horizontal = 14.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = track.label,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = if (isSelected) AccentCyanBright else TextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            if (isSelected) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Check,
                                                    contentDescription = null,
                                                    tint = AccentCyanBright,
                                                    modifier = Modifier.size(17.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Hairline vertical divider
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(260.dp)
                            .background(BorderSubtle)
                    )

                    // Right Column: Subtitles
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(bottom = 10.dp)
                        ) {
                            Icon(Icons.Outlined.ClosedCaption, contentDescription = null, tint = AccentOrange, modifier = Modifier.size(17.dp))
                            Text(
                                text = "Субтитры",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = AccentOrange
                            )
                        }

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 280.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item {
                                val isDisabledSelected = selectedSubtitleId == null
                                TvFocusCard(
                                    onClick = { onSelectSubtitle(null) },
                                    isSelected = isDisabledSelected,
                                    cornerRadius = 12.dp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(46.dp)
                                        .focusRequester(firstSubFocusRequester)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Отключены",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (isDisabledSelected) AccentOrange else TextPrimary,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (isDisabledSelected) {
                                            Icon(
                                                imageVector = Icons.Outlined.Check,
                                                contentDescription = null,
                                                tint = AccentOrange,
                                                modifier = Modifier.size(17.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            items(subtitleTracks) { track ->
                                val isSelected = track.id == selectedSubtitleId
                                TvFocusCard(
                                    onClick = { onSelectSubtitle(track) },
                                    isSelected = isSelected,
                                    cornerRadius = 12.dp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(46.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = track.label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (isSelected) AccentOrange else TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Outlined.Check,
                                                contentDescription = null,
                                                tint = AccentOrange,
                                                modifier = Modifier.size(17.dp)
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
