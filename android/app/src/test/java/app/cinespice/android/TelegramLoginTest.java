package app.cinespice.android;

import org.junit.Test;
import static org.junit.Assert.*;

public class TelegramLoginTest {
    @Test public void snapshotThenUpdateCannotConfigureTwice() {
        TelegramLogin.Startup startup = new TelegramLogin.Startup();
        assertTrue(startup.acceptsSnapshot(0));
        assertTrue(startup.beginParameters());
        startup.observeUpdate();
        assertFalse(startup.acceptsSnapshot(0));
        assertFalse(startup.beginParameters());
    }
    @Test public void newerUpdateWinsAndFailedSetupCanRetry() {
        TelegramLogin.Startup startup = new TelegramLogin.Startup();
        startup.observeUpdate();
        assertFalse(startup.acceptsSnapshot(0));
        assertTrue(startup.beginParameters());
        startup.parametersFailed();
        assertTrue(startup.beginParameters());
        startup.reset();
        assertTrue(startup.acceptsSnapshot(0));
        assertTrue(startup.beginParameters());
    }
    @Test public void freshStateQueryAfterUpdateIsAcceptedAndOldQueryIsRejected() {
        TelegramLogin.Startup startup = new TelegramLogin.Startup();
        int initialQuery = startup.revision();
        startup.observeUpdate();
        assertFalse(startup.acceptsSnapshot(initialQuery));
        int retryQuery = startup.revision();
        assertTrue(startup.acceptsSnapshot(retryQuery));
        startup.observeUpdate();
        assertFalse(startup.acceptsSnapshot(retryQuery));
        assertTrue(startup.acceptsSnapshot(startup.revision()));
    }
    @Test public void pastedInternationalNumberDoesNotDuplicateDialCode() {
        assertEquals("+919876543210", TelegramLogin.internationalPhone("  +91 98765-43210 ", "+91"));
        assertEquals("+447700900123", TelegramLogin.internationalPhone("+44 7700 900123", "+1"));
        assertEquals("+447700900123", TelegramLogin.internationalPhone("0044 7700 900123", "+91"));
        assertEquals("+919876543210", TelegramLogin.internationalPhone("98765 43210", "+91"));
        assertEquals("", TelegramLogin.internationalPhone(" ", "+1"));
        assertEquals("", TelegramLogin.internationalPhone("00", "+1"));
    }
    @Test public void deliveryAndResendFollowServerMetadata() {
        TelegramLogin.CodeDelivery code = new TelegramLogin.CodeDelivery(Json.obj("phone_number", "+15550100", "type", Json.obj("@type", "authenticationCodeTypeTelegramMessage"), "next_type", Json.obj("@type", "authenticationCodeTypeSms"), "timeout", 30), 100000);
        assertTrue(code.message.contains("verified Telegram service chat"));
        assertEquals("+15550100", code.phone);
        assertTrue(code.canResend);
        assertEquals(1, code.secondsUntilResend(129100));
        assertEquals(0, code.secondsUntilResend(130000));
        assertEquals(0, code.secondsUntilResend(160000));
    }
    @Test public void noResendIsInventedAndWordCodesAreAccepted() {
        TelegramLogin.CodeDelivery code = new TelegramLogin.CodeDelivery(Json.obj("type", Json.obj("@type", "authenticationCodeTypeSmsWord")), 0);
        assertFalse(code.numeric); assertFalse(code.canResend); assertTrue(code.message.contains("SMS"));
        TelegramLogin.CodeDelivery unknown = new TelegramLogin.CodeDelivery(Json.obj(), 0);
        assertFalse(unknown.canResend); assertTrue(unknown.message.contains("may be unavailable"));
    }
}
