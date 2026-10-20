import Foundation
import Combine
import TDLibFramework

@MainActor
final class TelegramClient: ObservableObject {
    enum Stage: Equatable { case credentials, phone, code, password, email, emailCode, loading, ready, error(String) }
    @Published private(set) var stage: Stage = .credentials
    @Published private(set) var status = ""
    @Published private(set) var isLoadingChats = false
    @Published private(set) var hasMoreChats = true
    @Published private(set) var loadingCollections: Set<Int64> = []
    @Published private(set) var collectionErrors: [Int64: String] = [:]
    @Published private(set) var discoveryError = ""
    @Published private(set) var moreCollections: Set<Int64> = []
    @Published private(set) var isClearingCache = false
    @Published private(set) var profileName = ""
    @Published private(set) var profileUsername = ""
    let library: LibraryStore
    private let databaseDirectory: URL

    private var clientID = td_create_client_id()
    private let receiver = DispatchQueue(label: "app.tevee.tdlib.receive", qos: .userInitiated)
    private var running = false
    private let rangeReader = TelegramRangeReader()
    private var rangeWindows: [Int32: Range<Int64>] = [:]
    private var requestTimeouts: [Int: Task<Void, Never>] = [:]
    private var completions: [Int: (Result<[String: Any], Error>) -> Void] = [:]
    private var requestID = 1
    private var apiID = 0
    private var apiHash = ""
    private var restored = false
    private var startingAccount = false
    private var loggingOut = false
    private var accountSession = UUID()
    private var pendingDiscoveryPage: TelegramDiscovery.Page?
    private var discovery: TelegramDiscovery?
    private var mediaQueue: [(id: Int64, refresh: Bool)] = []
    private var mediaWorker: Task<Void, Never>?
    @Published private(set) var isSearchingChats = false
    @Published private(set) var chatSearchError = ""
    private var searchGeneration = UUID()
    private var thumbnailRequests: [String: Task<Data, Error>] = [:]
    @Published private(set) var fullDownloads: Set<Int32> = []
    @Published private(set) var preparingDownloads: Set<String> = []
    @Published private(set) var cancellingDownloads: Set<Int32> = []
    @Published private(set) var isAuthenticating = false
    private var authenticationRequest = UUID()
    private var loginStartup = TelegramLoginStartup()
    private var lastAuthorizationType = ""
    @Published private(set) var codeDelivery: TelegramCodeDelivery?
    @Published private(set) var emailCodeDescription = "Check your email for the verification code from Telegram."
    @Published private(set) var passwordHint = ""
    private var editingCredentials = false

    @Published private(set) var videoSearchResults: [TelegramVideo] = []
    @Published private(set) var isSearchingVideos = false
    @Published private(set) var hasMoreVideoResults = false
    @Published private(set) var videoSearchError = ""
    private var videoSearch: TelegramVideoSearch?
    private var videoSearchGeneration = UUID()

    /// While the picker is visible, gradually discover all sources without loading messages.
    func discoverCollections() async {
        while !Task.isCancelled, stage == .ready, hasMoreChats {
            loadMoreChats()
            while isLoadingChats, !Task.isCancelled {
                do { try await Task.sleep(nanoseconds: 100_000_000) } catch { return }
            }
            guard discoveryError.isEmpty, library.error.isEmpty else { return }
            do { try await Task.sleep(nanoseconds: 350_000_000) } catch { return }
        }
    }

    func searchVideos(_ query: String, in chatID: Int64? = nil) async {
        let generation = UUID(); videoSearchGeneration = generation
        videoSearchResults = []; videoSearchError = ""; hasMoreVideoResults = false
        videoSearch = nil; isSearchingVideos = false
        let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, stage == .ready else { return }
        let session = accountSession
        let chatIDs = chatID.map { [$0] } ?? library.selectedCollections.map(\.id)
        let search = TelegramVideoSearch(chatIDs: chatIDs, query: text) { [weak self] request in
            guard let self else { throw CancellationError() }
            return try await self.sessionRequest(request, session: session)
        }
        videoSearch = search
        do { try await Task.sleep(nanoseconds: 350_000_000) } catch { return }
        guard videoSearchGeneration == generation else { return }
        await nextVideoSearchPage()
    }

    func nextVideoSearchPage() async {
        guard let videoSearch, !isSearchingVideos, stage == .ready else { return }
        let token = videoSearchGeneration; let session = accountSession
        isSearchingVideos = true; videoSearchError = ""
        defer { if videoSearchGeneration == token { isSearchingVideos = false } }
        do {
            let page = try await videoSearch.next()
            try Task.checkCancellation()
            guard token == videoSearchGeneration, session == accountSession else { return }
            let videos = page.videos.filter { library.snapshot.selected.contains($0.chatID) }
            var seen = Set(videoSearchResults.map(\.id))
            videoSearchResults += videos.filter { seen.insert($0.id).inserted }
            hasMoreVideoResults = page.hasMore
            _ = await library.ingest(videos)
        } catch is CancellationError { }
        catch { if token == videoSearchGeneration { videoSearchError = error.localizedDescription } }
    }

    init(library: LibraryStore, databaseDirectory: URL? = nil) {
        self.library = library
        self.databaseDirectory = databaseDirectory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Tevee/TDLib", isDirectory: true)
    }

    func restoreSession() {
        guard !restored else { return }; restored = true
        do {
            if !TelegramBuild.isStandard {
                guard let build = TelegramBuild.configuration, let id = build.apiID, let hash = build.apiHash else {
                    stage = .error("This build is missing its Telegram configuration. Please install a configured build."); return
                }
                connect(apiID: id, apiHash: hash)
                return
            }
            guard let data = try SecureStore.read("telegram_api_credentials") else { return }
            let credentials = try JSONDecoder().decode(TelegramCredentials.self, from: data)
            connect(apiID: credentials.apiID, apiHash: credentials.apiHash)
        } catch { stage = .error("Could not restore saved credentials: \(error.localizedDescription)") }
    }

    func connect(apiID: String, apiHash: String) {
        let hash = apiHash.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let value = Int32(apiID), value > 0, hash.count == 32, hash.allSatisfy({ $0.isHexDigit }) else {
            stage = .error("Enter a valid Telegram API ID and 32-character API hash."); return
        }
        do { try SecureStore.save(JSONEncoder().encode(TelegramCredentials(apiID: String(value), apiHash: hash)), key: "telegram_api_credentials") }
        catch { stage = .error(error.localizedDescription); return }
        self.apiID = Int(value); self.apiHash = hash; stage = .loading; status = ""
        startReceiver()
        if lastAuthorizationType == "authorizationStateWaitTdlibParameters" { configure() }
        refreshAuthorizationState()
    }

    private func refreshAuthorizationState() {
        let client = clientID
        let revision = loginStartup.revision
        send(["@type": "getAuthorizationState"]) { [weak self] result in
            guard let self, self.clientID == client else { return }
            switch result {
            case .success(let state):
                // Updates are authoritative; an older snapshot must not rewind login.
                if self.loginStartup.acceptsSnapshot(since: revision) { self.handle(state) }
            case .failure(let error):
                if self.loginStartup.acceptsSnapshot(since: revision) { self.stage = .error(error.localizedDescription) }
            }
        }
    }

    func submitPhone(_ phone: String) { submitAuthentication(["@type": "setAuthenticationPhoneNumber", "phone_number": phone], refreshState: true) }
    func submitCode(_ code: String) { submitAuthentication(["@type": "checkAuthenticationCode", "code": code]) }
    func submitPassword(_ password: String) { submitAuthentication(["@type": "checkAuthenticationPassword", "password": password]) }
    func submitEmail(_ email: String) { submitAuthentication(["@type": "setAuthenticationEmailAddress", "email_address": email]) }
    func submitEmailCode(_ code: String) { submitAuthentication(["@type": "checkAuthenticationEmailCode", "code": ["@type": "emailAddressAuthenticationCode", "code": code]]) }
    private func submitAuthentication(_ request: [String: Any], refreshState: Bool = false) {
        guard !isAuthenticating else { return }
        isAuthenticating = true; status = "Checking with Telegram…"
        let session = accountSession
        let token = UUID(); authenticationRequest = token
        send(request) { [weak self] result in
            guard let self, self.accountSession == session, self.authenticationRequest == token else { return }
            self.isAuthenticating = false
            switch result {
            case .success:
                self.status = ""
                if refreshState { self.refreshAuthorizationState() }
            case .failure(let error): self.status = error.localizedDescription
            }
        }
    }
    func changePhoneNumber() {
        guard !isAuthenticating, [.code, .password, .email, .emailCode].contains(stage) else { return }
        stage = .phone; status = ""
    }
    func resendCode() {
        guard stage == .emailCode || (stage == .code && codeDelivery?.canResend == true && codeDelivery!.secondsUntilResend(at: Date()) == 0) else { return }
        submitAuthentication(["@type": "resendAuthenticationCode"], refreshState: true)
    }
    func resetAuth() {
        guard !isAuthenticating else { return }
        status = ""
        guard apiID > 0 else { restored = false; stage = .credentials; restoreSession(); return }
        // Re-open the client; preserve its database, encryption key and saved session.
        stage = .loading
        send(["@type": "close"]) { [weak self] result in
            if case let .failure(error) = result, !(error is CancellationError) { self?.stage = .error(error.localizedDescription) }
        }
    }
    func editAPICredentials() {
        guard TelegramBuild.isStandard, !isAuthenticating else { return }
        if !running { stage = .credentials; status = ""; return }
        editingCredentials = true; stage = .loading
        send(["@type": "close"]) { [weak self] result in
            if case let .failure(error) = result, !(error is CancellationError) { self?.editingCredentials = false; self?.stage = .error(error.localizedDescription) }
        }
    }

    func refreshChats() { loadMoreChats() }
    func loadMoreChats() {
        guard stage == .ready, !isLoadingChats, let discovery else { return }
        isLoadingChats = true; status = ""; discoveryError = ""
        let session = accountSession
        Task {
            defer { if accountSession == session { isLoadingChats = false } }
            do {
                let page: TelegramDiscovery.Page
                if let pendingDiscoveryPage { page = pendingDiscoveryPage }
                else { page = try await discovery.next(cached: library.snapshot.collections, accountID: library.accountID) }
                guard accountSession == session, stage == .ready else { return }
                pendingDiscoveryPage = page
                let saved = await library.register(page.chats)
                guard accountSession == session else { return }
                guard saved else { status = library.error; return }
                pendingDiscoveryPage = nil; hasMoreChats = page.hasMore
            } catch { if accountSession == session { discoveryError = "Could not find channels: \(error.localizedDescription)"; status = discoveryError } }
        }
    }

    func searchCollections(_ query: String, channelsOnly: Bool = true) async {
        let token = UUID(); searchGeneration = token
        chatSearchError = ""
        guard stage == .ready, !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, let discovery else { isSearchingChats = false; return }
        isSearchingChats = true
        let session = accountSession
        defer { if searchGeneration == token { isSearchingChats = false } }
        do {
            try await Task.sleep(nanoseconds: 400_000_000)
            let chats = try await discovery.search(query, accountID: library.accountID, channelsOnly: channelsOnly)
            try Task.checkCancellation()
            guard accountSession == session, searchGeneration == token else { return }
            _ = await library.register(chats)
        } catch is CancellationError { }
        catch { if accountSession == session, searchGeneration == token { chatSearchError = "Channel search failed: \(error.localizedDescription)" } }
    }

    /// Opening a cached collection never advances its remote cursor.
    func ensureChat(_ id: Int64) {
        if let index = library.snapshot.indexes[id] {
            if index.hasMore { moreCollections.insert(id) }
            return
        }
        loadChat(id)
    }

    func loadChat(_ id: Int64, refresh: Bool = false) {
        guard stage == .ready, library.snapshot.selected.contains(id), !loadingCollections.contains(id) else { return }
        if !refresh, library.snapshot.indexes[id]?.hasMore == false { return }
        collectionErrors[id] = nil
        loadingCollections.insert(id)
        mediaQueue.append((id, refresh))
        guard mediaWorker == nil else { return }
        let session = accountSession
        mediaWorker = Task {
            defer { if accountSession == session { mediaWorker = nil } }
            while !mediaQueue.isEmpty, accountSession == session, stage == .ready {
                let job = mediaQueue.removeFirst()
                await loadPage(job.id, refresh: job.refresh, session: session)
                if accountSession == session { loadingCollections.remove(job.id) }
            }
        }
    }

    private func loadPage(_ id: Int64, refresh: Bool, session: UUID) async {
        let existing = library.snapshot.indexes[id]
        let requests = refresh ? CollectionIndex().cursors : (existing ?? CollectionIndex()).cursors
        for filter in CollectionIndex.filters {
            guard let cursor = requests[filter], accountSession == session, stage == .ready, library.snapshot.selected.contains(id) else { continue }
            do {
                let data = try await requestJSON(["@type": "searchChatMessages", "chat_id": id, "query": "", "from_message_id": cursor,
                    "offset": 0, "limit": CollectionIndex.pageLimit, "filter": ["@type": filter]])
                guard accountSession == session, stage == .ready, library.snapshot.selected.contains(id) else { return }
                let videos = (data["messages"] as? [[String: Any]] ?? []).compactMap { TelegramVideo.from(message: $0, chatID: id) }
                var index = library.snapshot.indexes[id] ?? CollectionIndex()
                // Refresh the newest page without losing the saved position for older pages.
                if !refresh || existing == nil { index.advance(filter: filter, from: cursor, next: data["next_from_message_id"] as? Int64 ?? 0) }
                let saved = await library.savePage(videos, chatID: id, index: index)
                guard accountSession == session else { return }
                guard saved else { collectionErrors[id] = library.error; status = library.error; return }
            } catch { if accountSession == session { collectionErrors[id] = "Could not load videos: \(error.localizedDescription)"; status = collectionErrors[id] ?? "" }; return }
        }
        guard accountSession == session else { return }
        if library.snapshot.indexes[id]?.hasMore == true { moreCollections.insert(id) } else { moreCollections.remove(id) }
    }

    func refreshLibrary() {
        if let first = library.selectedCollections.first { loadChat(first.id, refresh: true) }
    }

    private func sessionRequest(_ request: [String: Any], session: UUID) async throws -> [String: Any] {
        guard accountSession == session, stage == .ready else { throw CancellationError() }
        let response = try await requestJSON(request)
        guard accountSession == session, stage == .ready else { throw CancellationError() }
        return response
    }

    /// Refresh the actual message and TDLib file state before trusting a saved path.
    func prepare(_ saved: TelegramVideo, persist: Bool = true) async throws -> TelegramVideo {
        guard stage == .ready, !isClearingCache else { throw URLError(.userAuthenticationRequired) }
        let session = accountSession
        let message = try await requestJSON(["@type": "getMessage", "chat_id": saved.chatID, "message_id": saved.messageID])
        guard accountSession == session, stage == .ready else { throw CancellationError() }
        try Task.checkCancellation()
        guard let current = TelegramVideo.from(message: message, chatID: saved.chatID) else {
            throw NSError(domain: "Tevee.Media", code: 1, userInfo: [NSLocalizedDescriptionKey: "This message no longer contains an accessible video."])
        }
        // getMessage already includes TDLib's current File state. A second
        // getFile request adds a serial round trip without refreshing the message.
        guard accountSession == session, stage == .ready, !isClearingCache else { throw CancellationError() }
        if persist {
            guard await library.ingest([current]) else {
                throw NSError(domain: "Tevee.Library", code: 1, userInfo: [NSLocalizedDescriptionKey: library.error])
            }
        }
        try Task.checkCancellation()
        guard accountSession == session, stage == .ready else { throw CancellationError() }
        if fullDownloads.contains(current.fileID), !current.isDownloaded {
            throw NSError(domain: "Tevee.Download", code: 1, userInfo: [NSLocalizedDescriptionKey: "Download is still in progress. Wait for the downloaded badge, then play again."])
        }
        return current
    }

    func download(_ video: TelegramVideo) {
        guard stage == .ready, !isClearingCache, !fullDownloads.contains(video.fileID), !preparingDownloads.contains(video.id) else { return }
        preparingDownloads.insert(video.id)
        status = "Preparing download…"
        let session = accountSession
        Task {
            defer { if accountSession == session { preparingDownloads.remove(video.id) } }
            var requestedID: Int32?
            do {
                let current = try await prepare(video)
                requestedID = current.fileID
                rangeWindows.removeValue(forKey: current.fileID)
                fullDownloads.insert(current.fileID)
                status = "Downloading. Play becomes available when the file is complete."
                let file = try await requestJSON(["@type": "downloadFile", "file_id": current.fileID, "priority": 32, "offset": 0, "limit": 0, "synchronous": false])
                guard accountSession == session, stage == .ready else { return }
                updateFile(file)
            } catch {
                if accountSession == session {
                    if let requestedID { fullDownloads.remove(requestedID) }
                    status = "Could not download: \(error.localizedDescription)"
                }
            }
        }
    }

    func cancelDownload(_ video: TelegramVideo) async {
        guard !cancellingDownloads.contains(video.fileID) else { return }
        cancellingDownloads.insert(video.fileID)
        status = "Cancelling download…"
        let session = accountSession
        defer { if accountSession == session { cancellingDownloads.remove(video.fileID) } }
        do {
            _ = try await requestJSON(["@type": "cancelDownloadFile", "file_id": video.fileID, "only_if_pending": false])
            guard accountSession == session else { return }
            rangeWindows.removeValue(forKey: video.fileID)
            fullDownloads.remove(video.fileID); status = "Download cancelled. You can retry or stream the video."
        } catch { if accountSession == session { status = "Could not cancel download: \(error.localizedDescription)" } }
    }

    func thumbnail(_ video: TelegramVideo) async throws -> Data? {
        guard stage == .ready, !isClearingCache, let cache = library.thumbnails else { return video.minithumbnail }
        let session = accountSession
        let cached = try await cache.read(video.thumbnailKey)
        guard accountSession == session, stage == .ready, !isClearingCache else { throw CancellationError() }
        if let cached { return cached }
        guard video.thumbnailFileID != nil else { return video.minithumbnail }
        let key = video.thumbnailKey
        let requestKey = "\(session.uuidString)_\(key)"
        if let existing = thumbnailRequests[requestKey] { return try await existing.value }
        let task = Task<Data, Error> {
            let fileID: Int32
            if let remoteID = video.thumbnailRemoteID, !remoteID.isEmpty {
                let file = try await requestJSON(["@type": "getRemoteFile", "remote_file_id": remoteID, "file_type": ["@type": "fileTypeThumbnail"]])
                guard let id = file["id"] as? Int32, (file["size"] as? Int64 ?? 0) <= 2 * 1024 * 1024 else { throw URLError(.cannotDecodeContentData) }
                fileID = id
            } else {
                // Legacy rows only contain session-local IDs. Refresh the message
                // before downloading so an ID reused after logout cannot fetch a video.
                let message = try await requestJSON(["@type": "getMessage", "chat_id": video.chatID, "message_id": video.messageID])
                guard let current = TelegramVideo.from(message: message, chatID: video.chatID), let id = current.thumbnailFileID else { throw URLError(.fileDoesNotExist) }
                guard accountSession == session else { throw CancellationError() }
                _ = await library.ingest([current])
                fileID = id
            }
            guard accountSession == session, stage == .ready, !isClearingCache else { throw CancellationError() }
            let result = try await requestJSON(["@type": "downloadFile", "file_id": fileID, "priority": 1, "offset": 0, "limit": 0, "synchronous": true])
            guard accountSession == session, stage == .ready, !isClearingCache else { throw CancellationError() }
            guard (result["local"] as? [String: Any])?["is_downloading_completed"] as? Bool == true,
                  let size = result["size"] as? Int64, size > 0, size <= 2 * 1024 * 1024 else { throw URLError(.cannotDecodeContentData) }
            let response = try await requestJSON(["@type": "readFilePart", "file_id": fileID, "offset": 0, "count": size])
            guard let encoded = response["data"] as? String else { throw URLError(.cannotDecodeContentData) }
            let data = try await Task.detached(priority: .utility) {
                guard let bytes = Data(base64Encoded: encoded), bytes.count == size else { throw URLError(.cannotDecodeContentData) }
                return bytes
            }.value
            try Task.checkCancellation()
            guard accountSession == session, stage == .ready, !isClearingCache else { throw CancellationError() }
            try await cache.write(data, key: key)
            return data
        }
        thumbnailRequests[requestKey] = task
        defer { thumbnailRequests.removeValue(forKey: requestKey) }
        return try await task.value
    }

    func storageBytes() async throws -> Int64 {
        let stats = try await requestJSON(["@type": "getStorageStatisticsFast"])
        return stats["files_size"] as? Int64 ?? 0
    }

    /// Only TDLib local-cache APIs are called. No cloud-message deletion request.
    func clearMediaCache() async throws {
        guard !isClearingCache else { return }
        let session = accountSession
        isClearingCache = true
        defer { if accountSession == session { isClearingCache = false } }
        let pending = Array(thumbnailRequests.values)
        pending.forEach { $0.cancel() }
        for task in pending { _ = try? await task.value }
        for file in fullDownloads { _ = try await requestJSON(["@type": "cancelDownloadFile", "file_id": file, "only_if_pending": false]) }
        guard accountSession == session, stage == .ready else { throw CancellationError() }
        fullDownloads.removeAll(); rangeWindows.removeAll()
        _ = try await requestJSON(["@type": "optimizeStorage", "size": 0, "ttl": 0, "count": 0, "immunity_delay": 0,
            "file_types": [["@type": "fileTypeVideo"], ["@type": "fileTypeDocument"], ["@type": "fileTypeThumbnail"]],
            "chat_ids": [], "exclude_chat_ids": [], "return_deleted_file_statistics": false, "chat_limit": 0])
        guard accountSession == session, stage == .ready else { throw CancellationError() }
        guard await library.invalidateMediaCache() else {
            throw NSError(domain: "Tevee.Library", code: 1, userInfo: [NSLocalizedDescriptionKey: library.error])
        }
        status = "Downloaded media cleared. Your cloud videos and watch history are kept."
    }

    func logOut() {
        guard stage == .ready else { return }
        loggingOut = true; stage = .loading
        let session = accountSession
        send(["@type": "logOut"]) { [weak self] result in
            guard let self, self.accountSession == session else { return }
            if case let .failure(error) = result { self.loggingOut = false; self.stage = .ready; self.status = error.localizedDescription }
        }
    }
    func readRange(video: TelegramVideo, offset: Int64, length: Int) async throws -> Data {
        guard length > 0, offset >= 0, offset < video.size else { return Data() }
        guard stage == .ready, !isClearingCache else { throw CancellationError() }
        let session = accountSession
        let count = Int(min(Int64(length), video.size - offset))
        return try await rangeReader.read(fileID: video.fileID) { [weak self] in
            guard let self else { throw CancellationError() }
            return try await self.fetchRange(fileID: video.fileID, offset: offset, count: count, size: video.size, session: session)
        }
    }

    private func fetchRange(fileID: Int32, offset: Int64, count: Int, size: Int64, session: UUID) async throws -> Data {
        let deadline = Date().addingTimeInterval(45)
        var requested = false
        while Date() < deadline {
            try Task.checkCancellation()
            guard accountSession == session, stage == .ready, !isClearingCache else { throw CancellationError() }
            let prefix = try await requestJSON(["@type": "getFileDownloadedPrefixSize", "file_id": fileID, "offset": offset])
            try Task.checkCancellation()
            guard accountSession == session, stage == .ready, !isClearingCache else { throw CancellationError() }
            if let available = prefix["size"] as? Int64, available >= Int64(count) {
                let result = try await requestJSON(["@type": "readFilePart", "file_id": fileID, "offset": offset, "count": count])
                guard accountSession == session, stage == .ready else { throw CancellationError() }
                guard let encoded = result["data"] as? String else { throw URLError(.cannotDecodeContentData) }
                // TDLib performs cache I/O on its own workers. Decode the JSON
                // bytes off the main actor too; never infer validity from disk size.
                return try await Task.detached(priority: .userInitiated) {
                    guard let data = Data(base64Encoded: encoded), data.count == count else {
                        throw URLError(.cannotDecodeContentData)
                    }
                    return data
                }.value
            }
            if !requested {
                var reuse = false
                if let window = rangeWindows[fileID], window.lowerBound <= offset, window.upperBound >= offset + Int64(count) {
                    let file = try await requestJSON(["@type": "getFile", "file_id": fileID])
                    try Task.checkCancellation()
                    guard accountSession == session, stage == .ready, !isClearingCache else { throw CancellationError() }
                    reuse = (file["local"] as? [String: Any])?["is_downloading_active"] as? Bool == true
                }
                if !reuse {
                    let ahead = min(size - offset, max(Int64(count), 8 * 1024 * 1024))
                    _ = try await requestJSON(["@type": "downloadFile", "file_id": fileID, "priority": 32,
                                               "offset": offset, "limit": ahead, "synchronous": false])
                    guard accountSession == session else { throw CancellationError() }
                    rangeWindows[fileID] = offset..<(offset + ahead)
                }
                requested = true
            }
            try await Task.sleep(nanoseconds: 250_000_000)
        }
        throw URLError(.timedOut)
    }

    private func requestJSON(_ request: [String: Any]) async throws -> [String: Any] {
        try Task.checkCancellation()
        return try await withCheckedThrowingContinuation { continuation in send(request) { continuation.resume(with: $0) } }
    }

    private func startReceiver() {
        guard !running else { return }; running = true
        receiver.async { [weak self] in
            while self != nil {
                guard let pointer = td_receive(1.0) else { continue }
                let text = String(cString: pointer)
                // readFilePart responses contain large base64 strings. Parse their
                // JSON on the receive worker so larger reads do not block controls.
                let parsed = Result { try Self.parse(text) }
                DispatchQueue.main.async { [weak self] in
                    guard let self else { return }
                    switch parsed {
                    case .success(let value): self.handle(value)
                    case .failure(let error): self.status = "Could not decode Telegram's response: \(error.localizedDescription)"
                    }
                }
            }
        }
    }

    private func updateFile(_ file: [String: Any]) {
        library.updateFile(file)
        if let state = LocalFileStatus(file), state.complete, fullDownloads.remove(state.id) != nil {
            status = "Download complete. Open the video to play the local file."
        }
    }

    private func handle(_ value: [String: Any]) {
        if let client = value["@client_id"] as? Int32, client != clientID { return }
        if let id = value["@extra"] as? Int, let completion = completions.removeValue(forKey: id) {
            requestTimeouts.removeValue(forKey: id)?.cancel()
            if value["@type"] as? String == "error" {
                completion(.failure(NSError(domain: "TDLib", code: value["code"] as? Int ?? -1, userInfo: [NSLocalizedDescriptionKey: value["message"] as? String ?? "Telegram error"])))
            } else { completion(.success(value)) }
            return
        }
        if value["@type"] as? String == "updateFile", let file = value["file"] as? [String: Any] { updateFile(file); return }
        if value["@type"] as? String == "error" { status = value["message"] as? String ?? "Telegram request failed"; return }
        let responseType = value["@type"] as? String ?? ""
        if responseType == "updateAuthorizationState" { loginStartup.observeUpdate() }
        let auth = responseType == "updateAuthorizationState" ? value["authorization_state"] as? [String: Any] ?? [:] : value
        let type = auth["@type"] as? String
        if let type, type.hasPrefix("authorizationState") { lastAuthorizationType = type }
        switch type {
        case "authorizationStateWaitTdlibParameters": configure()
        case "authorizationStateWaitPhoneNumber": stage = .phone
        case "authorizationStateWaitCode":
            codeDelivery = TelegramCodeDelivery(auth["code_info"] as? [String: Any] ?? [:]); stage = .code
        case "authorizationStateWaitPassword": passwordHint = auth["password_hint"] as? String ?? ""; stage = .password
        case "authorizationStateWaitEmailAddress": stage = .email
        case "authorizationStateWaitEmailCode":
            let pattern = (auth["code_info"] as? [String: Any])?["email_address_pattern"] as? String ?? "your email"
            emailCodeDescription = "Enter the verification code Telegram sent to " + pattern + "."; stage = .emailCode
        case "authorizationStateReady": startAccount()
        case "authorizationStateWaitOtherDeviceConfirmation": stage = .error("Telegram requires confirmation in another device. Use phone login again or confirm in your existing Telegram app.")
        case "authorizationStateClosed": finishClosedClient()
        default: break
        }
    }

    private func startAccount() {
        guard !startingAccount, !loggingOut, stage != .ready else { return }; startingAccount = true
        let session = accountSession
        Task {
            defer { if accountSession == session { startingAccount = false } }
            do {
                let me = try await requestJSON(["@type": "getMe"])
                guard let id = me["id"] as? Int64 else { throw URLError(.cannotParseResponse) }
                guard accountSession == session else { return }
                try await library.activate(accountID: id)
                guard accountSession == session else { return }
                let data = try await requestJSON(["@type": "createPrivateChat", "user_id": id, "force": false])
                guard accountSession == session else { return }
                if let chat = TelegramChat.from(data, accountID: id) {
                    guard await library.register(chat, savedMessages: false) else {
                        throw NSError(domain: "Tevee.Library", code: 1, userInfo: [NSLocalizedDescriptionKey: library.error])
                    }
                }
                guard accountSession == session else { return }
                profileName = [me["first_name"] as? String, me["last_name"] as? String].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " ")
                profileUsername = ((me["usernames"] as? [String: Any])?["active_usernames"] as? [String])?.first ?? ""
                stage = .ready
                discovery = TelegramDiscovery { [weak self] request in
                    guard let self else { throw CancellationError() }
                    return try await self.sessionRequest(request, session: session)
                }
                // Cached library is immediately available. Discovery and indexing are
                // started explicitly by the channel picker/opened collection only.
            } catch { if accountSession == session { stage = .error("Could not restore your library: \(error.localizedDescription)") } }
        }
    }

    private func finishClosedClient() {
        accountSession = UUID(); library.deactivate(); profileName = ""; profileUsername = ""
        for task in thumbnailRequests.values { task.cancel() }
        thumbnailRequests.removeAll(); loadingCollections.removeAll(); moreCollections.removeAll()
        isLoadingChats = false; collectionErrors = [:]; discoveryError = ""; isClearingCache = false; startingAccount = false
        fullDownloads.removeAll(); preparingDownloads.removeAll(); cancellingDownloads.removeAll(); rangeWindows.removeAll(); isAuthenticating = false
        videoSearchGeneration = UUID(); videoSearch = nil; videoSearchResults = []; isSearchingVideos = false; hasMoreVideoResults = false
        discovery = nil; pendingDiscoveryPage = nil; mediaWorker?.cancel(); mediaWorker = nil; mediaQueue.removeAll()
        searchGeneration = UUID(); isSearchingChats = false; chatSearchError = ""; hasMoreChats = true
        for task in requestTimeouts.values { task.cancel() }
        requestTimeouts.removeAll()
        let callbacks = Array(completions.values); completions.removeAll()
        callbacks.forEach { $0(.failure(CancellationError())) }
        clientID = td_create_client_id(); loginStartup = TelegramLoginStartup(); lastAuthorizationType = ""
        codeDelivery = nil; passwordHint = ""
        if loggingOut {
            loggingOut = false; apiID = 0; apiHash = ""
            do { try SecureStore.remove("telegram_api_credentials") }
            catch { stage = .error(error.localizedDescription); return }
        }
        stage = .credentials
        if editingCredentials { editingCredentials = false; apiID = 0; apiHash = ""; status = "" }
        else { restored = false; restoreSession() }
    }

    private func configure() {
        guard apiID > 0, loginStartup.beginParameters() else { return }
        let client = clientID
        let root = databaseDirectory
        let files = root.appendingPathComponent("files", isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: files, withIntermediateDirectories: true)
            let key = try SecureStore.databaseKey()
            send(["@type": "setTdlibParameters", "database_directory": root.path, "files_directory": files.path,
                "database_encryption_key": key.base64EncodedString(), "use_file_database": true, "use_chat_info_database": true,
                "use_message_database": true, "use_secret_chats": false, "api_id": apiID, "api_hash": apiHash,
                "system_language_code": Locale.current.languageCode ?? "en", "device_model": "iPhone",
                "system_version": ProcessInfo.processInfo.operatingSystemVersionString, "application_version": "1.1"] ) { [weak self] result in
                    guard let self, self.clientID == client else { return }
                    if case let .failure(error) = result {
                        self.loginStartup.parametersFailed()
                        self.stage = .error("Telegram setup failed: \(error.localizedDescription)")
                    }
                }
        } catch { loginStartup.parametersFailed(); stage = .error("Could not prepare secure storage: \(error.localizedDescription)") }
    }
    private func send(_ request: [String: Any], completion: ((Result<[String: Any], Error>) -> Void)? = nil) {
        var request = request
        let id = requestID
        requestID += 1
        request["@extra"] = id
        do {
            let data = try JSONSerialization.data(withJSONObject: request)
            guard let text = String(data: data, encoding: .utf8) else { throw CocoaError(.propertyListWriteInvalid) }
            if let completion {
                completions[id] = completion
                requestTimeouts[id] = Task { [weak self] in
                    do { try await Task.sleep(nanoseconds: 30_000_000_000) }
                    catch { return }
                    guard let self else { return }
                    self.requestTimeouts.removeValue(forKey: id)
                    self.completions.removeValue(forKey: id)?(.failure(URLError(.timedOut)))
                }
            }
            text.withCString { td_send(clientID, $0) }
        } catch {
            if let completion { completion(.failure(error)) }
            else { status = "Telegram request failed: \(error.localizedDescription)" }
        }
    }

    nonisolated private static func parse(_ text: String) throws -> [String: Any] {
        guard let value = try JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any] else {
            throw CocoaError(.propertyListReadCorrupt)
        }
        return value
    }
}
