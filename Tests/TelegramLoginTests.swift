import XCTest
@testable import TeveeStreaming

final class TelegramLoginTests: XCTestCase {
    func testSnapshotAndStartupUpdateOnlySetParametersOnce() {
        var startup = TelegramLoginStartup()
        XCTAssertTrue(startup.acceptsSnapshot(since: 0))
        XCTAssertTrue(startup.beginParameters()) // getAuthorizationState arrives first
        startup.observeUpdate()                 // initial TDLib update arrives second
        XCTAssertFalse(startup.beginParameters())
        XCTAssertFalse(startup.beginParameters()) // late/repeated state can't reconfigure
    }
    func testUpdateBeforeSnapshotPreventsRewindingLogin() {
        var startup = TelegramLoginStartup()
        startup.observeUpdate()
        XCTAssertFalse(startup.acceptsSnapshot(since: 0)) // ignore a stale wait-parameters snapshot
        XCTAssertTrue(startup.beginParameters())
        startup.parametersFailed()
        XCTAssertTrue(startup.beginParameters()) // a failed attempt can be retried
        startup = TelegramLoginStartup()          // a new client must configure again
        XCTAssertTrue(startup.beginParameters())
    }
    func testNewStateQueryAfterAnUpdateIsAcceptedButOlderQueryCannotRewindIt() {
        var startup = TelegramLoginStartup()
        let initialQuery = startup.revision
        startup.observeUpdate() // TDLib now waits for the phone number
        XCTAssertFalse(startup.acceptsSnapshot(since: initialQuery))
        let retryQuery = startup.revision
        XCTAssertTrue(startup.acceptsSnapshot(since: retryQuery)) // reconnect with no new update
        startup.observeUpdate() // a code/password update arrives during the query
        XCTAssertFalse(startup.acceptsSnapshot(since: retryQuery))
        XCTAssertTrue(startup.acceptsSnapshot(since: startup.revision))
    }
    func testPastedInternationalNumberDoesNotDuplicateCountryCode() {
        XCTAssertEqual(TelegramPhoneNumber.international("  +91 98765-43210 ", dial: "+91"), "+919876543210")
        XCTAssertEqual(TelegramPhoneNumber.international("+44 7700 900123", dial: "+1"), "+447700900123")
        XCTAssertEqual(TelegramPhoneNumber.international("0044 7700 900123", dial: "+91"), "+447700900123")
        XCTAssertEqual(TelegramPhoneNumber.international("98765 43210", dial: "+91"), "+919876543210")
        XCTAssertEqual(TelegramPhoneNumber.international(" ", dial: "+1"), "")
        XCTAssertEqual(TelegramPhoneNumber.international("00", dial: "+1"), "")
    }
    func testDeliveryUsesTelegramMetadataAndHonorsResendDeadline() {
        let now = Date(timeIntervalSince1970: 100)
        let info: [String: Any] = ["phone_number": "+15550100", "type": ["@type": "authenticationCodeTypeTelegramMessage"], "next_type": ["@type": "authenticationCodeTypeSms"], "timeout": 30]
        let delivery = TelegramCodeDelivery(info, now: now)
        XCTAssertTrue(delivery.message.contains("verified Telegram service chat"))
        XCTAssertEqual(delivery.phone, "+15550100")
        XCTAssertTrue(delivery.canResend)
        XCTAssertEqual(delivery.secondsUntilResend(at: now.addingTimeInterval(29.1)), 1)
        XCTAssertEqual(delivery.secondsUntilResend(at: now.addingTimeInterval(30)), 0)
        XCTAssertEqual(delivery.secondsUntilResend(at: now.addingTimeInterval(60)), 0)
    }
    func testNoResendIsInventedAndSmsWordsUseTextInput() {
        let delivery = TelegramCodeDelivery(["type": ["@type": "authenticationCodeTypeSmsWord"], "next_type": NSNull()])
        XCTAssertFalse(delivery.canResend)
        XCTAssertFalse(delivery.numeric)
        XCTAssertTrue(delivery.message.contains("SMS"))
        let unknown = TelegramCodeDelivery([:])
        XCTAssertFalse(unknown.canResend)
        XCTAssertTrue(unknown.message.contains("may be unavailable"))
    }
}
