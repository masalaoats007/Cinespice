@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package app.cinespice.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay
import java.util.Locale

internal data class CinePalette(val name: String, val bg: Color, val surface: Color, val accent: Color, val dark: Boolean)
internal val palettes = listOf(
    CinePalette("Ember", Color(0xFF101014), Color(0xFF202026), Color(0xFFFF774A), true),
    CinePalette("Midnight", Color(0xFF0D1424), Color(0xFF1C2940), Color(0xFF91ADFF), true),
    CinePalette("Pearl", Color(0xFFF8F4EF), Color(0xFFE9E2DA), Color(0xFFA63D25), false),
    CinePalette("Sage", Color(0xFFF1F6F2), Color(0xFFDFEAE2), Color(0xFF226347), false)
)
internal data class Destination(val id: String, val title: String, val icon: ImageVector)
internal val destinations = listOf(Destination("home", "Home", Icons.Default.Home), Destination("search", "Search", Icons.Default.Search), Destination("downloads", "Downloads", CineIcons.Download))

@Composable internal fun Cinespice(activity: MainActivity, model: LibraryModel) {
    val palette = palettes.firstOrNull { it.name == model.palette } ?: palettes.first()
    val text = if (palette.dark) Color.White.copy(alpha = .96f) else Color(0xFF171717)
    val muted = if (palette.dark) Color.White.copy(alpha = .69f) else Color(0xFF5C5C5C)
    val scheme = (if (palette.dark) darkColorScheme() else lightColorScheme()).copy(
        primary = palette.accent, onPrimary = if (palette.dark) Color(0xFF17100D) else Color.White,
        background = palette.bg, onBackground = text, surface = palette.surface, onSurface = text,
        surfaceVariant = palette.surface, onSurfaceVariant = muted, outline = muted.copy(alpha = .3f),
        primaryContainer = palette.accent.copy(alpha = .15f), onPrimaryContainer = text,
        secondaryContainer = palette.accent.copy(alpha = .15f), onSecondaryContainer = palette.accent,
        surfaceContainer = palette.surface, surfaceContainerHigh = palette.surface, surfaceContainerHighest = palette.surface,
        surfaceTint = Color.Transparent
    )
    val inputAreas = remember { mutableMapOf<Any, androidx.compose.ui.geometry.Rect>() }
    CompositionLocalProvider(LocalInputAreas provides inputAreas) {
    MaterialTheme(colorScheme = scheme) {
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = LocalFocusManager.current
        val view = LocalView.current
        val density = LocalDensity.current
        val ime = WindowInsets.ime.getBottom(density) > 0
        SideEffect { WindowCompat.getInsetsController(activity.window, view).apply { isAppearanceLightStatusBars = !palette.dark; isAppearanceLightNavigationBars = !palette.dark } }
        LaunchedEffect(model.account) { while (true) { delay(1000); model.tick() } }
        fun navigate(destination: String) { keyboard?.hide(); focus.clearFocus(); model.navigate(destination) }
        BackHandler(model.channelsVisible || model.page != "home") {
            if (ime) { keyboard?.hide(); focus.clearFocus() }
            else if (model.channelsVisible) model.closeChannels() else navigate("home")
        }
        BoxWithConstraints(Modifier.fillMaxSize().background(palette.bg).outsideKeyboard()) {
            val rail = maxWidth >= 840.dp && model.stage == "ready" && model.page != "collection"
            Scaffold(containerColor = palette.bg, contentWindowInsets = WindowInsets.safeDrawing,
                topBar = {
                    Column(Modifier.statusBarsPadding().background(palette.surface)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (model.page == "collection" && model.stage == "ready") IconButton(onClick = { navigate("home") }) { Icon(Icons.Default.ArrowBack, "Back") }
                            if (model.page == "collection" && model.stage == "ready") {
                                Text(model.chatTitle, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                                SortControl(model, compact = true)
                                IconButton(onClick = { model.collectionSearchVisible = !model.collectionSearchVisible; if (!model.collectionSearchVisible) model.searchCollection("") }) { Icon(Icons.Default.Search, "Search this channel") }
                            } else {
                            TextButton(onClick = { if (model.stage == "ready") model.refresh() }, enabled = !model.busy) { Text("CINESPICE", letterSpacing = 2.sp, fontWeight = FontWeight.Bold, fontSize = 17.sp) }
                            Spacer(Modifier.weight(1f))
                            if (model.stage == "ready") {
                                IconButton(onClick = { navigate("favorites") }) { Icon(Icons.Default.FavoriteBorder, "Favorites and Watchlist") }
                                IconButton(onClick = { keyboard?.hide(); model.showChannels() }) { Icon(CineIcons.VideoLibrary, "Channels") }
                                IconButton(onClick = { navigate("profile") }) { Icon(CineIcons.PersonOutline, "Profile") }
                            }
                        }
                        }
                        if (model.busy || model.searching) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                        if (model.status.isNotBlank()) Text(model.status, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .3f))
                    }
                }, bottomBar = {
                    if (model.stage == "ready" && model.page != "collection" && !rail && !ime) NavigationBar(modifier = Modifier.heightIn(min = if (LocalDensity.current.fontScale > 1.4f) 112.dp else 80.dp), containerColor = palette.surface, tonalElevation = 0.dp) {
                        destinations.forEach { dest -> NavigationBarItem(selected = model.page == dest.id, onClick = { navigate(dest.id) }, icon = { Icon(dest.icon, null) }, label = { Text(dest.title, maxLines = if (LocalDensity.current.fontScale > 1.4f) 2 else 1, fontSize = 9.sp, softWrap = false, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }, modifier = Modifier.semantics { contentDescription = "${dest.title} tab" }) }
                    }
                }
            ) { padding ->
                // Scaffold already applies the safe-drawing insets. A second IME
                // inset here double-counted the keyboard when edge-to-edge was on.
                Row(Modifier.fillMaxSize().padding(padding)) {
                    if (rail) NavigationRail(containerColor = palette.surface) { destinations.forEach { dest -> NavigationRailItem(modifier = Modifier.semantics { contentDescription = "${dest.title} tab" }, selected = model.page == dest.id, onClick = { navigate(dest.id) }, icon = { Icon(dest.icon, dest.title) }, label = { Text(dest.title) }) } }
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                        if (model.stage == "ready") LibraryPage(activity, model, Modifier.widthIn(max = 1200.dp).fillMaxSize()) else Authentication(activity, model)
                    }
                }
            }
        }
        if (model.channelsVisible && model.stage == "ready") ChannelPicker(model)
        if (model.stage == "ready") activity.details?.let { FileDetails(activity, model, it) }
        LaunchedEffect(model.account) { activity.details = null; activity.downloadSuggestion = null }
        activity.downloadSuggestion?.let { video -> AlertDialog(onDismissRequest = { activity.downloadSuggestion = null }, title = { Text("Download to play") }, text = { Text("Please download this file before playing it. Its size is unavailable for streaming.") }, confirmButton = { TextButton(onClick = { model.download(video); activity.downloadSuggestion = null }) { Text("Download") } }, dismissButton = { TextButton(onClick = { activity.downloadSuggestion = null }) { Text("Cancel") } }) }
        activity.opening?.let { video -> AlertDialog(onDismissRequest = activity::cancelOpening, title = { Text("Opening video") }, text = { Column { Text(video.name); LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 16.dp)) } }, confirmButton = { TextButton(onClick = activity::cancelOpening) { Text("Cancel") } }) }
    }
}

}

/** Observe taps without consuming them, so the same tap can activate a button. */
@Composable private fun Modifier.outsideKeyboard(): Modifier {
    val keyboard = LocalSoftwareKeyboardController.current
    val areas = LocalInputAreas.current
    val focus = LocalFocusManager.current
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    return onGloballyPositioned { coordinates = it }.pointerInput(keyboard) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                if (event.type == PointerEventType.Press) {
                    // Observe without consuming, including taps already handled by a button.
                    if (event.changes.none { change -> areas.values.any { it.contains(coordinates?.localToRoot(change.position) ?: change.position) } }) { keyboard?.hide(); focus.clearFocus() }
                }
            }
        }
    }
}

@Composable private fun LibraryPage(activity: MainActivity, model: LibraryModel, modifier: Modifier) {
    if (model.page == "profile") { Profile(activity, model, modifier); return }
    key(model.account, model.page, model.chat, model.savedList) {
        val grid = rememberLazyGridState()
        val videos = when (model.page) {
            "collection" -> model.sortedChannel((model.videos.filter { it.chatId == model.chat && (model.collectionQuery.isBlank() || it.name.contains(model.collectionQuery, true)) } + model.collectionSearchResults).distinctBy { it.key() }, model.chat)
            "favorites" -> model.sorted(model.videos.filter { SavedStore.id(it) in if (model.savedList == "Favorites") model.favorites else model.watchlist })
            "search" -> if (model.query.isBlank()) emptyList() else model.sorted(model.searchResults)
            "downloads" -> model.sorted(model.videos.filter { model.downloadStates[SavedStore.id(it)] !in listOf(null, "Cancelled") })
            "history" -> model.history
            else -> emptyList()
        }
        val pageCount = maxOf(1, (videos.size + 29) / 30)
        val currentPage = model.collectionPageNumber.coerceIn(0, pageCount - 1)
        val shown = if (model.page == "collection") videos.drop(currentPage * 30).take(30) else videos
        LaunchedEffect(currentPage, model.collectionQuery, model.channelOrder(model.chat)) { if (model.page == "collection") grid.scrollToItem(0) }
        LazyVerticalGrid(columns = GridCells.Adaptive(140.dp), state = grid, modifier = modifier.testTagCompat("library-grid"), contentPadding = PaddingValues(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (model.page == "home") {
                item(span = { GridItemSpan(maxLineSpan) }) { Hero(activity, model) }
                if (model.continuing.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionTitle("Continue watching")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) { items(model.continuing, key = { it.key() }) { video -> ContinueCard(activity, model, video) } }
                    }
                }
                val selectedChannels = model.channels.filter { it.selected() }
                if (selectedChannels.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { EmptyState("Choose your channels", "Select Telegram channels and groups with the Channels button.") }
                items(selectedChannels, key = { "channel_${it.id()}" }, span = { GridItemSpan(maxLineSpan) }) { source ->
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth().clickable { model.browse(source) }, verticalAlignment = Alignment.CenterVertically) {
                            Text(source.title(), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { model.browse(source) }) { Icon(CineIcons.ChevronRight, "Open ${source.title()}") }
                        }
                        val preview = model.sortedChannel(model.videos.filter { it.chatId == source.id() }, source.id()).take(10)
                        if (preview.isEmpty()) Text("No files loaded yet. Open this channel to load or retry.", style = MaterialTheme.typography.bodySmall)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(preview, key = { it.key() }) { video -> Box(Modifier.width(160.dp)) { Poster(activity, model, video) } }
                        }
                    }
                }
            }
            if (model.page != "home") item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (model.page != "collection") Text(when(model.page) { "favorites" -> "Saved for later"; "search" -> "Find your next watch"; "downloads" -> "Downloads"; "history" -> "Recently watched"; else -> model.chatTitle }, style = MaterialTheme.typography.headlineMedium, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    if (model.page == "favorites") SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) { listOf("Favorites", "Watchlist").forEachIndexed { index, list -> SegmentedButton(selected = model.savedList == list, onClick = { model.setList(list) }, shape = SegmentedButtonDefaults.itemShape(index, 2)) { Text(list) } } }
                    if (model.page == "search") {
                        OutlinedTextField(model.query, model::search, Modifier.fillMaxWidth().inputArea(), label = { Text("Search videos") }, singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = { if (model.query.isNotEmpty()) IconButton(onClick = { model.search("") }) { Icon(Icons.Default.Close, "Clear search") } else Spacer(Modifier.size(48.dp)) }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { model.search(model.query) }))
                    }
                    if (model.page == "collection" && model.collectionSearchVisible) OutlinedTextField(model.collectionQuery, model::searchCollection, Modifier.fillMaxWidth().inputArea(), label = { Text("Search this channel") }, singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = { if (model.collectionQuery.isNotEmpty()) IconButton(onClick = { model.searchCollection("") }) { Icon(Icons.Default.Close, "Clear channel search") } else Spacer(Modifier.size(48.dp)) }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { model.searchCollection(model.collectionQuery) }))
                    if (model.page == "history") Text("Your five most recent watches. Older resume positions stay saved.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    else if (model.page == "downloads") Text("Full downloads only. Keep the app open until they finish; interrupted downloads can be retried.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    if (model.page !in listOf("search", "history") || model.query.isNotBlank()) if (model.page !in listOf("history", "collection")) SortControl(model)
                }
            }
            if (videos.isEmpty() && model.page != "home") item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(when (model.page) { "search" -> if (model.query.isBlank()) "Search your selected channels" else if (model.searching) "Searching…" else "No matching videos"; "collection" -> if (model.collectionQuery.isBlank()) "No videos loaded yet" else if (model.collectionSearching) "Searching this channel…" else "No matching videos"; "favorites" -> "A little inspiration for later"; "downloads" -> "Nothing downloaded yet"; "history" -> "Your next film starts here"; else -> "Your cinema starts here" }, when (model.page) { "search" -> "Enter a title or filename. Only channels you select are searched."; "collection" -> if (model.collectionQuery.isBlank()) "Load this channel's videos to start browsing." else "Search is limited to this channel."; "favorites" -> "Long press a file to add it to ${model.savedList}."; "downloads" -> "Choose Download from a video's menu."; "history" -> "Videos you watch will appear here."; else -> "Select Telegram channels using the Channels button." })
            }
            items(shown, key = { it.key() }) { video -> Poster(activity, model, video) }
            if (model.page == "search" && model.query.isNotBlank()) item(span = { GridItemSpan(maxLineSpan) }) { OutlinedButton(onClick = model::moreSearch, enabled = !model.searching && model.searchMore, modifier = Modifier.fillMaxWidth()) { Text(if (model.searching) "Searching…" else "More results / Retry") } }
            if (model.page == "collection" && model.collectionQuery.isNotBlank() && model.collectionSearchError.isNotBlank()) item(span = { GridItemSpan(maxLineSpan) }) { Text(model.collectionSearchError, color = MaterialTheme.colorScheme.error) }
            if (model.page == "collection") item(span = { GridItemSpan(maxLineSpan) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { model.collectionPageNumber = currentPage - 1 }, enabled = currentPage > 0) { Icon(Icons.Default.ArrowBack, "Previous page") }
                    LazyRow(Modifier.weight(1f)) { items(pageCount) { page ->
                        TextButton(onClick = { model.collectionPageNumber = page }, colors = ButtonDefaults.textButtonColors(contentColor = if (page == currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)) { Text("${page + 1}", fontWeight = if (page == currentPage) FontWeight.Bold else FontWeight.Normal) }
                    } }
                    IconButton(onClick = { model.collectionPageNumber = currentPage + 1 }, enabled = currentPage + 1 < pageCount) { Icon(CineIcons.ChevronRight, "Next page") }
                }
            }
            if (model.page == "collection") item(span = { GridItemSpan(maxLineSpan) }) {
                Text("${videos.size} loaded files · 30 per page. Sorting applies to loaded files.", style = MaterialTheme.typography.bodySmall)
            }
            if (model.page == "collection") item(span = { GridItemSpan(maxLineSpan) }) {
                if (model.collectionQuery.isNotBlank()) OutlinedButton(onClick = model::moreCollectionSearch, enabled = !model.collectionSearching && model.collectionSearchMore, modifier = Modifier.fillMaxWidth()) { Text(if (model.collectionSearching) "Searching…" else if (model.collectionSearchError.isNotBlank()) "Retry channel search" else "More channel results") }
                else OutlinedButton(onClick = model::older, enabled = !model.busy && model.collectionMore, modifier = Modifier.fillMaxWidth()) { Text(if (model.collectionMore) "Load older videos / Retry" else "All available pages loaded") }
            }
        }
    }
}

@Composable private fun Modifier.testTagCompat(tag: String) = this.semantics { testTag = tag }
@Composable private fun SectionTitle(text: String) { Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
@Composable private fun EmptyState(title: String, detail: String) {
    Surface(shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .4f))) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Icon(CineIcons.Movie, null, tint = MaterialTheme.colorScheme.primary); Text(title, style = MaterialTheme.typography.titleLarge); Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
    }
}
@Composable private fun SortControl(model: LibraryModel, compact: Boolean = false) {
    var expanded by remember { mutableStateOf(false) }
    val selectedSort = if (model.page == "collection") model.channelOrder(model.chat) else model.sort
    Column {
        Box { TextButton(onClick = { expanded = true }, contentPadding = PaddingValues(horizontal = 0.dp)) { Icon(CineIcons.Sort, "Sort channel", Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); if (!compact) Text(VideoSort.LABELS[selectedSort]) }; DropdownMenu(expanded, { expanded = false }) { VideoSort.LABELS.forEachIndexed { index, label -> DropdownMenuItem(text = { Text(if (index == selectedSort) "✓ $label" else label) }, onClick = { model.order(index); expanded = false }) } } }
        if (!compact && selectedSort < 2) Text("Filename years · loaded results only", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    }
}
@Composable private fun Hero(activity: MainActivity, model: LibraryModel) {
    val pager = rememberPagerState { 7 }
    val latest = model.videos.maxByOrNull { it.data.optLong("date") }
    val daily = VideoSort.daily(model.videos, model.day)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalPager(pager, pageSpacing = 12.dp, modifier = Modifier.fillMaxWidth()) { slot ->
            val video = when (slot) { 0 -> latest; 1 -> daily; 6 -> model.lastSearch; else -> null }
            val label = listOf("JUST ADDED", "TODAY'S PICK", "TRENDING FILMS", "POPULAR THIS WEEK", "DISCOVER SOMETHING NEW", "FROM THE CATALOGUE", "YOUR LAST SEARCH")[slot]
            Box(Modifier.fillMaxWidth().heightIn(min = 300.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF202026)).semantics { contentDescription = "Hero ${slot+1} of 7: $label" }) {
                if (video != null) ArtworkImage(model, video, Modifier.matchParentSize())
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color(0x80000000), Color(0xF0101014)))))
                Column(Modifier.padding(24.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(label, color = Color.White.copy(alpha = .8f), fontSize = 11.sp, letterSpacing = 2.sp)
                    Spacer(Modifier.height(32.dp))
                    Text(video?.name?.substringBeforeLast('.') ?: if (slot in 2..5) "More stories,\ncoming into view." else if (slot == 6) "What will you find?" else "Your own cinema.", color = Color.White, fontSize = 24.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(if (video != null) videoDetails(video) else if (slot in 2..5) "Catalogue not connected" else if (slot == 6) "Complete a search to see a result here." else "Choose your channels to get started.", color = Color.White.copy(alpha = .75f), fontSize = 13.sp)
                    if (video != null) Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { activity.open(video) }) { Icon(Icons.Default.PlayArrow, null); Text(if (model.telegram.requireLibrary().position(video.key()) >= 5000) "Resume" else "Play") }
                        Spacer(Modifier.width(8.dp)); OutlinedButton(onClick = { activity.details = video }) { Icon(Icons.Default.Info, null); Text("More info", color = Color.White) }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { repeat(7) { index -> Box(Modifier.padding(horizontal = 3.dp).size(if (pager.currentPage == index) 8.dp else 6.dp).clip(RoundedCornerShape(8.dp)).background(if (pager.currentPage == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)) } }
    }
}
@Composable private fun ContinueCard(activity: MainActivity, model: LibraryModel, video: Video) {
    Column(Modifier.width(240.dp)) {
        Poster(activity, model, video, wide = true)
        Text("Resume at ${Ui.time(model.telegram.requireLibrary().position(video.key()))}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}
@Composable private fun Poster(activity: MainActivity, model: LibraryModel, video: Video, wide: Boolean = false) {
    var options by remember(video.key()) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().combinedClickable(onClick = { activity.details = video }, onLongClick = { options = true }), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(if (wide) 16f / 9f else .69f).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).combinedClickable(onClickLabel = "Play ${video.name}", onClick = { activity.open(video) }, onLongClick = { options = true })) {
            ArtworkImage(model, video, Modifier.matchParentSize())
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xBF000000)))))
            Text(video.name.substringBeforeLast('.').replace('.', ' '), Modifier.align(Alignment.BottomStart).padding(12.dp), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            val id = SavedStore.id(video)
            Row(Modifier.align(Alignment.TopStart).padding(8.dp)) {
                if (id in model.favorites) Icon(Icons.Default.Favorite, "In Favorites", tint = Color(0xFFFF774A), modifier = Modifier.size(20.dp))
                if (id in model.watchlist) Icon(CineIcons.Bookmark, "In Watchlist", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
        Row(verticalAlignment = Alignment.Top) {
            Text(video.name, Modifier.weight(1f).padding(top = 8.dp), fontSize = 14.sp, maxLines = 2, lineHeight = 19.sp, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)

        }
        Row {
            FilledTonalIconButton(onClick = { activity.open(video) }) { Icon(Icons.Default.PlayArrow, "Play ${video.name}") }
            IconButton(onClick = { activity.details = video }) { Icon(Icons.Default.Info, "Info for ${video.name}") }
        }
        if (options) FileActions(activity, model, video) { options = false }
        Text(videoDetails(video), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 2)
        model.downloadStates[SavedStore.id(video)]?.let { state -> Text(state, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }
    }
}
internal fun videoDetails(video: Video): String = listOfNotNull(VideoSort.year(video)?.let { "$it · filename year" }, if (video.duration > 0) Ui.time(video.duration * 1000L) else null, if (video.size > 0) String.format(Locale.ROOT, "%.0f MB", video.size / 1048576.0) else null).joinToString(" · ")
@Composable internal fun ArtworkImage(model: LibraryModel, video: Video, modifier: Modifier) {
    var bitmap by remember(model.account, video.key()) { mutableStateOf<Bitmap?>(model.artwork?.cached(video) ?: runCatching { val bytes = Base64.decode(video.data.optString("mini"), Base64.DEFAULT); BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()) }
    DisposableEffect(model.account, video.key()) {
        var active = true
        model.artwork?.load(video) { image -> if (active) bitmap = image }
        onDispose { active = false }
    }
    if (bitmap != null) Image(bitmap!!.asImageBitmap(), null, modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(Brush.linearGradient(listOf(Color(0xFF343843), Color(0xFF191A21)))), contentAlignment = Alignment.Center) { Icon(CineIcons.LocalMovies, null, tint = Color.White.copy(alpha = .18f), modifier = Modifier.size(50.dp)) }
}
@Composable private fun FileActions(activity: MainActivity, model: LibraryModel, video: Video, dismiss: () -> Unit) {
    val id = SavedStore.id(video)
    AlertDialog(onDismissRequest = dismiss, title = { Text(video.name, maxLines = 2) }, confirmButton = { TextButton(onClick = dismiss) { Text("Close") } }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            fun action(run: () -> Unit) { dismiss(); run() }
            DropdownMenuItem(text = { Text(if (id in model.favorites) "Remove from Favorites" else "Add to Favorites") }, onClick = { action { model.toggle(video, "Favorites") } })
            DropdownMenuItem(text = { Text(if (id in model.watchlist) "Remove from Watchlist" else "Add to Watchlist") }, onClick = { action { model.toggle(video, "Watchlist") } })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Play / Resume") }, onClick = { action { activity.open(video) } })
            DropdownMenuItem(text = { Text("Start over") }, onClick = { action { activity.open(video, false) } })
            DropdownMenuItem(text = { Text("Download") }, enabled = id !in model.preparing && model.downloadStates[id] != "Downloading", onClick = { action { model.download(video) } })
            DropdownMenuItem(text = { Text("Cancel download") }, enabled = model.downloadStates[id] == "Downloading", onClick = { action { model.cancelDownload(video) } })
            DropdownMenuItem(text = { Text("Export to Files…") }, onClick = { action { activity.export(video) } })
            DropdownMenuItem(text = { Text("Mark watched") }, onClick = { action { model.mark(video) } })
            DropdownMenuItem(text = { Text("Remove history") }, onClick = { action { model.removeHistory(video) } })
        }
    })
}

@Composable private fun ChannelPicker(model: LibraryModel) {
    ModalBottomSheet(modifier = Modifier.fillMaxHeight(.95f), sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), onDismissRequest = model::closeChannels, containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        val dialogView = LocalView.current
        val light = MaterialTheme.colorScheme.surface.luminance() > .5f
        fun applyBars() { (dialogView.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { window ->
            WindowCompat.getInsetsController(window, dialogView).apply { isAppearanceLightStatusBars = light; isAppearanceLightNavigationBars = light }
        } }
        SideEffect { applyBars() }
        DisposableEffect(dialogView, light) {
            val listener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { if (it) applyBars() }
            dialogView.viewTreeObserver.addOnWindowFocusChangeListener(listener)
            onDispose { if (dialogView.viewTreeObserver.isAlive) dialogView.viewTreeObserver.removeOnWindowFocusChangeListener(listener) }
        }
        Column(Modifier.fillMaxSize().outsideKeyboard().padding(horizontal = 20.dp)) {
            Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) { Text("Channels", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); TextButton(onClick = model::closeChannels) { Text("Done") } }
            Text("Select sources for your library. Use the arrow to browse an added source.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(model.channelQuery, model::channelFilter, Modifier.fillMaxWidth().padding(vertical = 10.dp).inputArea(), singleLine = true, label = { Text("Search channels and groups") }, leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = { if (model.channelQuery.isNotEmpty()) IconButton(onClick = { model.channelFilter("") }) { Icon(Icons.Default.Close, "Clear channel search") } else Spacer(Modifier.size(48.dp)) }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { model.searchChannels() }))
            if (model.channelSearchLoading) Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Text("  Searching Telegram…", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
            if (model.channelSearchError.isNotBlank()) Text(model.channelSearchError, Modifier.fillMaxWidth().padding(bottom = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (model.channelScanError.isNotBlank()) Text(model.channelScanError, Modifier.fillMaxWidth().padding(bottom = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (model.scanning) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            val sources = model.channels.filter { it.title().contains(model.channelQuery, ignoreCase = true) || it.id() in model.channelSearchMatches }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(sources, key = { it.id() }) { source ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Checkbox, onClickLabel = if (source.selected()) "Remove ${source.title()}" else "Add ${source.title()}") { model.select(source) }.padding(start = 4.dp, end = 0.dp, top = 4.dp, bottom = 4.dp).semantics { stateDescription = if (source.selected()) "Selected" else "Not selected" }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(source.selected(), onCheckedChange = null)
                        Text(source.title(), Modifier.weight(1f).padding(horizontal = 10.dp), fontSize = 15.sp)
                        if (source.selected()) IconButton(onClick = { model.browse(source) }) { Icon(CineIcons.ChevronRight, "Browse ${source.title()}") } else Spacer(Modifier.size(48.dp))
                    }
                }
                if (sources.isEmpty()) item { Text(if (model.channelSearchLoading) "Looking for matching channels and groups…" else if (model.scanning) "Finding channels and groups…" else if (model.channelQuery.isBlank()) "No channels found yet" else "No matching channels yet. Try another name or continue scanning.", Modifier.padding(vertical = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = model::scanSources, enabled = !model.scanning && model.discoveryMore) { Text(if (model.scanning) "Finding sources…" else "Find more / Retry") }
                        if (model.channelQuery.isNotBlank()) TextButton(onClick = model::searchChannels, enabled = !model.channelSearchLoading) { Text(if (model.channelSearchLoading) "Searching…" else "Search Telegram") }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
@Composable private fun Profile(activity: MainActivity, model: LibraryModel, modifier: Modifier) {
    var confirmation by remember { mutableStateOf<String?>(null) }
    var notices by remember { mutableStateOf(false) }
    Column(modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Your space", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer) { Icon(CineIcons.PersonOutline, null, Modifier.padding(16.dp).size(32.dp), tint = MaterialTheme.colorScheme.primary) }
            Column(Modifier.padding(start = 16.dp)) {
                Text(model.telegram.displayName.ifBlank { "Telegram account" }, style = MaterialTheme.typography.titleLarge)
                if (model.telegram.username.isNotBlank()) Text("@${model.telegram.username}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SectionTitle("Make it yours")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            palettes.chunked(2).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { row.forEach { palette ->
                OutlinedButton(onClick = { model.theme(palette.name) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp), border = BorderStroke(if (model.palette == palette.name) 2.dp else 1.dp, if (model.palette == palette.name) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)) {
                    Box(Modifier.size(16.dp).clip(RoundedCornerShape(8.dp)).background(palette.accent)); Spacer(Modifier.width(8.dp)); Text(palette.name, maxLines = 1)
                }
            } } }
        }
        HorizontalDivider()
        SectionTitle("Local storage")
        listOf("downloads" to "Clear downloaded media", "artwork" to "Clear artwork cache", "history" to "Clear watch history").forEach { (kind, label) -> OutlinedButton(onClick = { confirmation = kind }, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) { Text(label) } }
        Text("Cleanup leaves your Favorites, Watchlist and Telegram cloud messages intact.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("Request feature · unavailable") }
        Text("Created by Masalaots · @notmasalaoats", style = MaterialTheme.typography.bodyMedium)
        Row {
            TextButton(onClick = { activity.link("https://instagram.com/notmasalaoats") }) { Icon(CineIcons.CameraAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Instagram") }
            TextButton(onClick = { activity.link("https://t.me/notmasalaoats") }) { Icon(Icons.Default.Send, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Telegram") }
        }
        TextButton(onClick = { notices = true }) { Text("Open source notices") }
        Text("Cinespice 1.2.0 · Telegram + VLC", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { confirmation = "session" }, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) { Text("Sign out of Telegram") }
    }
    confirmation?.let { kind -> AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(if (kind == "session") "Sign out of Telegram?" else "Clear $kind?") }, text = { Text(if (kind == "session") "Your local library stays scoped to this account." else "This changes local device storage. Telegram cloud messages and saved-list membership are preserved.") }, confirmButton = { TextButton(onClick = { confirmation = null; model.clear(kind) }) { Text("Continue") } }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel") } }) }
    if (notices) {
        val text = remember { buildString {
            append("TDLib 1.8.65 · Boost Software License 1.0\nlibVLC 3.6.5 · LGPL 2.1 or later\nAndroidX, Compose, Room and Material Components · Apache 2.0\n\nNative sources are unmodified; builds strip debug symbols. Dependency sources and build instructions: android/THIRD_PARTY.md in Cinespice/Cinespice.\n\n")
            listOf("TDLib.txt", "TDLib-JNI.txt", "libVLC.txt", "Apache-2.0.txt").forEach { name -> append(activity.assets.open("licenses/$name").bufferedReader().use { it.readText() }); append("\n\n") }
        } }
        AlertDialog(onDismissRequest = { notices = false }, title = { Text("Open source notices") }, text = { Text(text, Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), fontSize = 12.sp) }, confirmButton = { TextButton(onClick = { notices = false }) { Text("Close") } })
    }
}
private data class DialingCountry(val id: String, val name: String, val dial: String)
@Composable private fun Authentication(activity: MainActivity, model: LibraryModel) {
    var apiId by rememberSaveable { mutableStateOf("") }
    var hash by remember { mutableStateOf("") }
    var value by remember(model.stage) { mutableStateOf("") }
    var countryId by rememberSaveable { mutableStateOf(Locale.getDefault().country.ifEmpty { "US" }) }
    var chooseCountry by remember { mutableStateOf(false) }
    var countryQuery by remember { mutableStateOf("") }
    val countries = remember { runCatching {
        val data = org.json.JSONArray(activity.assets.open("countries.json").bufferedReader().use { it.readText() })
        (0 until data.length()).map { val item = data.getJSONObject(it); DialingCountry(item.getString("id"), item.getString("name"), item.getString("dial")) }
    }.getOrDefault(listOf(DialingCountry("US", "United States", "+1"))) }
    val country = countries.firstOrNull { it.id == countryId } ?: countries.first()
    val keyboard = LocalSoftwareKeyboardController.current
    fun submit() {
        if (model.busy || value.isBlank()) return
        val input = if (model.stage == "phone") country.dial + value.filter { it.isDigit() } else if (model.stage == "password") value else value.trim()
        keyboard?.hide()
        model.work("Checking with Telegram…") { model.network { model.telegram.authenticate(input) } }
    }
    Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("YOUR CHANNELS. YOUR CINEMA.", fontSize = 11.sp, letterSpacing = 2.sp, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(20.dp))
        Text(when (model.stage) { "phone" -> "Your number.\nYour cinema."; "code", "emailCode" -> "Enter your login code"; "password" -> "Your cloud password"; "email" -> "Your sign-in email"; else -> "Welcome to Cinespice" }, fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold)
        if (model.stage == "credentials") {
            if (model.telegram.standardBuild) {
                OutlinedTextField(apiId, { apiId = it }, Modifier.fillMaxWidth().inputArea(), label = { Text("API ID") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(hash, { hash = it }, Modifier.fillMaxWidth().inputArea(), label = { Text("API hash") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                Button(onClick = { model.work("Connecting…") { model.network { model.telegram.connect(apiId.trim(), hash.trim()) } } }, enabled = !model.busy) { Text("Connect Telegram") }
            } else {
                Text("This edition connects using Cinespice's Telegram configuration.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { model.work("Connecting…") { model.network { model.telegram.restoreCredentials() } } }, enabled = !model.busy) { Text("Connect / Retry") }
            }
        } else if (model.stage in setOf("phone", "code", "password", "email", "emailCode")) {
            Text(when(model.stage) { "phone" -> "Choose your country and enter the number you use for Telegram."; "code" -> "Check Telegram or the delivery method selected for your account."; "password" -> "Two-step verification is enabled. Enter your Telegram password to finish signing in."; "email" -> "Telegram needs an email address for this login."; else -> "Check your email for the verification code." }, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (model.stage == "phone") OutlinedButton(onClick = { chooseCountry = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(14.dp)) {
                Text(country.name, Modifier.weight(1f)); Text(country.dial); Icon(Icons.Default.ArrowDropDown, "Choose country")
            }
            val label = when(model.stage) { "phone" -> "Phone number"; "code" -> "Telegram login code"; "password" -> "Cloud password"; "email" -> "Email address"; else -> "Email verification code" }
            OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth().inputArea(), label = { Text(label) }, singleLine = true,
                prefix = { if (model.stage == "phone") Text(country.dial + " ") },
                textStyle = if (model.stage in setOf("code", "emailCode")) MaterialTheme.typography.headlineMedium.copy(letterSpacing = 4.sp) else MaterialTheme.typography.bodyLarge,
                shape = RoundedCornerShape(14.dp), visualTransformation = if (model.stage == "password") PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = when(model.stage) { "phone" -> KeyboardType.Phone; "code", "emailCode" -> KeyboardType.Number; "email" -> KeyboardType.Email; "password" -> KeyboardType.Password; else -> KeyboardType.Text }, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { submit() }))
            Button(onClick = ::submit, enabled = !model.busy && value.isNotBlank() && (model.stage != "phone" || value.any { it.isDigit() }), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) { Text(if (model.busy) "Checking…" else when(model.stage) { "phone", "email" -> "Get code"; "password" -> "Log in"; else -> "Submit code" }) }
        } else { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(if (model.stage == "unsupported") model.telegram.error else "Connecting securely to Telegram…") }
        Text("Your Telegram session and library stay on this device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (chooseCountry) AlertDialog(onDismissRequest = { chooseCountry = false }, title = { Text("Choose country") }, confirmButton = { TextButton(onClick = { chooseCountry = false }) { Text("Back") } }, text = {
        Column {
            OutlinedTextField(countryQuery, { countryQuery = it }, Modifier.fillMaxWidth().inputArea(), label = { Text("Country or calling code") }, singleLine = true)
            TextButton(onClick = { activity.link("https://github.com/mledoze/countries") }) { Text("Country data: mledoze/countries · ODbL") }
            LazyColumn(Modifier.heightIn(max = 350.dp)) {
                items(countries.filter { countryQuery.isBlank() || it.name.contains(countryQuery, true) || it.dial.contains(countryQuery) }, key = { it.id }) { item ->
                    TextButton(onClick = { countryId = item.id; chooseCountry = false }, modifier = Modifier.fillMaxWidth()) { Text(item.name, Modifier.weight(1f)); Text(item.dial) }
                }
            }
        }
    })
}

private val LocalInputAreas = staticCompositionLocalOf { mutableMapOf<Any, androidx.compose.ui.geometry.Rect>() }
@Composable private fun Modifier.inputArea(): Modifier {
    val areas = LocalInputAreas.current
    val token = remember { Any() }
    DisposableEffect(token) { onDispose { areas.remove(token) } }
    return this.onGloballyPositioned { areas[token] = it.boundsInRoot() }
}
