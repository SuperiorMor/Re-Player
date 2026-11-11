package com.replayer.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.replayer.ui.theme.AccentCyanBright
import com.replayer.ui.theme.SurfaceDark
import com.replayer.ui.theme.TextPrimary
import com.replayer.ui.theme.TextSecondary

enum class NavItem(val label: String, val icon: ImageVector) {
    HOME("Главная", Icons.Outlined.Home),
    UPNP("UPnP / DLNA", Icons.Outlined.FolderOpen),
    IPTV("ТВ каналы", Icons.Outlined.LiveTv),
    SETTINGS("Настройки", Icons.Outlined.Settings)
}

@Composable
fun TvNavigationRail(
    selectedItem: NavItem,
    navMenuFocusRequester: FocusRequester,
    activeScreenFocusRequester: FocusRequester,
    onItemSelected: (NavItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxHeight()
            .width(92.dp)
            .background(SurfaceDark.copy(alpha = 0.72f))
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(top = 24.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            // Official App Logo (y = 24dp .. 88dp)
            Image(
                painter = painterResource(id = com.replayer.R.drawable.app_logo_transparent),
                contentDescription = "Re:Player",
                modifier = Modifier.size(64.dp)
            )

            Spacer(modifier = Modifier.height(22.dp))

            NavItem.entries.forEachIndexed { idx, item ->
                val isSelected = item == selectedItem
                var isItemFocused by remember { mutableStateOf(false) }

                TvFocusCard(
                    onClick = {
                        if (!isSelected) {
                            onItemSelected(item)
                        } else {
                            try {
                                activeScreenFocusRequester.requestFocus()
                            } catch (_: Exception) {}
                        }
                    },
                    isSelected = isSelected,
                    cornerRadius = 14.dp,
                    focusedScale = 1.05f,
                    modifier = Modifier
                        .padding(vertical = 7.dp)
                        .size(50.dp)
                        .then(if (isSelected) Modifier.focusRequester(navMenuFocusRequester) else Modifier)
                        .onFocusChanged { isItemFocused = it.isFocused }
                        .focusProperties {
                            left = FocusRequester.Cancel
                            right = activeScreenFocusRequester
                            if (idx == 0) up = FocusRequester.Cancel
                            if (idx == NavItem.entries.lastIndex) down = FocusRequester.Cancel
                        }
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            tint = when {
                                isSelected -> AccentCyanBright
                                isItemFocused -> TextPrimary
                                else -> TextSecondary
                            },
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }

        // Subtle glass rail edge divider
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(Color.White.copy(alpha = 0.07f))
        )
    }
}
