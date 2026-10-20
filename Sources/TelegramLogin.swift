import Foundation

/// A TDLib state snapshot can arrive after a newer authorization update.
struct TelegramLoginStartup {
    private(set) var receivedUpdate = false
    private(set) var submittedParameters = false
    mutating func observeUpdate() { receivedUpdate = true }
    mutating func beginParameters() -> Bool {
        guard !submittedParameters else { return false }
        submittedParameters = true
        return true
    }
    mutating func parametersFailed() { submittedParameters = false }
}

enum TelegramPhoneNumber {
    static func international(_ input: String, dial: String) -> String {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        let digits = text.filter { $0.isASCII && $0.isNumber }
        guard !digits.isEmpty else { return "" }
        if text.hasPrefix("+") { return "+" + digits }
        if text.hasPrefix("00") { return digits.count > 2 ? "+" + digits.dropFirst(2) : "" }
        return dial + digits
    }
}

struct TelegramCodeDelivery {
    let phone: String
    let message: String
    let numeric: Bool
    let canResend: Bool
    let resendAt: Date

    init(_ info: [String: Any], now: Date = Date()) {
        phone = info["phone_number"] as? String ?? ""
        let type = (info["type"] as? [String: Any])?["@type"] as? String ?? ""
        numeric = !["authenticationCodeTypeSmsWord", "authenticationCodeTypeSmsPhrase"].contains(type)
        switch type {
        case "authenticationCodeTypeTelegramMessage": message = "Open Telegram on a device where you’re already signed in. Your code is in the verified Telegram service chat."
        case "authenticationCodeTypeSms", "authenticationCodeTypeSmsWord", "authenticationCodeTypeSmsPhrase": message = "Telegram sent your code by SMS. Check messages for this phone number."
        case "authenticationCodeTypeCall": message = "Telegram will call this number. Enter the code from the call."
        case "authenticationCodeTypeMissedCall": message = "Enter the last digits of the number that called you."
        case "authenticationCodeTypeFlashCall": message = "Telegram is verifying your number with a call."
        case "authenticationCodeTypeFragment": message = "Your login code is available on Fragment. Open fragment.com for the number you own."
        default: message = "Follow the delivery instructions in Telegram on a device where you’re signed in. SMS delivery is selected by Telegram and may be unavailable for this app."
        }
        canResend = info["next_type"] is [String: Any]
        resendAt = now.addingTimeInterval(TimeInterval(max(0, info["timeout"] as? Int ?? 0)))
    }
    func secondsUntilResend(at now: Date) -> Int { max(0, Int(ceil(resendAt.timeIntervalSince(now)))) }
}
