package com.replayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import com.replayer.ui.theme.AccentCyan
import com.replayer.ui.theme.SurfaceDark
import com.replayer.ui.theme.TextSecondary

enum class NavItem(val label: String, val icon: ImageVector) {
    HOME("Главная", Icons.Default.Home),
    UPNP("UPnP / DLNA", Icons.Default.Folder),
    GDRIVE("Google Drive", Icons.Default.Cloud),
    SETTINGS("Настройки", Icons.Default.Settings)
}

@Composable
fun TvNavigationRail(
    selectedItem: NavItem,
    onItemSelected: (NavItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(90.dp)
            .background(SurfaceDark)
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        // Official App Logo
        Image(
            painter = painterResource(id = com.replayer.R.drawable.app_logo_transparent),
            contentDescription = "Re:Player",
            modifier = Modifier
                .padding(bottom = 24.dp)
                .size(56.dp)
        )

        NavItem.entries.forEach { item ->
            val isSelected = item == selectedItem
            TvFocusCard(
                onClick = { onItemSelected(item) },
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .width(52.dp)
                    .height(52.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        tint = if (isSelected) AccentCyan else TextSecondary
                    )
                }
            }
        }
    }
}
