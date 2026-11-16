package edu.groupproject.faceauth.service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.OptionalLong;

/**
 * Time-based One-Time Passwords (RFC 6238) — the 6-digit codes shown by
 * Google Authenticator, Microsoft Authenticator, Authy, etc.
 *
 * Written in plain Java (no library) so the group can see exactly how it works:
 *   code = last 6 digits of HMAC-SHA1(secret, floor(unixTime / 30))
 */
public class TotpService {

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int PERIOD_SECONDS = 30;
    private static final int DIGITS = 6;
    /** Accept codes from one step before/after to allow for clock drift. */
    private static final int ALLOWED_DRIFT_STEPS = 1;

    private final SecureRandom random = new SecureRandom();

    /** New random 160-bit secret, Base32-encoded (what authenticator apps expect). */
    public String generateSecret() {
        byte[] bytes = new byte[20];
        random.nextBytes(bytes);
        return base32Encode(bytes);
    }

    /** The URI that gets turned into a QR code for the authenticator app to scan. */
    public String buildOtpAuthUri(String issuer, String account, String secret) {
        String label = enc(issuer) + ":" + enc(account);
        return "otpauth://totp/" + label + "?secret=" + secret + "&issuer=" + enc(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + PERIOD_SECONDS;
    }

    /**
     * Checks a code. Returns the matching time-step if valid so the caller can store it
     * and reject the same code if it is submitted again (replay protection).
     */
    public OptionalLong verify(String secret, String code, long lastUsedStep) {
        return verifyAt(secret, code, lastUsedStep, Instant.now().getEpochSecond());
    }

    OptionalLong verifyAt(String secret, String code, long lastUsedStep, long epochSeconds) {
        if (secret == null || code == null || !code.matches("\\d{" + DIGITS + "}")) {
            return OptionalLong.empty();
        }
        byte[] key = base32Decode(secret);
        long currentStep = epochSeconds / PERIOD_SECONDS;
        for (int i = -ALLOWED_DRIFT_STEPS; i <= ALLOWED_DRIFT_STEPS; i++) {
            long step = currentStep + i;
            if (step <= lastUsedStep) continue; // already used
            String expected = generateCode(key, step);
            // constant-time comparison to avoid timing attacks
            if (MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                                      code.getBytes(StandardCharsets.US_ASCII))) {
                return OptionalLong.of(step);
            }
        }
        return OptionalLong.empty();
    }

    /** HOTP (RFC 4226) for a given counter/time-step. */
    static String generateCode(byte[] key, long counter) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            int otp = binary % (int) Math.pow(10, DIGITS);
            return String.format("%0" + DIGITS + "d", otp);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 not available", e);
        }
    }

    // ---------- Base32 helpers ----------

    static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                sb.append(BASE32_ALPHABET.charAt((buffer >> (bitsLeft - 5)) & 0x1F));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            sb.append(BASE32_ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1F));
        }
        return sb.toString();
    }

    static byte[] base32Decode(String s) {
        String clean = s.replace("=", "").replace(" ", "").toUpperCase();
        ByteBuffer out = ByteBuffer.allocate(clean.length() * 5 / 8);
        int buffer = 0, bitsLeft = 0;
        for (char c : clean.toCharArray()) {
            int val = BASE32_ALPHABET.indexOf(c);
            if (val < 0) throw new IllegalArgumentException("Invalid Base32 character: " + c);
            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out.put((byte) ((buffer >> (bitsLeft - 8)) & 0xFF));
                bitsLeft -= 8;
            }
        }
        byte[] result = new byte[out.position()];
        out.flip();
        out.get(result);
        return result;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
