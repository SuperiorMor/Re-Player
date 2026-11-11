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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.replayer.ui.theme.AccentCyanBright
import com.replayer.ui.theme.SurfaceDark
import com.replayer.ui.theme.TextSecondary
import kotlinx.coroutines.launch

enum class NavItem(val label: String, val icon: ImageVector) {
    HOME("Главная", Icons.Outlined.Home),
    UPNP("UPnP / DLNA", Icons.Outlined.FolderOpen),
    IPTV("ТВ каналы", Icons.Outlined.LiveTv),
    SETTINGS("Настройки", Icons.Outlined.Settings)
}

@Composable
fun TvNavigationRail(
    selectedItem: NavItem,
    onItemSelected: (NavItem) -> Unit,
    onEnterSection: (NavItem) -> Unit = onItemSelected,
    itemFocusRequesters: Map<NavItem, FocusRequester> = emptyMap(),
    shouldSnapToSelectedOnFocus: Boolean = false,
    onSnappedToSelected: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val localRequesters = remember { NavItem.entries.associateWith { FocusRequester() } }
    val resolvedRequesters = remember(itemFocusRequesters) {
        NavItem.entries.associateWith { item -> itemFocusRequesters[item] ?: localRequesters.getValue(item) }
    }
    var focusedItem by remember { mutableStateOf<NavItem?>(null) }
    val latestSelected by rememberUpdatedState(selectedItem)
    val latestShouldSnap by rememberUpdatedState(shouldSnapToSelectedOnFocus)
    val latestOnSnapped by rememberUpdatedState(onSnappedToSelected)

    Row(
        modifier = modifier
            .fillMaxHeight()
            .width(92.dp)
            .background(SurfaceDark.copy(alpha = 0.72f))
            .onFocusChanged { state ->
                if (!state.hasFocus) {
                    focusedItem = null
                } else if (latestShouldSnap) {
                    latestOnSnapped()
                    if (focusedItem != latestSelected) {
                        scope.launch {
                            try {
                                resolvedRequesters[latestSelected]?.requestFocus()
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
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

            NavItem.entries.forEach { item ->
                val isSelected = item == selectedItem
                TvFocusCard(
                    onClick = {
                        onItemSelected(item)
                        onEnterSection(item)
                    },
                    isSelected = isSelected,
                    cornerRadius = 14.dp,
                    modifier = Modifier
                        .padding(vertical = 7.dp)
                        .size(50.dp)
                        .focusRequester(resolvedRequesters.getValue(item))
                        .onFocusChanged { state ->
                            if (state.isFocused) {
                                focusedItem = item
                            }
                        }
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.DirectionRight) {
                                if (item == selectedItem) {
                                    onEnterSection(item)
                                }
                                // Absorb Right on non-selected items so cursor never leaks or vanishes
                                true
                            } else {
                                false
                            }
                        }
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            tint = if (isSelected) AccentCyanBright else TextSecondary,
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
