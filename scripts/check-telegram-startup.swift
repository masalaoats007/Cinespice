// Run the app's actual TelegramClient with the pinned native TDLib, without a
// phone number, real API credentials, Keychain changes, or an account login.
import Foundation

struct TelegramCredentials: Codable { let apiID: String; let apiHash: String }
enum TelegramBuild {
    struct Configuration { let apiID: String?; let apiHash: String? }
    static var isStandard = true
    static let configuration: Configuration? = Configuration(apiID: "12345", apiHash: String(repeating: "0123456789abcdef", count: 2))
}
enum SecureStore {
    static var credentials: Data?
    static func read(_ key: String) throws -> Data? { credentials }
    static func save(_ data: Data, key: String) throws { credentials = data }
    static func remove(_ key: String) throws { credentials = nil }
    static func databaseKey() throws -> Data { Data(repeating: 0x5a, count: 32) }
}

@main struct NativeTelegramStartupCheck {
    @MainActor static func waitFor(_ expected: TelegramClient.Stage, client: TelegramClient) async throws {
        let deadline = Date().addingTimeInterval(40)
        while Date() < deadline {
            if case let .error(message) = client.stage { throw failure(message) }
            if client.stage == expected { return }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        throw failure("Timed out waiting for native authorization state: \(expected); \(client.status)")
    }
    static func failure(_ message: String) -> NSError {
        NSError(domain: "NativeTelegramStartup", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
    }
    @MainActor static func main() async throws {
        guard CommandLine.arguments.count == 2 else { throw failure("Expected an isolated temporary database directory") }
        let root = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
        let client = TelegramClient(library: LibraryStore(), databaseDirectory: root)
        let configured = TelegramBuild.configuration!
        // Standard: restore a previously saved developer configuration.
        SecureStore.credentials = try JSONEncoder().encode(TelegramCredentials(apiID: configured.apiID!, apiHash: configured.apiHash!))
        client.restoreSession()
        try await waitFor(.phone, client: client)
        print("PASS: saved credentials initialize the real TDLib client and reach phone entry")
        // Regression: after the first update, a second state query used to be
        // ignored permanently, leaving this real client on the loading screen.
        client.connect(apiID: configured.apiID!, apiHash: configured.apiHash!)
        try await waitFor(.phone, client: client)
        print("PASS: reconnecting an initialized client accepts the current state snapshot")
        // Exercise the recovery path added by the login changes.
        client.editAPICredentials()
        try await waitFor(.credentials, client: client)
        print("PASS: changing app credentials closes the old client and returns to API entry")
        client.connect(apiID: configured.apiID!, apiHash: configured.apiHash!)
        try await waitFor(.phone, client: client)
        print("PASS: API entry initializes the replacement native client")
        // Configured: close/re-open and use bundled credentials through restoreSession.
        TelegramBuild.isStandard = false
        client.resetAuth()
        try await waitFor(.phone, client: client)
        print("PASS: configured restoration initializes TDLib and reaches phone entry")
        client.resetAuth()
        try await waitFor(.phone, client: client)
        print("PASS: connection retry preserves the database key and initializes once")
        print("No phone/code/password requests were sent. Live code delivery is untested.")
    }
}
