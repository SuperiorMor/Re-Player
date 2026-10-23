package com.replayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.Text
import com.replayer.ui.theme.AccentCyan
import com.replayer.ui.theme.CardBackground
import com.replayer.ui.theme.SurfaceDark
import com.replayer.ui.theme.TextPrimary
import com.replayer.ui.theme.TextSecondary

enum class NavItem(val label: String, val icon: ImageVector) {
    HOME("Главная", Icons.Default.Home),
    UPNP("UPnP / DLNA", Icons.Default.Folder),
    GDRIVE("Google Drive", Icons.Default.Cloud),
    SETTINGS("Настройки", Icons.Default.Settings)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvNavigationRail(
    selectedItem: NavItem,
    onItemSelected: (NavItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(100.dp)
            .background(SurfaceDark)
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        // Logo / Title Indicator
        Text(
            text = "Re:",
            color = AccentCyan,
            fontSize = 24.sp,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        NavItem.values().forEach { item ->
            val isSelected = item == selectedItem
            IconButton(
                onClick = { onItemSelected(item) },
                colors = IconButtonDefaults.colors(
                    containerColor = if (isSelected) CardBackground else SurfaceDark,
                    focusedContainerColor = AccentCyan
                ),
                shape = IconButtonDefaults.shape(RoundedCornerShape(12.dp)),
                modifier = Modifier.padding(vertical = 8.dp)
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
