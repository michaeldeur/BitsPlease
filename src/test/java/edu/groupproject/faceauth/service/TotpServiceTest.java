package edu.groupproject.faceauth.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Checks our TOTP code against the official RFC 6238 test vectors. */
class TotpServiceTest {

    private final TotpService totp = new TotpService();
    // RFC 6238 SHA1 test key "12345678901234567890"
    private final String secret = TotpService.base32Encode("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    @Test
    void matchesRfcVectors() {
        // RFC values are 8 digits; we use the last 6.
        assertTrue(totp.verifyAt(secret, "287082", 0, 59).isPresent());
        assertTrue(totp.verifyAt(secret, "081804", 0, 1111111109).isPresent());
        assertTrue(totp.verifyAt(secret, "050471", 0, 1111111111).isPresent());
        assertTrue(totp.verifyAt(secret, "005924", 0, 1234567890).isPresent());
    }

    @Test
    void rejectsWrongAndReusedCodes() {
        assertTrue(totp.verifyAt(secret, "000000", 0, 59).isEmpty());
        long step = totp.verifyAt(secret, "287082", 0, 59).getAsLong();
        assertTrue(totp.verifyAt(secret, "287082", step, 59).isEmpty(), "same code must not work twice");
    }

    @Test
    void base32RoundTrip() {
        String s = totp.generateSecret();
        assertEquals(32, s.length());
        assertEquals(s, TotpService.base32Encode(TotpService.base32Decode(s)));
    }
}
