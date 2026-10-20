package app.cinespice.android;

import org.json.JSONObject;

/** Login state and input rules, independent of the native TDLib transport. */
final class TelegramLogin {
    static final class Startup {
        private int revision;
        private boolean submittedParameters;
        synchronized void observeUpdate() { revision++; }
        synchronized int revision() { return revision; }
        synchronized boolean acceptsSnapshot(int since) { return revision == since; }
        synchronized boolean beginParameters() {
            if (submittedParameters) return false;
            submittedParameters = true; return true;
        }
        synchronized void parametersFailed() { submittedParameters = false; }
        synchronized void reset() { revision = 0; submittedParameters = false; }
    }
    static String internationalPhone(String input, String dial) {
        String text = input.trim(), digits = text.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return "";
        if (text.startsWith("+")) return "+" + digits;
        if (text.startsWith("00")) return digits.length() > 2 ? "+" + digits.substring(2) : "";
        return dial + digits;
    }
    static final class CodeDelivery {
        final String phone, message;
        final boolean numeric, canResend;
        final long resendAt;
        CodeDelivery(JSONObject info, long now) {
            phone = info.optString("phone_number");
            String type = Json.type(Json.child(info, "type"));
            numeric = !type.equals("authenticationCodeTypeSmsWord") && !type.equals("authenticationCodeTypeSmsPhrase");
            message = switch (type) {
                case "authenticationCodeTypeTelegramMessage" -> "Open Telegram on a device where you’re already signed in. Your code is in the verified Telegram service chat.";
                case "authenticationCodeTypeSms", "authenticationCodeTypeSmsWord", "authenticationCodeTypeSmsPhrase" -> "Telegram sent your code by SMS. Check messages for this phone number.";
                case "authenticationCodeTypeCall" -> "Telegram will call this number. Enter the code from the call.";
                case "authenticationCodeTypeMissedCall" -> "Enter the last digits of the number that called you.";
                case "authenticationCodeTypeFlashCall" -> "Telegram is verifying your number with a call.";
                case "authenticationCodeTypeFragment" -> "Your login code is available on Fragment. Open fragment.com for the number you own.";
                default -> "Follow the delivery instructions in Telegram on a device where you’re signed in. SMS delivery is selected by Telegram and may be unavailable for this app.";
            };
            canResend = info.optJSONObject("next_type") != null;
            resendAt = now + Math.max(0, info.optInt("timeout")) * 1000L;
        }
        int secondsUntilResend(long now) { return (int)Math.max(0, Math.ceil((resendAt - now) / 1000.0)); }
    }
}
