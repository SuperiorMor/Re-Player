package com.replayer.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.replayer.data.FavoritesManager
import com.replayer.data.MediaWatchProgress
import com.replayer.data.RecentMediaManager
import com.replayer.data.ServerConfigManager
import com.replayer.diagnostics.CrashReporter
import com.replayer.model.FavoriteFolder
import com.replayer.model.MediaItem
import com.replayer.model.MediaSourceType
import com.replayer.model.MediaType
import com.replayer.model.SortMode
import com.replayer.network.upnp.ContentDirectoryService
import com.replayer.ui.components.TvFocusCard
import com.replayer.ui.theme.AccentCyan
import com.replayer.ui.theme.AccentCyanBright
import com.replayer.ui.theme.AccentGold
import com.replayer.ui.theme.AccentOrange
import com.replayer.ui.theme.BorderSubtle
import com.replayer.ui.theme.CardBackground
import com.replayer.ui.theme.DarkCard
import com.replayer.ui.theme.DarkSurface
import com.replayer.ui.theme.FocusBorder
import com.replayer.ui.theme.TextPrimary
import com.replayer.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun UpnpBrowseScreen(
    firstItemFocusRequester: FocusRequester,
    navMenuFocusRequester: FocusRequester,
    onPlayMedia: (MediaItem, List<MediaItem>) -> Unit,
    onNavigateToSettings: () -> Unit,
    onBackToHome: () -> Unit,
    modifier: Modifier = Modifier,
    initialFolder: Pair<String, String>? = null,
    lastPlayedMediaId: String? = null,
    isPlayerActive: Boolean = false,
    onFolderLoaded: () -> Unit = {},
    onConsumeLastPlayedMediaId: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val serverConfigManager = remember { ServerConfigManager(context) }
    val favoritesManager = remember { FavoritesManager(context) }
    val recentManager = remember { RecentMediaManager.getInstance(context) }
    val contentDirectory = remember { ContentDirectoryService() }

    val connectedDevice by serverConfigManager.connectedUpnpDevice.collectAsState()
    val favorites by favoritesManager.favorites.collectAsState()
    val progressMap by recentManager.progressMap.collectAsState()

    var currentItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var loadedFolderId by remember { mutableStateOf<String?>(null) }
    var folderStack by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var isLoadingFolder by remember { mutableStateOf(false) }
    var activeLoadRequestId by remember { mutableIntStateOf(0) }
    var sortMode by remember { mutableStateOf(SortMode.BY_NAME) }

    val listState = rememberLazyListState()
    var focusedItem by remember { mutableStateOf<MediaItem?>(null) }
    var lastExitTimestamp by remember { mutableLongStateOf(0L) }
    var handledBackKeyDown by remember { mutableStateOf(false) }
    var lastBackHandledMs by remember { mutableLongStateOf(0L) }
    val folderCache = remember { mutableMapOf<String, List<MediaItem>>() }

    val currentFolderId = folderStack.lastOrNull()?.first

    fun loadFolder(controlUrl: String, folderId: String, showOverlay: Boolean = true) {
        val reqId = ++activeLoadRequestId
        if (showOverlay) {
            isLoadingFolder = true
        }
        scope.launch {
            val loaded = contentDirectory.browse(controlUrl, folderId).distinctBy { it.id }
            if (reqId == activeLoadRequestId) {
                folderCache[folderId] = loaded
                currentItems = loaded
                loadedFolderId = folderId
                isLoadingFolder = false
            }
        }
    }

    fun openFolder(controlUrl: String, folderItem: MediaItem) {
        CrashReporter.breadcrumb("DLNA", "openFolder: ${folderItem.title} (${folderItem.id})")
        recentManager.recordFolderSelection(currentFolderId, folderItem.id, folderItem.uri)
        onConsumeLastPlayedMediaId()
        folderStack = folderStack + Pair(folderItem.id, folderItem.title)
        loadFolder(controlUrl, folderItem.id)
    }

    LaunchedEffect(isPlayerActive) {
        if (!isPlayerActive) {
            lastExitTimestamp = System.currentTimeMillis()
        }
    }

    // Initialize or switch folder when connectedDevice or initialFolder changes
    LaunchedEffect(connectedDevice, initialFolder) {
        if (connectedDevice != null) {
            val controlUrl = connectedDevice!!.contentDirectoryControlUrl ?: return@LaunchedEffect
            if (initialFolder != null) {
                if (folderStack.lastOrNull()?.first != initialFolder.first) {
                    folderStack = listOf(Pair("0", connectedDevice!!.friendlyName), initialFolder)
                    loadFolder(controlUrl, initialFolder.first)
                    onFolderLoaded()
                }
            } else if (folderStack.isEmpty()) {
                folderStack = listOf(Pair("0", connectedDevice!!.friendlyName))
                loadFolder(controlUrl, "0")
            }
        }
    }

    fun navigateBack() {
        val now = System.currentTimeMillis()
        if (now - lastExitTimestamp < 350 || now - lastBackHandledMs < 220) {
            return
        }
        lastBackHandledMs = now
        onConsumeLastPlayedMediaId()
        if (folderStack.size > 1) {
            val parentIdx = folderStack.size - 2
            val parentFolder = folderStack[parentIdx]
            folderStack = folderStack.take(parentIdx + 1)
            val cached = folderCache[parentFolder.first]
            if (cached != null) {
                activeLoadRequestId++
                currentItems = cached
                loadedFolderId = parentFolder.first
                isLoadingFolder = false
            } else {
                connectedDevice?.contentDirectoryControlUrl?.let { url ->
                    loadFolder(url, parentFolder.first)
                }
            }
        } else {
            onBackToHome()
        }
    }

    BackHandler(enabled = !isPlayerActive) {
        navigateBack()
    }

    fun handleBackPreviewKeyEvent(keyEvent: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (isPlayerActive) return false
        if (keyEvent.key == Key.Back || keyEvent.key == Key.Escape) {
            return when (keyEvent.type) {
                KeyEventType.KeyDown -> {
                    handledBackKeyDown = true
                    navigateBack()
                    true
                }
                KeyEventType.KeyUp -> {
                    if (handledBackKeyDown) {
                        handledBackKeyDown = false
                        true
                    } else false
                }
                else -> false
            }
        }
        return false
    }

    val sortedItems = remember(currentItems, sortMode) {
        when (sortMode) {
            SortMode.BY_NAME -> {
                currentItems.sortedWith(
                    compareBy<MediaItem> { it.type != MediaType.DIRECTORY }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                )
            }
            SortMode.BY_DATE -> {
                currentItems.sortedWith(
                    compareBy<MediaItem> { it.type != MediaType.DIRECTORY }
                        .thenByDescending { it.dateAdded ?: "" }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                )
            }
        }
    }

    val latestSortedItems by rememberUpdatedState(sortedItems)

    if (connectedDevice == null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .onPreviewKeyEvent { handleBackPreviewKeyEvent(it) }
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.FolderOpen,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Медиа-сервер UPnP / DLNA не подключён",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Выберите или подключите DLNA сервер в настройках",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(24.dp))
                TvFocusCard(
                    onClick = onNavigateToSettings,
                    modifier = Modifier
                        .width(240.dp)
                        .height(52.dp)
                        .focusRequester(firstItemFocusRequester)
                        .focusProperties { left = navMenuFocusRequester }
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Settings, contentDescription = null, tint = AccentCyanBright, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "Перейти в настройки", color = AccentCyanBright, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        return
    }

    val isAtRoot = folderStack.size <= 1
    val headerTitle = if (isAtRoot) connectedDevice!!.friendlyName else folderStack.last().second
    val parentBreadcrumb = if (isAtRoot) "" else folderStack.dropLast(1).joinToString(" › ") { it.second }

    Column(
        modifier = modifier
            .fillMaxSize()
            .onPreviewKeyEvent { handleBackPreviewKeyEvent(it) }
            .padding(start = 32.dp, end = 32.dp, top = 38.dp, bottom = 18.dp)
    ) {
        // Header: Aligned vertically with the visible left Logo (y ≈ 44..71dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = headerTitle,
                    style = MaterialTheme.typography.displayLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (parentBreadcrumb.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = parentBreadcrumb,
                        style = MaterialTheme.typography.bodySmall,
                        color = AccentCyanBright,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            TvFocusCard(
                onClick = {
                    sortMode = if (sortMode == SortMode.BY_NAME) SortMode.BY_DATE else SortMode.BY_NAME
                },
                modifier = Modifier
                    .width(180.dp)
                    .height(42.dp)
                    .focusProperties {
                        left = navMenuFocusRequester
                    }
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.SwapVert,
                        contentDescription = null,
                        tint = AccentCyanBright,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (sortMode == SortMode.BY_NAME) "Сорт: По имени" else "Сорт: По дате",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Box(modifier = Modifier.fillMaxSize()) {
            if (sortedItems.isEmpty() && !isLoadingFolder) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(firstItemFocusRequester)
                        .focusProperties { left = navMenuFocusRequester }
                        .focusable(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Папка пуста",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary
                    )
                }
            } else {
                // Two-column TV browser layout:
                // Left column (56%) — compact IPTV-style list of folders & files
                // Right column (44%) — vertical movie/series poster + bottom metadata panel
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp, start = 14.dp, end = 14.dp),
                        modifier = Modifier
                            .weight(0.56f)
                            .fillMaxHeight()
                            .focusRequester(firstItemFocusRequester)
                            .focusRestorer()
                            .focusProperties {
                                left = navMenuFocusRequester
                            }
                    ) {
                        items(sortedItems, key = { item -> item.id }) { item ->
                            val isFav = favorites.any { it.id == item.id }
                            val watchProgress = remember(item.id, item.uri, progressMap) {
                                if (item.type != MediaType.DIRECTORY) {
                                    (progressMap[item.id]?.progress()
                                        ?: progressMap[item.uri]?.progress()
                                        ?: 0f).coerceIn(0f, 1f)
                                } else 0f
                            }

                            var isRowFocused by remember { mutableStateOf(false) }
                            val starInteraction = remember { MutableInteractionSource() }
                            val isStarFocused by starInteraction.collectIsFocusedAsState()
                            val isAnyPartFocused = isRowFocused || isStarFocused

                            TvFocusCard(
                                onClick = {
                                    if (item.type == MediaType.DIRECTORY) {
                                        connectedDevice?.contentDirectoryControlUrl?.let { url ->
                                            openFolder(url, item)
                                        }
                                    } else {
                                        recentManager.recordFolderSelection(currentFolderId, item.id, item.uri)
                                        val folderTitle = folderStack.lastOrNull()?.second
                                        if (item.type == MediaType.VIDEO || item.type == MediaType.AUDIO) {
                                            recentManager.addRecent(
                                                id = item.id,
                                                title = item.title,
                                                uri = item.uri,
                                                parentId = item.parentId ?: currentFolderId,
                                                folderTitle = folderTitle
                                            )
                                            recentManager.resolveVerticalPoster(
                                                targetItem = item,
                                                folderItems = currentItems,
                                                controlUrl = connectedDevice?.contentDirectoryControlUrl,
                                                folderTitle = folderTitle
                                            )
                                        }
                                        val playables = sortedItems.filter {
                                            it.type == MediaType.VIDEO || it.type == MediaType.AUDIO
                                        }.ifEmpty { listOf(item) }
                                        onPlayMedia(item, playables)
                                    }
                                },
                                isSelected = isStarFocused,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .focusProperties {
                                        left = navMenuFocusRequester
                                    }
                                    .onFocusChanged { state ->
                                        isRowFocused = state.hasFocus
                                        if (state.hasFocus) {
                                            focusedItem = item
                                        }
                                    }
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    if (watchProgress > 0f) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxHeight()
                                                .fillMaxWidth(fraction = watchProgress)
                                                .background(
                                                    Brush.horizontalGradient(
                                                        colors = listOf(
                                                            AccentCyan.copy(alpha = 0.06f),
                                                            AccentOrange.copy(alpha = 0.16f)
                                                        )
                                                    )
                                                )
                                        )
                                    }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            val icon = mediaTypeIcon(item.type)
                                            val iconTint = mediaTypeTint(item.type)
                                            Box(
                                                modifier = Modifier
                                                    .size(32.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(DarkSurface.copy(alpha = 0.85f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = icon,
                                                    contentDescription = null,
                                                    tint = iconTint,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }

                                            Spacer(modifier = Modifier.width(10.dp))

                                            Column(
                                                modifier = Modifier.weight(1f),
                                                verticalArrangement = Arrangement.Center
                                            ) {
                                                Text(
                                                    text = item.title,
                                                    style = MaterialTheme.typography.bodyMedium.copy(
                                                        fontSize = 13.5.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        lineHeight = 16.sp
                                                    ),
                                                    color = TextPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.then(
                                                        if (isAnyPartFocused) {
                                                            Modifier.basicMarquee(
                                                                iterations = Int.MAX_VALUE,
                                                                initialDelayMillis = 250,
                                                                repeatDelayMillis = 800,
                                                                velocity = 40.dp
                                                            )
                                                        } else Modifier
                                                    )
                                                )
                                                val secondaryLine = buildListSecondaryText(item, watchProgress)
                                                Text(
                                                    text = secondaryLine,
                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                        fontSize = 11.sp,
                                                        lineHeight = 13.sp
                                                    ),
                                                    color = if (watchProgress > 0.01f) AccentGold else TextSecondary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }

                                        if (item.type == MediaType.DIRECTORY) {
                                            val starScale by animateFloatAsState(if (isStarFocused) 1.12f else 1.0f, label = "dlna_star_scale")
                                            val starBg by animateColorAsState(
                                                targetValue = when {
                                                    isStarFocused && isFav -> AccentGold.copy(alpha = 0.24f)
                                                    isStarFocused -> AccentCyan.copy(alpha = 0.22f)
                                                    isFav -> AccentGold.copy(alpha = 0.12f)
                                                    else -> Color.Transparent
                                                },
                                                label = "dlna_star_bg"
                                            )
                                            val starBorder = when {
                                                isStarFocused && isFav -> AccentGold
                                                isStarFocused -> FocusBorder
                                                isFav -> AccentGold.copy(alpha = 0.45f)
                                                else -> Color.Transparent
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .padding(start = 6.dp)
                                                    .size(30.dp)
                                                    .scale(starScale)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(starBg)
                                                    .then(
                                                        if (starBorder != Color.Transparent) {
                                                            Modifier.border(if (isStarFocused) 2.dp else 1.dp, starBorder, RoundedCornerShape(8.dp))
                                                        } else Modifier
                                                    )
                                                    .clickable(
                                                        interactionSource = starInteraction,
                                                        indication = null
                                                    ) {
                                                        favoritesManager.toggleFavorite(
                                                            FavoriteFolder(
                                                                id = item.id,
                                                                title = item.title,
                                                                sourceType = MediaSourceType.UPNP_DLNA,
                                                                serverControlUrl = connectedDevice?.contentDirectoryControlUrl,
                                                                parentId = item.parentId
                                                            )
                                                        )
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = if (isFav) Icons.Rounded.Star else Icons.Outlined.StarOutline,
                                                    contentDescription = "Избранное",
                                                    tint = when {
                                                        isFav -> AccentGold
                                                        isStarFocused -> AccentCyanBright
                                                        else -> TextSecondary
                                                    },
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Right column: Movie/Series Poster Preview + Full Metadata at the bottom
                    val displayItem = focusedItem ?: sortedItems.firstOrNull()
                    DlnaDetailPreviewPanel(
                        item = displayItem,
                        folderItems = currentItems,
                        folderTitle = folderStack.lastOrNull()?.second,
                        controlUrl = connectedDevice?.contentDirectoryControlUrl,
                        progressMap = progressMap,
                        recentManager = recentManager,
                        modifier = Modifier
                            .weight(0.44f)
                            .fillMaxHeight()
                            .padding(bottom = 16.dp)
                    )
                }
            }

            if (isLoadingFolder) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.48f)),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(DarkCard)
                            .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
                            .padding(horizontal = 24.dp, vertical = 16.dp)
                    ) {
                        CircularProgressIndicator(
                            color = AccentCyan,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = "Загрузка содержимого...",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DlnaDetailPreviewPanel(
    item: MediaItem?,
    folderItems: List<MediaItem>,
    folderTitle: String?,
    controlUrl: String?,
    progressMap: Map<String, MediaWatchProgress>,
    recentManager: RecentMediaManager,
    modifier: Modifier = Modifier
) {
    if (item == null) {
        Box(modifier = modifier)
        return
    }

    var posterUrl by remember(item.id) {
        mutableStateOf(recentManager.getCachedPreviewPoster(item.id))
    }
    var isResolvingPoster by remember(item.id) { mutableStateOf(false) }

    LaunchedEffect(item.id, item.uri, controlUrl) {
        if (recentManager.hasCachedPreviewPoster(item.id)) {
            posterUrl = recentManager.getCachedPreviewPoster(item.id)
            isResolvingPoster = false
            return@LaunchedEffect
        }
        posterUrl = null
        delay(160)
        isResolvingPoster = true
        val resolved = try {
            recentManager.resolvePreviewPosterForItem(
                item = item,
                folderItems = folderItems,
                controlUrl = controlUrl,
                folderTitle = folderTitle
            )
        } catch (_: Throwable) {
            null
        }
        posterUrl = resolved
        isResolvingPoster = false
    }

    val watchEntry = remember(item.id, item.uri, progressMap) {
        if (item.type != MediaType.DIRECTORY) {
            progressMap[item.id] ?: progressMap[item.uri]
        } else null
    }
    val watchProgress = watchEntry?.progress()?.coerceIn(0f, 1f) ?: 0f

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(DarkCard)
            .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Poster preview area matching vertical Home poster cards (2:3 ratio)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(2f / 3f, matchHeightConstraintsFirst = true)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(CardBackground, DarkSurface)
                        )
                    )
                    .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = posterUrl,
                    transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
                    label = "poster_fade"
                ) { url ->
                    if (!url.isNullOrBlank()) {
                        AsyncImage(
                            model = url,
                            contentDescription = item.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = mediaTypeIcon(item.type),
                                contentDescription = null,
                                tint = mediaTypeTint(item.type).copy(alpha = 0.55f),
                                modifier = Modifier.size(64.dp)
                            )
                        }
                    }
                }

                if (isResolvingPoster && posterUrl.isNullOrBlank()) {
                    CircularProgressIndicator(
                        color = AccentCyan,
                        strokeWidth = 2.dp,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(10.dp)
                            .size(18.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Bottom info section: Full title, format/size/resolution/date chips, watch bar
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    lineHeight = 20.sp
                ),
                color = TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            val metaParts = buildList {
                add(item.fileFormat())
                val sizeStr = item.formattedSize()
                if (sizeStr.isNotEmpty()) add(sizeStr)
                val resStr = item.resolution?.trim()
                if (!resStr.isNullOrEmpty() && !resStr.equals("null", ignoreCase = true)) {
                    add(resStr)
                }
                if (item.durationMs > 0L) {
                    add(formatShortDuration(item.durationMs))
                }
                val dateStr = item.formattedDate()
                if (dateStr.isNotEmpty()) add(dateStr)
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                metaParts.take(4).forEachIndexed { idx, label ->
                    val isFormatChip = idx == 0
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (isFormatChip) AccentCyan.copy(alpha = 0.16f) else DarkSurface
                            )
                            .border(
                                width = 1.dp,
                                color = if (isFormatChip) AccentCyan.copy(alpha = 0.35f) else BorderSubtle,
                                shape = RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = if (isFormatChip) AccentCyanBright else TextSecondary,
                            maxLines = 1
                        )
                    }
                }
            }

            if (metaParts.size > 4) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = metaParts.drop(4).joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (watchProgress > 0.005f && watchEntry != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Просмотрено: ${(watchProgress * 100).toInt().coerceIn(1, 100)}%",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = AccentGold
                    )
                    Text(
                        text = "${formatShortDuration(watchEntry.positionMs)} / ${formatShortDuration(watchEntry.durationMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
                Spacer(modifier = Modifier.height(5.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(DarkSurface)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction = watchProgress)
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(AccentGold, AccentOrange)
                                )
                            )
                    )
                }
            }
        }
    }
}

private fun mediaTypeIcon(type: MediaType): ImageVector = when (type) {
    MediaType.DIRECTORY -> Icons.Outlined.FolderOpen
    MediaType.VIDEO -> Icons.Outlined.VideoFile
    MediaType.AUDIO -> Icons.Outlined.AudioFile
    MediaType.IMAGE -> Icons.Outlined.Image
    MediaType.UNKNOWN -> Icons.Outlined.VideoFile
}

private fun mediaTypeTint(type: MediaType): Color = when (type) {
    MediaType.DIRECTORY -> AccentCyan
    MediaType.VIDEO -> AccentCyanBright
    MediaType.AUDIO -> AccentGold
    MediaType.IMAGE -> AccentGold
    MediaType.UNKNOWN -> AccentCyan
}

private fun buildListSecondaryText(item: MediaItem, watchProgress: Float): String {
    if (item.type == MediaType.DIRECTORY) return "Папка"
    val parts = buildList {
        add(item.fileFormat())
        val sizeStr = item.formattedSize()
        if (sizeStr.isNotEmpty()) add(sizeStr)
        if (watchProgress > 0.01f) {
            add("Просмотрено ${(watchProgress * 100).toInt().coerceIn(1, 100)}%")
        }
    }
    return parts.joinToString(" • ")
}

private fun formatShortDuration(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val seconds = totalSeconds % 60L
    val minutes = (totalSeconds / 60L) % 60L
    val hours = totalSeconds / 3600L
    return if (hours > 0L) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
