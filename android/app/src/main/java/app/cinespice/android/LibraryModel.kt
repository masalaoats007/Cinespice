package app.cinespice.android

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class LibraryModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application), Telegram.Listener {
    internal val telegram = (application as CinespiceApp).telegram
    private val preferences = application.getSharedPreferences("cinespice", 0)
    var stage by mutableStateOf(telegram.stage); private set
    var page by mutableStateOf((saved.get<String>("page") ?: "home").let { if (it == "channels") "home" else it }); private set
    var chat by mutableLongStateOf(saved.get<Long>("chat") ?: 0); private set
    var chatTitle by mutableStateOf(saved.get<String>("chatTitle") ?: ""); private set
    var query by mutableStateOf(saved.get<String>("query") ?: ""); private set
    var collectionQuery by mutableStateOf(saved.get<String>("collectionQuery") ?: ""); private set
    var channelQuery by mutableStateOf(saved.get<String>("channelQuery") ?: ""); private set
    var savedList by mutableStateOf(saved.get<String>("savedList") ?: "Favorites"); private set
    var palette by mutableStateOf(preferences.getString("cinespice.theme", "Ember")!!); private set
    var sort by mutableIntStateOf(preferences.getInt("sort", 0)); private set
    var collectionPageNumber by mutableIntStateOf(0)
    var collectionSearchVisible by mutableStateOf(false)
    private var channelSortRevision by mutableIntStateOf(0)
    var channelsVisible by mutableStateOf(false); private set
    var status by mutableStateOf(""); private set
    var busy by mutableStateOf(false); private set
    var searching by mutableStateOf(false); private set
    var collectionSearching by mutableStateOf(false); private set
    var scanning by mutableStateOf(false); private set
    var discoveryMore by mutableStateOf(true); private set
    var searchMore by mutableStateOf(true); private set
    var collectionSearchMore by mutableStateOf(true); private set
    var collectionSearchError by mutableStateOf(""); private set
    var collectionMore by mutableStateOf(true); private set
    internal var videos by mutableStateOf<List<Video>>(emptyList()); private set
    internal var channels by mutableStateOf<List<Library.Chat>>(emptyList()); private set
    internal var history by mutableStateOf<List<Video>>(emptyList()); private set
    internal var continuing by mutableStateOf<List<Video>>(emptyList()); private set
    internal var searchResults by mutableStateOf<List<Video>>(emptyList()); private set
    internal var collectionSearchResults by mutableStateOf<List<Video>>(emptyList()); private set
    internal var lastSearch by mutableStateOf<Video?>(null); private set
    var favorites by mutableStateOf<Set<String>>(emptySet()); private set
    var watchlist by mutableStateOf<Set<String>>(emptySet()); private set
    var downloadStates by mutableStateOf<Map<String, String>>(emptyMap()); private set
    var preparing by mutableStateOf<Set<String>>(emptySet()); private set
    var account by mutableLongStateOf(-1); private set
    var day by mutableLongStateOf(System.currentTimeMillis() / 86_400_000); private set
    private var store: SavedStore? = null
    internal var artwork: Artwork? = null; private set
    private var scan: Job? = null
    private var channelSearchJob: Job? = null
    private var channelSearchEpoch = 0L
    private var queryJob: Job? = null
    private var collectionQueryJob: Job? = null
    private var session: SearchSession? = null
    private var collectionSession: SearchSession? = null
    private var queryEpoch = 0L
    private var collectionQueryEpoch = 0L
    private val loader = Mutex()
    private val sourceLoader = Mutex()
    var channelSearchLoading by mutableStateOf(false); private set
    var channelSearchError by mutableStateOf(""); private set
    var channelScanError by mutableStateOf(""); private set
    var channelSearchMatches by mutableStateOf<Set<Long>>(emptySet()); private set
    init { telegram.listen(this); changed() }
    override fun changed() {
        stage = telegram.stage
        if (stage == "ready" && account != telegram.library?.accountId) {
            scan?.cancel(); queryJob?.cancel(); collectionQueryJob?.cancel(); session = null; collectionSession = null; queryEpoch++; collectionQueryEpoch++
            store?.close(); artwork?.close()
            account = telegram.requireLibrary().accountId
            store = SavedStore.open(getApplication(), account)
            artwork = Artwork(telegram, File(getApplication<Application>().cacheDir, "artwork_$account"))
            discoveryMore = true; lastSearch = null; collectionSearchResults = emptyList(); collectionSearchError = ""
            reload(); reconcileDownloads()
            if (page == "search" && query.isNotBlank()) search(query)
        } else if (stage != "ready" && account != -1L) {
            scan?.cancel(); queryJob?.cancel(); collectionQueryJob?.cancel(); store?.close(); store = null; artwork?.close(); artwork = null
            account = -1; videos = emptyList(); history = emptyList(); continuing = emptyList(); channels = emptyList()
            favorites = emptySet(); watchlist = emptySet(); downloadStates = emptyMap(); lastSearch = null
            channelsVisible = false; navigate("home"); searchResults = emptyList(); collectionSearchResults = emptyList(); preparing = emptySet()
        }
        if (telegram.error.isNotEmpty()) status = telegram.error
    }
    fun foreground() { day = System.currentTimeMillis() / 86_400_000; if (stage == "ready") reload() }
    internal fun reload() {
        if (stage != "ready") return
        val library = telegram.requireLibrary()
        channels = library.chats(false); videos = library.videos(0, "", false, 10000)
        history = library.recentHistory(5); continuing = library.videos(0, "", true, 5)
        favorites = store?.entries()?.members("Favorites")?.toSet() ?: emptySet()
        watchlist = store?.entries()?.members("Watchlist")?.toSet() ?: emptySet()
        downloadStates = store?.entries()?.downloads()?.associate { it.id to it.state } ?: emptyMap()
        val selectedIds = channels.filter { it.selected() }.map { it.id() }.toSet()
        searchResults = searchResults.filter { it.chatId in selectedIds }
        if (lastSearch?.let { v -> channels.none { it.id() == v.chatId && it.selected() } } == true) lastSearch = null
    }
    fun tick() {
        if (stage != "ready") return
        var modified = false
        videos.forEach { v ->
            val state = downloadStates[SavedStore.id(v)]
            if (state == "Downloading" && telegram.completedPath(v).isNotEmpty()) {
                store?.entries()?.putDownload(SavedStore.Download(SavedStore.id(v), "Downloaded")); modified = true
            }
        }
        if (modified) reload()
    }
    fun navigate(destination: String) {
        page = destination; saved["page"] = destination
        if (destination != "search") { queryJob?.cancel(); searching = false }
        if (destination != "collection") { collectionQueryEpoch++; collectionQueryJob?.cancel(); collectionSearching = false }
        if (destination == "downloads") reconcileDownloads()
        if (destination == "search" && query.isNotBlank() && session == null) search(query)
        if (destination == "collection" && collectionQuery.isNotBlank() && collectionSession == null) searchCollection(collectionQuery)
    }
    internal fun browse(source: Library.Chat) {
        collectionPageNumber = 0; collectionSearchVisible = false
        closeChannels(false); chat = source.id(); chatTitle = source.title()
        collectionQuery = ""; saved["collectionQuery"] = ""; collectionSearchResults = emptyList(); collectionSearchMore = true
        collectionSearchError = ""; collectionSession = null; collectionQueryEpoch++; collectionQueryJob?.cancel(); collectionSearching = false
        saved["chat"] = chat; saved["chatTitle"] = chatTitle; collectionMore = true; navigate("collection")
        val target = chat
        work("Loading collection…") { collectionMore = network { loader.withLock { telegram.page(target, false) } }; reload() }
    }
    fun setList(value: String) { savedList = value; saved["savedList"] = value }
    fun theme(value: String) { palette = value; preferences.edit().putString("cinespice.theme", value).apply() }
    fun order(value: Int) {
        if (page == "collection") {
            preferences.edit().putInt("sort.$account.$chat", value).apply()
            channelSortRevision++; collectionPageNumber = 0
        } else { sort = value; preferences.edit().putInt("sort", value).apply() }
    }
    internal fun channelOrder(id: Long): Int {
        channelSortRevision // Compose tracks preference edits through this revision.
        return preferences.getInt("sort.$account.$id", 0).coerceIn(VideoSort.LABELS.indices)
    }
    internal fun sortedChannel(input: List<Video>, id: Long) = VideoSort.sorted(input, channelOrder(id))
    internal fun sorted(input: List<Video>) = VideoSort.sorted(input, sort)
    internal fun toggle(video: Video, list: String) {
        val library = telegram.requireLibrary(); library.save(video)
        val id = SavedStore.id(video); val entries = store!!.entries()
        if ((if (list == "Favorites") favorites else watchlist).contains(id)) entries.remove(id, list) else entries.put(SavedStore.Member(id, list))
        reload(); status = if ((if (list == "Favorites") favorites else watchlist).contains(id)) "Saved to $list" else "Removed from $list"
    }
    fun showChannels() { channelsVisible = true; scanSources(); if (channelQuery.isNotBlank()) searchChannels(channelQuery) }
    fun closeChannels() = closeChannels(true)
    private fun closeChannels(loadSelected: Boolean) {
        channelsVisible = false; scan?.cancel(); scan = null; scanning = false
        channelSearchEpoch++; channelSearchJob?.cancel(); channelSearchJob = null
        channelSearchLoading = false
        if (loadSelected && stage == "ready") {
            val targets = telegram.requireLibrary().chats(true)
            if (targets.isNotEmpty()) work("Loading selected channels…") {
                val failed = mutableListOf<String>()
                for (source in targets) {
                    try { network { loader.withLock { telegram.page(source.id(), false) } } }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { failed += source.title() }
                }
                reload()
                if (failed.isNotEmpty()) status = "Could not load videos from ${failed.size} selected source${if (failed.size == 1) "" else "s"}. Open a source to retry."
            }
        }
    }
    fun channelFilter(value: String) {
        channelQuery = value; saved["channelQuery"] = value
        searchChannels(value)
    }
    internal fun select(source: Library.Chat) { telegram.requireLibrary().select(source.id(), !source.selected()); reload() }
    fun scanSources() {
        if (scan?.isActive == true || !discoveryMore) return
        val generation = telegram.generation
        scan = viewModelScope.launch {
            scanning = true; channelScanError = ""
            try {
                while (channelsVisible && discoveryMore) {
                    discoveryMore = withContext(Dispatchers.IO) { sourceLoader.withLock { telegram.discover() } }
                    if (generation != telegram.generation) return@launch
                    reload(); delay(500)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { channelScanError = "Could not load channels: ${e.message ?: "Try again."}"; status = "${e.message} · Retry finding channels" }
            finally { scanning = false }
        }
    }
    fun searchChannels() = searchChannels(channelQuery)
    private fun searchChannels(value: String) {
        val filter = value.trim()
        channelSearchEpoch++
        val token = channelSearchEpoch
        channelSearchJob?.cancel()
        channelSearchError = ""
        channelSearchMatches = emptySet()
        if (filter.isEmpty() || stage != "ready") { channelSearchLoading = false; return }
        channelSearchLoading = true
        channelSearchJob = viewModelScope.launch {
            try {
                delay(450)
                val matches = withContext(Dispatchers.IO) { sourceLoader.withLock { telegram.searchChats(filter) } }
                if (token == channelSearchEpoch && channelsVisible) { channelSearchMatches = matches; reload() }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (token == channelSearchEpoch) channelSearchError = "Channel search failed: ${e.message ?: "Try again."}"
            } finally {
                if (token == channelSearchEpoch) channelSearchLoading = false
            }
        }
    }
    fun search(value: String) {
        query = value; saved["query"] = value; session = null; queryEpoch++
        val token = queryEpoch; queryJob?.cancel(); searchResults = emptyList(); searching = value.isNotBlank(); searchMore = true
        if (value.isBlank()) { searching = false; return }
        queryJob = viewModelScope.launch {
            delay(450)
            val selected = telegram.requireLibrary().chats(true)
            val current = SearchSession(telegram, selected, value.trim()); session = current
            searchResults = sorted(telegram.requireLibrary().videos(0, value.trim(), false, 10000))
            try {
                searchMore = withContext(Dispatchers.IO) { loader.withLock { current.next() } }
                if (token == queryEpoch) { reload(); mergeSearch(current); lastSearch = searchResults.firstOrNull() }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (token == queryEpoch) status = "${e.message} · Retry search" }
            finally { if (token == queryEpoch) searching = false }
        }
    }
    private fun mergeSearch(current: SearchSession) {
        val selected = channels.filter { it.selected() }.map { it.id() }.toSet()
        searchResults = sorted((telegram.requireLibrary().videos(0, query.trim(), false, 10000) + current.results.values).filter { it.chatId in selected }.distinctBy { it.key() })
    }
    fun moreSearch() {
        if (searching) return
        val current = session ?: run { search(query); return }
        val token = queryEpoch
        searching = true
        queryJob = viewModelScope.launch {
            try {
                searchMore = withContext(Dispatchers.IO) { loader.withLock { current.next() } }
                if (token == queryEpoch) { reload(); mergeSearch(current); lastSearch = searchResults.firstOrNull() }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status = "${e.message} · Retry search" }
            finally { if (token == queryEpoch) searching = false }
        }
    }
    fun searchCollection(value: String) {
        collectionPageNumber = 0
        collectionQuery = value; saved["collectionQuery"] = value; collectionSession = null; collectionQueryEpoch++
        val token = collectionQueryEpoch; collectionQueryJob?.cancel(); collectionSearchResults = emptyList()
        collectionSearching = value.isNotBlank(); collectionSearchMore = true; collectionSearchError = ""
        if (value.isBlank()) { collectionSearching = false; return }
        val targetChat = chat
        val source = channels.firstOrNull { it.id() == targetChat } ?: run { collectionSearching = false; return }
        collectionQueryJob = viewModelScope.launch {
            delay(450)
            val current = SearchSession(telegram, listOf(source), value.trim()); collectionSession = current
            collectionSearchResults = sorted(telegram.requireLibrary().videos(0, value.trim(), false, 10000).filter { it.chatId == targetChat })
            try {
                collectionSearchMore = withContext(Dispatchers.IO) { loader.withLock { current.next() } }
                if (token == collectionQueryEpoch && page == "collection" && chat == targetChat) { reload(); mergeCollectionSearch(current) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (token == collectionQueryEpoch) collectionSearchError = e.message ?: "Channel search failed. Try again." }
            finally { if (token == collectionQueryEpoch) collectionSearching = false }
        }
    }
    private fun mergeCollectionSearch(current: SearchSession) {
        val targetChat = chat
        val cached = telegram.requireLibrary().videos(0, collectionQuery.trim(), false, 10000).filter { it.chatId == targetChat }
        collectionSearchResults = sorted((cached + current.results.values).filter { it.chatId == targetChat }.distinctBy { it.key() })
    }
    fun moreCollectionSearch() {
        if (collectionSearching) return
        val current = collectionSession ?: run { searchCollection(collectionQuery); return }
        val token = collectionQueryEpoch; val targetChat = chat; collectionSearching = true
        collectionQueryJob = viewModelScope.launch {
            try {
                collectionSearchMore = withContext(Dispatchers.IO) { loader.withLock { current.next() } }
                if (token == collectionQueryEpoch && page == "collection" && chat == targetChat) { reload(); mergeCollectionSearch(current) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (token == collectionQueryEpoch) collectionSearchError = e.message ?: "Channel search failed. Try again." }
            finally { if (token == collectionQueryEpoch) collectionSearching = false }
        }
    }
    fun refresh() {
        if (page == "search") { if (query.isNotBlank()) search(query); return }
        if (page == "collection" && collectionQuery.isNotBlank()) { searchCollection(collectionQuery); return }
        val selected = telegram.requireLibrary().chats(true).map { it.id() }
        val target = if (page == "collection") listOf(chat) else selected
        work("Refreshing newest videos…") { network { loader.withLock { target.forEach { telegram.page(it, true) } } }; reload(); status = "Newest videos refreshed" }
    }
    fun older() {
        val target = chat
        if (page == "collection" && collectionMore) work("Loading older videos…") { collectionMore = network { loader.withLock { telegram.page(target, false) } }; reload() }
    }
    internal fun download(video: Video) {
        val id = SavedStore.id(video)
        if (id in preparing || downloadStates[id] == "Downloading") return
        preparing = preparing + id
        store!!.entries().putDownload(SavedStore.Download(id, "Preparing")); reload()
        val owner = account
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { telegram.download(video) }
                if (owner == account) { store?.entries()?.putDownload(SavedStore.Download(id, if (telegram.completedPath(video).isNotEmpty()) "Downloaded" else "Downloading")); status = "Download started. Keep Cinespice open." }
            } catch (e: Exception) { if (owner == account) { store?.entries()?.putDownload(SavedStore.Download(id, "Retry download")); status = e.message ?: "Download failed" } }
            finally { preparing = preparing - id; if (owner == account) reload() }
        }
    }
    internal fun cancelDownload(video: Video) {
        if (busy) return
        val id = SavedStore.id(video)
        store?.entries()?.putDownload(SavedStore.Download(id, "Cancelling")); reload()
        work("Cancelling download…") {
            try { network { telegram.cancelDownload(video) }; store?.entries()?.putDownload(SavedStore.Download(id, "Cancelled")) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { store?.entries()?.putDownload(SavedStore.Download(id, "Downloading")); throw e }
            finally { reload() }
        }
    }
    internal fun mark(video: Video) { telegram.requireLibrary().progress(video, video.duration * 1000L, video.duration * 1000L, true); reload() }
    internal fun removeHistory(video: Video) { telegram.requireLibrary().removeHistory(video.key()); reload() }
    private fun reconcileDownloads() = work("Checking saved downloads…") {
        val records = store?.entries()?.downloads().orEmpty()
        for (record in records) {
            val video = videos.find { SavedStore.id(it) == record.id } ?: continue
            try {
                val current = network { telegram.prepare(video) }
                store?.entries()?.putDownload(SavedStore.Download(record.id, if (telegram.completedPath(current).isNotEmpty()) "Downloaded" else "Retry download"))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { store?.entries()?.putDownload(SavedStore.Download(record.id, "Retry download")) }
        }
        reload()
    }
    fun clear(kind: String) = work("Clearing $kind…") {
        when (kind) {
            "history" -> telegram.requireLibrary().clearHistory()
            "downloads" -> { network { telegram.clearDownloads() }; store?.entries()?.clearDownloads() }
            "artwork" -> { network { telegram.request(Json.obj("@type", "optimizeStorage", "size", 0, "ttl", 0, "count", 0, "immunity_delay", 0, "file_types", org.json.JSONArray().put(Json.obj("@type", "fileTypeThumbnail")))) }; artwork?.clear() }
            "session" -> network { telegram.request(Json.obj("@type", "logOut")) }
        }
        reload(); status = "$kind cleared"
    }
    internal suspend fun <T> network(block: suspend () -> T): T {
        val generation = telegram.generation
        val result = withContext(Dispatchers.IO) { block() }
        if (generation != telegram.generation) throw CancellationException("Account changed")
        return result
    }
    internal fun work(message: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true; status = message
        val owner = telegram.generation
        viewModelScope.launch {
            try {
                block()
                if (owner != telegram.generation) return@launch
                if (status == message) status = ""
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (owner == telegram.generation) status = e.message ?: "Operation failed. Try again." }
            finally { busy = false }
        }
    }
    fun message(value: String) { status = value }
    override fun onCleared() { telegram.unlisten(this); scan?.cancel(); channelSearchJob?.cancel(); queryJob?.cancel(); collectionQueryJob?.cancel(); store?.close(); artwork?.close() }
}
