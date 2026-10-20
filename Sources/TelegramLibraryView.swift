import SwiftUI

struct TelegramLibraryView: View {
    @AppStorage("cinespice.theme") private var theme: CinespiceTheme = .ember
    @AppStorage("cinespice.order") private var order: LibraryOrder = .latestRelease
    @Environment(\.scenePhase) private var scenePhase
    @State private var dailySeed = Int(Date().timeIntervalSince1970 / 86400)
    @State private var savedList = "Favorites"
    @State private var lastSearchVideo: TelegramVideo?
    @State private var refreshMessage = ""
    @EnvironmentObject private var telegram: TelegramClient
    @EnvironmentObject private var library: LibraryStore
    @EnvironmentObject private var rangeServer: TelegramRangeServer
    @State private var apiID = ""
    @State private var apiHash = ""
    @State private var phone = ""
    @State private var country = Locale.current.regionCode ?? "US"
    @State private var code = ""
    @State private var password = ""
    @State private var email = ""
    @State private var emailCode = ""
    @State private var tab = 0
    @State private var viewport = CGSize(width: 390, height: 844)
    @State private var detailsVideo: TelegramVideo?
    @State private var query = ""
    @State private var collectionQuery = ""
    @State private var collectionSearchVisible = false
    @State private var collectionPageNumber = 0
    @AppStorage("cinespice.channelOrders") private var channelOrders = "{}"
    @State private var downloadSuggestion: TelegramVideo?
    @State private var exportFile: VideoExport?
    @State private var exportDirectory: URL?
    @State private var exporting = false
    @State private var collection: TelegramChat?
    @State private var showCollections = false
    @State private var showSettings = false
    @State private var selection: PlaybackSelection?
    @State private var openingToken = UUID()
    @State private var openingID: String?
    @State private var openingTitle = ""
    @State private var openTask: Task<Void, Never>?
    @State private var playbackError = ""

    var body: some View {
        ZStack {
            theme.background.ignoresSafeArea()
            if telegram.stage == .ready { libraryScreen.disabled(openingID != nil).accessibilityHidden(openingID != nil) }
            else { authentication }
            if openingID != nil {
                MediaOpeningOverlay(title: openingTitle, cancel: cancelOpening)
                    .zIndex(10)
            }
        }
        .background(GeometryReader { geometry in
            Color.clear.allowsHitTesting(false).onAppear { viewport = geometry.size }.onChange(of: geometry.size) { viewport = $0 }
        })
        .foregroundStyle(theme.text)
        .tint(theme.accent)
        .preferredColorScheme(theme.scheme)
        .background(KeyboardDismissObserver().frame(width: 0, height: 0))
        .task { telegram.restoreSession() }
        .onChange(of: scenePhase) { if $0 == .active { dailySeed = Int(Date().timeIntervalSince1970 / 86400) } }
        .sheet(isPresented: $showCollections) { CollectionPicker { chat in showCollections = false; openCollection(chat) } }
        .sheet(item: $exportFile, onDismiss: {
            if let directory = exportDirectory {
                Task.detached(priority: .utility) { try? FileManager.default.removeItem(at: directory) }
            }
            exportDirectory = nil
        }) { file in VideoExportPicker(file: file) { exportFile = nil } }
        .sheet(item: $detailsVideo) { VideoDetailsView(video: $0) }
        .sheet(isPresented: $showSettings) { LibrarySettingsView() }
        .fullScreenCover(item: $selection) { item in
            VLCPlaybackView(selection: item, library: library) { selection = nil }
        }
        .alert("Could not open video", isPresented: Binding(get: { !playbackError.isEmpty }, set: { if !$0 { playbackError = "" } })) {
            if let video = downloadSuggestion {
                Button("Download") { telegram.download(video); downloadSuggestion = nil }
            }
            Button("OK", role: .cancel) { playbackError = ""; downloadSuggestion = nil }
        } message: { Text(playbackError) }
        .onChange(of: telegram.stage) { stage in
            if stage != .code { code = "" }; if stage != .password { password = "" }; if stage != .emailCode { emailCode = "" }
            if stage == .ready { apiID = ""; apiHash = "" }
            if stage != .ready {
                cancelOpening()
                showCollections = false; showSettings = false
                lastSearchVideo = nil; tab = 0; selection = nil; exportFile = nil; collection = nil; detailsVideo = nil; query = ""; rangeServer.stop()
            }
        }
    }

    private var browseVideos: [TelegramVideo] {
        order.sort(library.videos)
    }

    private var libraryScreen: some View {
        VStack(spacing: 0) {
            header
            if !refreshMessage.isEmpty { Text(refreshMessage).font(.caption).foregroundStyle(theme.muted).padding(6) }
            if exporting { ProgressView("Preparing export…").font(.caption).tint(theme.accent).padding(10) }
            ScrollViewReader { scroll in ScrollView {
                LazyVStack(alignment: .leading, spacing: 28) {
                    Color.clear.frame(height: 0).id("channel-top")
                    if let collection { collectionPage(collection) }
                    else {
                        switch tab {
                        case 1: favoritesPage
                        case 2: searchPage
                        case 3: downloadsPage
                        default: home
                        }
                    }
                    if !library.error.isEmpty { banner(library.error) }
                    if !telegram.status.isEmpty { banner(telegram.status) }
                }.padding(.bottom, 24).frame(maxWidth: 1200).frame(maxWidth: .infinity)
            }.coordinateSpace(name: "library-scroll")
                .scrollDismissesKeyboard(.interactively)
                .id("\(tab)_\(collection?.id ?? 0)_\(tab == 1 ? savedList : "")_\(library.sessionID)")
                .onChange(of: collectionPageNumber) { _ in scroll.scrollTo("channel-top", anchor: .top) }
            }
            if collection == nil { tabs }
        }

        .buttonStyle(CinespiceButtonStyle())
        .task(id: refreshMessage) {
            guard !refreshMessage.isEmpty else { return }
            do { try await Task.sleep(nanoseconds: 3_000_000_000) } catch { return }
            refreshMessage = ""
        }
        .task(id: "\(showCollections)_" + library.selectedCollections.map { String($0.id) }.joined(separator: ",")) {
            guard !showCollections else { return }
            for chat in library.selectedCollections { telegram.ensureChat(chat.id) }
        }
    }

    private var header: some View {
        HStack(spacing: 4) {
            if let chat = collection {
                Button { collection = nil; collectionQuery = "" } label: { Image(systemName: "arrow.left").frame(width: 44, height: 44) }.accessibilityLabel("Back")
                Text(chat.title).font(.headline).lineLimit(1).frame(maxWidth: .infinity, alignment: .leading)
                Menu {
                    Picker("Sort channel", selection: Binding(get: { channelOrder(chat.id) }, set: { saveChannelOrder($0, chatID: chat.id) })) {
                        ForEach(LibraryOrder.allCases, id: \.self) { Text($0.title).tag($0) }
                    }
                } label: { Image(systemName: "arrow.up.arrow.down").frame(width: 44, height: 44) }.accessibilityLabel("Sort channel")
                Button { collectionSearchVisible.toggle(); if !collectionSearchVisible { collectionQuery = "" } } label: {
                    Image(systemName: "magnifyingglass").frame(width: 44, height: 44)
                }.accessibilityLabel("Search this channel")
            } else {
                Button(action: refreshCurrentPage) {
                    Text("CINESPICE").tracking(-1).font(.system(size: 22, weight: .black, design: .rounded))
                        .minimumScaleFactor(0.6).lineLimit(1).foregroundStyle(theme.accent)
                }.accessibilityLabel("Cinespice, refresh current library")
                Spacer(minLength: 0)
                Button { showCollections = true } label: { Image(systemName: "rectangle.stack").frame(width: 44, height: 44) }.accessibilityLabel("Channels and groups")
                Button { showSettings = true } label: { Image(systemName: "person.crop.circle").frame(width: 44, height: 44) }.accessibilityLabel("Profile and settings")
            }
        }.padding(.horizontal, 16).padding(.vertical, 2).background(.ultraThinMaterial)
    }

    private func channelOrder(_ chatID: Int64) -> LibraryOrder {
        let values = (try? JSONDecoder().decode([String: String].self, from: Data(channelOrders.utf8))) ?? [:]
        return LibraryOrder(rawValue: values["\(library.accountID ?? 0):\(chatID)"] ?? "") ?? .latestRelease
    }
    private func saveChannelOrder(_ value: LibraryOrder, chatID: Int64) {
        var values = (try? JSONDecoder().decode([String: String].self, from: Data(channelOrders.utf8))) ?? [:]
        values["\(library.accountID ?? 0):\(chatID)"] = value.rawValue
        if let data = try? JSONEncoder().encode(values), let text = String(data: data, encoding: .utf8) { channelOrders = text }
        collectionPageNumber = 0
    }

    private func refreshCurrentPage() {
        if tab == 2, collection == nil, !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            Task {
                let searched = query
                await telegram.searchVideos(searched)
                guard query == searched else { return }
                lastSearchVideo = telegram.videoSearchResults.first
            }
            refreshMessage = "Refreshing search…"
        } else if let chat = collection {
            if !collectionQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                Task { await telegram.searchVideos(collectionQuery, in: chat.id) }
                refreshMessage = "Refreshing search in " + chat.title + "…"
            } else {
                telegram.loadChat(chat.id, refresh: true)
                refreshMessage = "Refreshing " + chat.title + "…"
            }
        } else if !library.selectedCollections.isEmpty {
            for chat in library.selectedCollections { telegram.loadChat(chat.id, refresh: true) }
            refreshMessage = "Refreshing selected sources, one page at a time…"
        } else { refreshMessage = "Select sources using the channel icon." }
    }

    private var sortControl: some View {
        Menu {
            Picker("Sort videos", selection: $order) { ForEach(LibraryOrder.allCases, id: \.self) { Text($0.title).tag($0) } }
        } label: { Label(order.title, systemImage: "arrow.up.arrow.down").font(.caption.weight(.semibold)).frame(minHeight: 44) }
    }

    private var home: some View {
        VStack(alignment: .leading, spacing: 20) {
            heroCarousel
            if !library.continueWatching.isEmpty { rail("Continue Watching", subtitle: "", videos: Array(library.continueWatching.prefix(5)), wide: true) }
            if library.selectedCollections.isEmpty {
                VStack(spacing: 12) {
                    LibraryEmptyState(symbol: "rectangle.stack", title: "Choose your channels", detail: "Select Telegram channels or groups to make them appear here.")
                    Button("Choose channels") { showCollections = true }.buttonStyle(.borderedProminent)
                }
            }
            ForEach(library.selectedCollections) { chat in
                VStack(alignment: .leading, spacing: 12) {
                    Button { openCollection(chat) } label: {
                        HStack { Text(chat.title).font(.title3.bold()).lineLimit(1); Spacer(); Image(systemName: "chevron.right") }
                            .padding(.horizontal, 20).frame(minHeight: 44)
                    }.accessibilityLabel("Open \(chat.title)")
                    let videos = channelOrder(chat.id).sort(library.videos.filter { $0.chatID == chat.id })
                    if videos.isEmpty { Text("No files loaded yet. Open this channel to load or retry.").font(.caption).foregroundStyle(theme.muted).padding(.horizontal, 20) }
                    else { posterRail(Array(videos.prefix(10)), wide: false) }
                }
            }
            if !telegram.loadingCollections.isEmpty { ProgressView("Loading selected channels…").frame(maxWidth: .infinity).font(.caption).tint(theme.accent) }
        }
    }

    private var heroHeight: CGFloat { min(440, max(290, viewport.height * 0.49)) }
    private var dailyVideo: TelegramVideo? {
        let items = library.videos.sorted { $0.id < $1.id }
        guard !items.isEmpty else { return nil }
        var seed = UInt64(dailySeed) &+ 0x9E3779B97F4A7C15
        seed = (seed ^ (seed >> 30)) &* 0xBF58476D1CE4E5B9
        seed = (seed ^ (seed >> 27)) &* 0x94D049BB133111EB
        return items[Int((seed ^ (seed >> 31)) % UInt64(items.count))]
    }
    private var heroCarousel: some View {
        // Seven editorial positions; no fabricated catalogue results.
        TabView {
            heroSlot("Recently added", video: LibraryOrder.newest.sort(library.videos).first, empty: "Add a source to discover your videos.").tag(0)
            heroSlot("Today's pick", video: dailyVideo, empty: "Your daily pick appears when videos are indexed.").tag(1)
            ForEach(0..<4) { index in
                heroSlot("Trending · \(index + 1)", video: nil, empty: "Catalogue connection coming later. No trending data connected yet.").tag(index + 2)
            }
            heroSlot("Last searched", video: lastSearchVideo.flatMap { library.snapshot.selected.contains($0.chatID) ? $0 : nil }, empty: "Search for a video to fill this spot.").tag(6)
        }.tabViewStyle(.page(indexDisplayMode: .always)).indexViewStyle(.page(backgroundDisplayMode: .always))
            .frame(height: heroHeight + 66)
    }
    private func heroSlot(_ title: String, video: TelegramVideo?, empty: String) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(title.uppercased()).font(.caption2.bold()).tracking(1.5).foregroundStyle(theme.accent).padding(.horizontal, 20).padding(.vertical, 8)
            if let video { hero(video) }
            else {
                ZStack {
                    LinearGradient(colors: [theme.accent.opacity(0.2), theme.surface], startPoint: .topTrailing, endPoint: .bottomLeading)
                    LibraryEmptyState(symbol: title.hasPrefix("Trending") ? "sparkles" : "play.rectangle", title: title, detail: empty)
                }.frame(height: heroHeight).clipShape(RoundedRectangle(cornerRadius: 18)).padding(.horizontal, 16)
            }
            Spacer(minLength: 28)
        }
    }
    @ViewBuilder private var releaseSortHint: some View {
        if order == .latestRelease || order == .earliestRelease {
            Text("Year hints from filenames · unknown years last. Catalogue release dates coming later.").font(.caption).foregroundStyle(theme.muted).padding(.horizontal, 24)
        }
    }

    private func hero(_ video: TelegramVideo) -> some View {
        ZStack(alignment: .bottomLeading) {
            VideoArtwork(video: video)
            LinearGradient(colors: [.black.opacity(0.1), .clear, .black.opacity(0.45), .black.opacity(0.95)], startPoint: .top, endPoint: .bottom).allowsHitTesting(false)
            LinearGradient(colors: [.black.opacity(0.4), .clear], startPoint: .leading, endPoint: .trailing).allowsHitTesting(false)
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 6) {
                    Text("C").font(.system(size: 22, weight: .black)).foregroundStyle(theme.accent)
                    Text(library.snapshot.collections.first(where: { $0.id == video.chatID })?.title.uppercased() ?? "YOUR LIBRARY").font(.system(size: 10, weight: .semibold)).tracking(2).lineLimit(1)
                }
                // Keep tracking on Text before View modifiers.
                Text(video.title).tracking(-0.9).font(.system(size: 27, weight: .heavy)).lineLimit(2)
                HStack(spacing: 10) { Text(video.formatLabel).font(.system(size: 10, weight: .bold)).padding(.horizontal, 5).padding(.vertical, 3).overlay(RoundedRectangle(cornerRadius: 2).stroke(.white.opacity(0.6))); Text(ByteCountFormatter.string(fromByteCount: video.size, countStyle: .file)).font(.caption); if video.duration > 0 { Text("\(video.duration / 60) min").font(.caption) } }.foregroundStyle(.white.opacity(0.8))
                if let caption = video.caption { Text(caption).font(.subheadline).foregroundStyle(.white.opacity(0.75)).lineLimit(2) }
                HStack(spacing: 12) {
                    Button { play(video) } label: {
                        Label(openingID == video.id ? "Opening…" : (library.snapshot.history[video.id]?.canResume == true ? "Resume" : "Play"), systemImage: "play.fill")
                            .font(.subheadline.weight(.bold)).foregroundStyle(.black).frame(maxWidth: .infinity).padding(.vertical, 13).background(.white, in: RoundedRectangle(cornerRadius: 5))
                    }.disabled(openingID != nil)
                    Button { detailsVideo = video } label: { Label("More info", systemImage: "info.circle").font(.subheadline.weight(.semibold)).frame(maxWidth: .infinity).padding(.vertical, 13).background(.white.opacity(0.2), in: RoundedRectangle(cornerRadius: 5)) }
                }
            }.padding(.horizontal, 24).padding(.bottom, 20)
        }.frame(height: heroHeight).foregroundStyle(.white).clipShape(RoundedRectangle(cornerRadius: 18)).padding(.horizontal, 16).contentShape(Rectangle())
    }

    private var favoritesPage: some View {
        let items = order.sort(library.videos.filter { library.snapshot.savedLists[savedList]?.contains($0.id) == true })
        return VStack(alignment: .leading, spacing: 16) {
            pageTitle("Saved for you", subtitle: "FAVORITES & WATCHLIST")
            Picker("Saved list", selection: $savedList) { Text("Favorites").tag("Favorites"); Text("Watchlist").tag("Watchlist") }.pickerStyle(.segmented).padding(.horizontal, 24)
            HStack { Text("\(items.count) titles").font(.caption); Spacer(); sortControl }.padding(.horizontal, 24)
            releaseSortHint
            if items.isEmpty { LibraryEmptyState(symbol: savedList == "Favorites" ? "heart" : "bookmark", title: "Make it yours", detail: "Long press a file to add it here. Saved items from deselected sources stay saved and reappear when selected again.") }
            else { grid(items) }
        }.id(savedList)
    }

    private var downloadsPage: some View {
        let items = order.sort(library.videos.filter { library.display($0).isDownloaded || telegram.fullDownloads.contains($0.fileID) || telegram.preparingDownloads.contains($0.id) })
        return VStack(alignment: .leading, spacing: 16) {
            pageTitle("Downloads", subtitle: "READY FOR YOUR NEXT WATCH")
            Text("Long press any file to download. Completed downloads offer Save to Files with the original filename. Clear local downloads in Profile → Storage.").font(.caption).foregroundStyle(theme.muted).padding(.horizontal, 24)
            HStack { Text("\(items.count) titles").font(.caption); Spacer(); sortControl }.padding(.horizontal, 24)
            releaseSortHint
            if items.isEmpty { LibraryEmptyState(symbol: "arrow.down.circle", title: "Take your cinema with you", detail: "Full downloads from selected sources appear here. Partial streaming buffers are not downloads.") }
            else { grid(items) }
        }
    }

    private func collectionPage(_ chat: TelegramChat) -> some View {
        let text = collectionQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        let cached = browseVideos.filter { $0.chatID == chat.id }
        let local = text.isEmpty ? cached : cached.filter { $0.title.localizedCaseInsensitiveContains(text) || ($0.fileName?.localizedCaseInsensitiveContains(text) == true) }
        let remote = text.isEmpty ? [] : telegram.videoSearchResults.filter { $0.chatID == chat.id }
        let items = channelOrder(chat.id).sort(Array(Dictionary((local + remote).map { ($0.id, $0) }, uniquingKeysWith: { _, latest in latest }).values))
        let cachedIndex = library.snapshot.indexes[chat.id]
        return VStack(alignment: .leading, spacing: 20) {
            if collectionSearchVisible { HStack {
                Image(systemName: "magnifyingglass").foregroundStyle(theme.muted)
                TextField("Search this channel", text: $collectionQuery).textInputAutocapitalization(.never).disableAutocorrection(true)
                if !collectionQuery.isEmpty {
                    Button { collectionQuery = "" } label: { Image(systemName: "xmark.circle.fill").frame(width: 44, height: 44).contentShape(Rectangle()) }.accessibilityLabel("Clear channel search")
                }
            }.frame(minHeight: 44).padding(.horizontal, 15).padding(.vertical, 4).background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16)).padding(.horizontal, 24) }
            HStack {
                Text(text.isEmpty ? "\(items.count) loaded titles" : "\(items.count) results in this channel").font(.caption).foregroundStyle(theme.muted)
                Spacer()
                Text(channelOrder(chat.id).title).font(.caption).foregroundStyle(theme.muted)
            }.padding(.horizontal, 20)
            Text("Sorting applies to loaded files. More pages become available as files are indexed.").font(.caption).foregroundStyle(theme.muted).padding(.horizontal, 20)
            let pageCount = max(1, (items.count + 29) / 30)
            let currentPage = min(collectionPageNumber, pageCount - 1)
            grid(Array(items.dropFirst(currentPage * 30).prefix(30)))
            HStack {
                Button { collectionPageNumber = max(0, currentPage - 1) } label: { Image(systemName: "chevron.left").frame(width: 44, height: 44) }.disabled(currentPage == 0).accessibilityLabel("Previous page")
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack {
                        ForEach(0..<pageCount, id: \.self) { page in
                            Button { collectionPageNumber = page } label: {
                                Text(String(page + 1)).frame(minWidth: 44, minHeight: 44)
                                    .background(page == currentPage ? theme.accent.opacity(0.2) : .clear, in: Circle())
                            }.accessibilityLabel("Page \(page + 1)").accessibilityAddTraits(page == currentPage ? .isSelected : [])
                        }
                    }
                }
                Button { collectionPageNumber = min(pageCount - 1, currentPage + 1) } label: { Image(systemName: "chevron.right").frame(width: 44, height: 44) }.disabled(currentPage + 1 >= pageCount).accessibilityLabel("Next page")
            }.padding(.horizontal, 20)
            if telegram.isSearchingVideos { ProgressView("Searching this channel…").frame(maxWidth: .infinity).tint(theme.accent) }
            else if !text.isEmpty && !telegram.videoSearchError.isEmpty {
                Text(telegram.videoSearchError).font(.caption).foregroundStyle(.orange)
                Button("Retry channel search") { Task { await telegram.searchVideos(text, in: chat.id) } }
            }
            if let error = telegram.collectionErrors[chat.id] { Text(error).font(.caption).foregroundStyle(.orange).padding(.horizontal, 24) }
            if items.isEmpty && !telegram.loadingCollections.contains(chat.id) && !telegram.isSearchingVideos {
                LibraryEmptyState(symbol: text.isEmpty ? "film" : "magnifyingglass", title: text.isEmpty ? "No titles loaded" : "No matching videos", detail: text.isEmpty ? "Load this channel's videos to start browsing." : "Try another title or filename in this channel.")
            }
            if !text.isEmpty && !telegram.isSearchingVideos && telegram.videoSearchError.isEmpty && telegram.hasMoreVideoResults {
                Button { Task { await telegram.nextVideoSearchPage() } } label: { Text("More channel results").frame(minHeight: 44) }.buttonStyle(.bordered).tint(theme.accent).frame(maxWidth: .infinity)
            } else if text.isEmpty && telegram.loadingCollections.contains(chat.id) { ProgressView("Finding more videos…").frame(maxWidth: .infinity).tint(theme.accent) }
            else if text.isEmpty && (cachedIndex == nil || cachedIndex?.hasMore == true) {
                Button { telegram.loadChat(chat.id) } label: { Text("Load more videos").frame(minHeight: 44) }.buttonStyle(.bordered).tint(theme.accent).frame(maxWidth: .infinity)
            }
        }
        .onChange(of: collectionQuery) { _ in collectionPageNumber = 0 }
        .task(id: "collection_\(chat.id)_\(collectionQuery)") { await telegram.searchVideos(collectionQuery, in: chat.id) }
    }

    private var searchPage: some View {
        let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
        let local = text.isEmpty ? [] : browseVideos.filter { $0.title.localizedCaseInsensitiveContains(text) || ($0.fileName?.localizedCaseInsensitiveContains(text) == true) }
        let merged = Dictionary((local + (text.isEmpty ? [] : telegram.videoSearchResults).filter { library.snapshot.selected.contains($0.chatID) }).map { ($0.id, $0) }, uniquingKeysWith: { _, latest in latest })
        let results = order.sort(Array(merged.values))
        return VStack(alignment: .leading, spacing: 22) {
            pageTitle("Find your next watch.", subtitle: "SEARCH SELECTED CHANNELS & GROUPS")
            HStack { Image(systemName: "magnifyingglass").foregroundStyle(theme.muted); TextField("Video title or filename", text: $query).textInputAutocapitalization(.never); if !query.isEmpty { Button { query = "" } label: { Image(systemName: "xmark.circle.fill").frame(width: 44, height: 44).contentShape(Rectangle()) }.accessibilityLabel("Clear search") } }
                .frame(minHeight: 44).padding(.horizontal, 15).padding(.vertical, 4).background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16)).padding(.horizontal, 24)
            if text.isEmpty { LibraryEmptyState(symbol: "magnifyingglass", title: "What are you in the mood for?", detail: "Search video names and filenames in your selected channels and groups.") }
            else {
                HStack { Text("\(results.count) results").font(.caption); Spacer(); sortControl }.padding(.horizontal, 24)
                releaseSortHint
                grid(results)
                if telegram.isSearchingVideos { ProgressView("Searching Telegram…").frame(maxWidth: .infinity).tint(theme.accent) }
                else if !telegram.videoSearchError.isEmpty {
                    Text(telegram.videoSearchError).font(.caption).foregroundStyle(.orange)
                    Button("Retry search") { Task { await telegram.nextVideoSearchPage() } }
                } else if telegram.hasMoreVideoResults {
                    Button("More results") { Task { await telegram.nextVideoSearchPage() } }

                } else if results.isEmpty && !text.isEmpty { LibraryEmptyState(symbol: "magnifyingglass", title: "No matching videos", detail: "Try a different filename or select more channels and groups.") }
                LibraryPageTrigger(enabled: telegram.hasMoreVideoResults && !telegram.isSearchingVideos && telegram.videoSearchError.isEmpty, revision: String(telegram.videoSearchResults.count), viewportHeight: viewport.height) { Task { await telegram.nextVideoSearchPage() } }
            }
        }
        .task(id: query + library.selectedCollections.map { String($0.id) }.joined(separator: ",")) {
            let searched = query
            await telegram.searchVideos(searched)
            guard !Task.isCancelled, query == searched, !searched.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
            lastSearchVideo = order.sort(local + telegram.videoSearchResults.filter { library.snapshot.selected.contains($0.chatID) }).first
        }
    }

    private func posterRail(_ videos: [TelegramVideo], wide: Bool) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(alignment: .top, spacing: 10) { ForEach(videos) { video in card(video, wide: wide).frame(width: wide ? min(320, viewport.width * 0.72) : min(190, viewport.width * 0.39)) } }.padding(.horizontal, 20)
        }
    }
    private func rail(_ title: String, subtitle: String, videos: [TelegramVideo], wide: Bool) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            sectionTitle(title, detail: subtitle)
            posterRail(videos, wide: wide)
        }
    }
    private func grid(_ videos: [TelegramVideo]) -> some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 140), spacing: 16)], alignment: .leading, spacing: 24) {
            ForEach(videos) { video in card(video) }
        }.padding(.horizontal, 24)
    }
    private func card(_ video: TelegramVideo, wide: Bool = false) -> some View {
        VideoCard(video: video, wide: wide, opening: openingID == video.id, play: { play(video, resume: $0) }, download: { rangeServer.stop(); telegram.download(video) }, export: { exportVideo(video) }, info: { detailsVideo = video })
    }
    private func exportVideo(_ video: TelegramVideo) {
        guard !exporting else { return }; exporting = true
        let session = library.sessionID
        Task {
            defer { exporting = false }
            do {
                let current = try await telegram.prepare(video)
                let file = try await VideoExport.prepare(current)
                guard session == library.sessionID else { try? FileManager.default.removeItem(at: file.directory); return }
                exportDirectory = file.directory; exportFile = file
            } catch { playbackError = "Could not export video: \(error.localizedDescription)" }
        }
    }
    private func openCollection(_ chat: TelegramChat) {
        if collection?.id != chat.id { collectionQuery = "" }
        collection = chat; collectionPageNumber = 0; collectionSearchVisible = false
        telegram.ensureChat(chat.id)
    }
    private func play(_ video: TelegramVideo, resume: Bool = true) {
        guard openingID == nil, selection == nil else { return }
        openingTitle = video.fileName ?? video.title
        withAnimation(.easeOut(duration: 0.15)) { openingID = video.id }
        let session = library.sessionID
        let token = UUID(); openingToken = token
        openTask = Task {
            defer { if openingToken == token { openingID = nil; openTask = nil } }
            do {
                // Persist after presentation: opening one video must not wait for
                // a full library snapshot to be decoded and published.
                let current = try await telegram.prepare(video, persist: false)
                try Task.checkCancellation()
                let url: URL
                if current.isDownloaded, !current.localPath.isEmpty { rangeServer.stop(); url = URL(fileURLWithPath: current.localPath) }
                else {
                    guard current.size > 0 else {
                        downloadSuggestion = current
                        playbackError = "Please download this file before playing it. Its size is not available for streaming."
                        return
                    }
                    url = try await rangeServer.open(video: current, using: telegram)
                }
                try Task.checkCancellation()
                guard openingToken == token, library.sessionID == session else { return }
                selection = PlaybackSelection(video: current, url: url, resume: resume)
                Task {
                    guard library.sessionID == session else { return }
                    if !(await library.ingest([current])), library.sessionID == session {
                        library.report("Playback opened, but its metadata could not be saved. " + library.error)
                    }
                }
            } catch is CancellationError { }
            catch { if openingToken == token { playbackError = "\(error.localizedDescription) You can try downloading this file before playing it." } }
        }
    }
    private func cancelOpening() {
        openingToken = UUID()
        openTask?.cancel(); openTask = nil
        openingID = nil
        rangeServer.stop()
    }
    private func eyebrow(_ text: String) -> some View { Text(text).font(.system(size: 9, weight: .bold)).tracking(2.3).foregroundStyle(theme.accent) }
    private func pageTitle(_ title: String, subtitle: String) -> some View { VStack(alignment: .leading, spacing: 12) { eyebrow(subtitle); Text(title).font(.system(size: 28, weight: .bold)).tracking(-0.7) }.padding(.horizontal, 24).padding(.top, 16) }
    private func sectionTitle(_ title: String, detail: String) -> some View { Text(title).font(.title3.weight(.bold)).padding(.horizontal, 20) }
    private func banner(_ message: String) -> some View { Text(message).font(.caption).foregroundStyle(.orange).frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 24).padding(.bottom, 8) }
    private var tabs: some View {
        HStack {
            tabButton(0, "Home", "house")
            tabButton(2, "Search", "magnifyingglass")
            tabButton(1, "Favorites", "heart")
            tabButton(3, "Downloads", "arrow.down.circle")
        }.padding(.horizontal, 8).padding(.vertical, 6)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 28))
            .overlay(RoundedRectangle(cornerRadius: 28).strokeBorder(theme.muted.opacity(0.15), lineWidth: 1))
            .shadow(color: .black.opacity(0.12), radius: 10, y: 4)
            .frame(maxWidth: 560)
            .padding(.horizontal, 16).padding(.top, 8).padding(.bottom, 8)
    }
    private func tabButton(_ index: Int, _ title: String, _ symbol: String) -> some View {
        Button { tab = index; collection = nil } label: {
            VStack(spacing: 4) { Image(systemName: symbol).font(.system(size: 18, weight: tab == index ? .semibold : .regular)); Text(title).font(.system(size: 10, weight: .semibold)).lineLimit(1).minimumScaleFactor(0.72) }.frame(maxWidth: .infinity, minHeight: 48).contentShape(Rectangle())
        }.accessibilityLabel(title).accessibilityAddTraits(tab == index ? .isSelected : [])
            .foregroundStyle(tab == index ? theme.accent : theme.muted).accessibilityValue(tab == index ? "Selected" : "")
    }

    @ViewBuilder private var authentication: some View {
        switch telegram.stage {
        case .credentials:
            if TelegramBuild.isStandard {
                authForm("Connect Telegram", subtitle: "Use your app credentials from my.telegram.org. You only need to enter them once.") {
                    authInput { TextField("API ID", text: $apiID).keyboardType(.numberPad) }
                    authInput { SecureField("API hash", text: $apiHash) }
                    authButton("Connect") { telegram.connect(apiID: apiID.trimmingCharacters(in: .whitespacesAndNewlines), apiHash: apiHash) }
                }
            } else { ProgressView("Connecting to Telegram…") }
        case .phone:
            authForm("Your phone number", subtitle: "Choose your country and enter the number linked to your Telegram account.") {
                PhoneNumberEntry(country: $country, number: $phone)
                authButton("Get code", action: submitAuthentication).disabled(phone.filter(\.isNumber).isEmpty)
                Text("Already signed in on another device? Telegram may send your code there instead of by SMS.").font(.footnote).foregroundStyle(theme.muted)
            }
        case .code:
            authForm("Enter your code", subtitle: telegram.codeDelivery?.message ?? "Check Telegram on a device where you’re already signed in.") {
                if let delivery = telegram.codeDelivery, !delivery.phone.isEmpty {
                    HStack { Text(delivery.phone).font(.subheadline.weight(.semibold)); Spacer(); Button("Edit number", action: telegram.changePhoneNumber).disabled(telegram.isAuthenticating) }
                }
                authInput {
                    TextField("Login code", text: $code).keyboardType(telegram.codeDelivery?.numeric == false ? .default : .numberPad).textContentType(.oneTimeCode)
                        .font(.system(size: 28, weight: .semibold, design: .monospaced)).multilineTextAlignment(.center)
                }
                authButton("Submit code", action: submitAuthentication).disabled(code.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                resendControl
            }
        case .password:
            authForm("Your cloud password", subtitle: "Two-step verification is enabled for this account. Enter your Telegram password to finish signing in.") {
                authInput { SecureField("Cloud password", text: $password).textContentType(.password).submitLabel(.go).onSubmit(submitAuthentication) }
                if !telegram.passwordHint.isEmpty { Text("Hint: " + telegram.passwordHint).font(.footnote).foregroundStyle(theme.muted) }
                authButton("Log in", action: submitAuthentication).disabled(password.isEmpty)
            }
        case .email:
            authForm("Your sign-in email", subtitle: "Telegram needs an email address to continue this login.") {
                authInput { TextField("Email address", text: $email).keyboardType(.emailAddress).textContentType(.emailAddress).submitLabel(.go).onSubmit(submitAuthentication) }
                authButton("Get code", action: submitAuthentication).disabled(email.isEmpty)
            }
        case .emailCode:
            authForm("Check your email", subtitle: telegram.emailCodeDescription) {
                authInput { TextField("Email code", text: $emailCode).keyboardType(.numberPad).textContentType(.oneTimeCode).font(.system(size: 28, weight: .semibold, design: .monospaced)).multilineTextAlignment(.center) }
                authButton("Submit code", action: submitAuthentication).disabled(emailCode.isEmpty)
                Button("Resend email code", action: telegram.resendCode).frame(minHeight: 44).disabled(telegram.isAuthenticating)
            }
        case .loading:
            authForm("Connecting securely", subtitle: "Preparing Telegram and checking for your saved session.") { ProgressView().tint(theme.accent).frame(maxWidth: .infinity).padding() }
        case let .error(message):
            authForm("Let’s reconnect", subtitle: message) {
                authButton("Retry connection", action: telegram.resetAuth)
                if TelegramBuild.isStandard { Button("Change API credentials", action: telegram.editAPICredentials).frame(minHeight: 44) }
            }
        case .ready: EmptyView()
        }
    }
    private var resendControl: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            let remaining = telegram.codeDelivery?.secondsUntilResend(at: context.date) ?? 0
            VStack(alignment: .leading, spacing: 8) {
                if telegram.codeDelivery?.canResend == true {
                    Button(remaining > 0 ? "Resend code in \(remaining)s" : "Resend code", action: telegram.resendCode)
                        .frame(minHeight: 44).disabled(remaining > 0 || telegram.isAuthenticating)
                } else {
                    Text("No code yet? Check your other Telegram sessions, or go back and check your phone number. Telegram hasn’t offered another delivery method yet.")
                        .font(.footnote).foregroundStyle(theme.muted)
                }
            }
        }
    }
    private func submitAuthentication() {
        guard !telegram.isAuthenticating else { return }
        switch telegram.stage {
        case .credentials: telegram.connect(apiID: apiID.trimmingCharacters(in: .whitespacesAndNewlines), apiHash: apiHash)
        case .phone:
            let dial = DialingCountry.all.first { $0.id == country }?.dial ?? "+1"
            let number = TelegramPhoneNumber.international(phone, dial: dial)
            if !number.isEmpty { telegram.submitPhone(number) }
        case .code: if !code.isEmpty { telegram.submitCode(code.trimmingCharacters(in: .whitespacesAndNewlines)) }
        case .password: if !password.isEmpty { telegram.submitPassword(password) }
        case .email: if !email.isEmpty { telegram.submitEmail(email.trimmingCharacters(in: .whitespacesAndNewlines)) }
        case .emailCode: if !emailCode.isEmpty { telegram.submitEmailCode(emailCode.trimmingCharacters(in: .whitespacesAndNewlines)) }
        default: break
        }
    }
    private func authInput<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        content().textFieldStyle(.plain).padding(16).frame(minHeight: 56)
            .background(theme.surface, in: RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(theme.muted.opacity(0.2), lineWidth: 1))
            .disabled(telegram.isAuthenticating)
    }
    private func authButton(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 10) { if telegram.isAuthenticating { ProgressView() }; Text(telegram.isAuthenticating ? "Please wait…" : title).font(.headline) }
                .frame(maxWidth: .infinity, minHeight: 40)
        }.buttonStyle(.borderedProminent).buttonBorderShape(.roundedRectangle(radius: 16)).tint(theme.accent).disabled(telegram.isAuthenticating)
    }
    private var canGoBackInLogin: Bool {
        [.code, .password, .email, .emailCode].contains(telegram.stage) || (telegram.stage == .phone && TelegramBuild.isStandard)
    }
    private func goBackInLogin() {
        if telegram.stage == .phone { telegram.editAPICredentials() } else { telegram.changePhoneNumber() }
    }
    private func authForm<Content: View>(_ title: String, subtitle: String, @ViewBuilder content: () -> Content) -> some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    HStack(spacing: 10) {
                        Image(systemName: "film").font(.title3)
                        Text("CINESPICE").font(.system(size: 16, weight: .black, design: .rounded)).tracking(3)
                    }.foregroundStyle(theme.accent).padding(.bottom, 8)
                    VStack(alignment: .leading, spacing: 12) {
                        Text(title).font(.system(size: 30, weight: .bold, design: .rounded)).tracking(-0.6)
                        Text(subtitle).font(.subheadline).foregroundStyle(theme.muted).lineSpacing(4).fixedSize(horizontal: false, vertical: true)
                    }
                    VStack(alignment: .leading, spacing: 16) { content() }
                        .textInputAutocapitalization(.never).disableAutocorrection(true)
                    if !telegram.status.isEmpty {
                        Label(telegram.status, systemImage: "info.circle").font(.footnote).foregroundStyle(theme.accent)
                    }
                    Label("Your session stays on this device.", systemImage: "lock.shield").font(.footnote).foregroundStyle(theme.muted).padding(.top, 8)
                }.padding(24).frame(maxWidth: 440).frame(maxWidth: .infinity)
            }.background(theme.background).navigationBarTitleDisplayMode(.inline).toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    if canGoBackInLogin { Button(action: goBackInLogin) { Label("Back", systemImage: "chevron.left").frame(minHeight: 44) }.disabled(telegram.isAuthenticating) }
                }
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button("Enter", action: submitAuthentication).disabled(telegram.isAuthenticating)
                }
            }
        }.navigationViewStyle(.stack)
    }
}
