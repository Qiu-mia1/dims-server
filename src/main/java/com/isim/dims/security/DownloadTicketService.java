package com.isim.dims.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/** Short-lived, signed tickets used by browser-native downloads. */
@Service
public class DownloadTicketService {
    private static final Duration TTL = Duration.ofMinutes(5);
    private final byte[] secret;

    public DownloadTicketService(@Value("${dims.download.ticket-secret:}") String configuredSecret) {
        this.secret = (configuredSecret == null || configuredSecret.isBlank())
                ? randomSecret()
                : configuredSecret.getBytes(StandardCharsets.UTF_8);
    }

    public String issue(String sliceId) {
        long expiresAt = Instant.now().plus(TTL).getEpochSecond();
        String payload = sliceId + "." + expiresAt + "." + randomNonce();
        return encode(payload + "." + sign(payload));
    }

    public boolean verify(String sliceId, String ticket) {
        try {
            String value = new String(Base64.getUrlDecoder().decode(ticket), StandardCharsets.UTF_8);
            String[] parts = value.split("\\.", 4);
            if (parts.length != 4 || !sliceId.equals(parts[0])) return false;
            long expiresAt = Long.parseLong(parts[1]);
            if (expiresAt < Instant.now().getEpochSecond()) return false;
            String payload = parts[0] + "." + parts[1] + "." + parts[2];
            return constantTimeEquals(parts[3], sign(payload));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to sign download ticket", e);
        }
    }

    private static byte[] randomSecret() {
        byte[] value = new byte[32];
        new SecureRandom().nextBytes(value);
        return value;
    }

    private static String randomNonce() {
        byte[] value = new byte[12];
        new SecureRandom().nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static boolean constantTimeEquals(String left, String right) {
        return java.security.MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
    }
}
