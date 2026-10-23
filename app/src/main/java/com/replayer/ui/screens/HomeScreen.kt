package com.replayer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.replayer.ui.components.NavItem
import com.replayer.ui.components.TvFocusCard
import com.replayer.ui.theme.AccentCyan
import com.replayer.ui.theme.TextPrimary
import com.replayer.ui.theme.TextSecondary

@Composable
fun HomeScreen(
    onNavigate: (NavItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp)
    ) {
        Text(
            text = "Re:Player",
            style = MaterialTheme.typography.displayLarge
        )
        Text(
            text = "Медиаплеер для домашней сети и Google Drive",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            modifier = Modifier.padding(top = 4.dp, bottom = 32.dp)
        )

        Text(
            text = "Источники медиа",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            TvFocusCard(
                onClick = { onNavigate(NavItem.UPNP) },
                modifier = Modifier
                    .width(260.dp)
                    .height(160.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = "UPnP / DLNA",
                        tint = AccentCyan,
                        modifier = Modifier.height(40.dp).width(40.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = "UPnP / DLNA", style = MaterialTheme.typography.titleMedium)
                    Text(text = "Локальная сеть Wi-Fi", style = MaterialTheme.typography.bodyMedium)
                }
            }

            TvFocusCard(
                onClick = { onNavigate(NavItem.GDRIVE) },
                modifier = Modifier
                    .width(260.dp)
                    .height(160.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Cloud,
                        contentDescription = "Google Drive",
                        tint = AccentCyan,
                        modifier = Modifier.height(40.dp).width(40.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = "Google Drive", style = MaterialTheme.typography.titleMedium)
                    Text(text = "Облачные файлы", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
