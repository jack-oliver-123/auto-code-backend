package com.jack.autocodebackend.model.session;

import java.io.Serial;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

public record AuthenticatedSession(
        long userId,
        String credentialFingerprint
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final Pattern SHA_256_PATTERN = Pattern.compile("[a-f0-9]{64}");

    public AuthenticatedSession {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (credentialFingerprint == null
                || !SHA_256_PATTERN.matcher(credentialFingerprint).matches()) {
            throw new IllegalArgumentException("credentialFingerprint must be a SHA-256 value");
        }
    }

    public static AuthenticatedSession fromCredential(Long userId, String encodedCredential) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        return new AuthenticatedSession(userId, fingerprint(encodedCredential));
    }

    public boolean matchesCredential(String encodedCredential) {
        if (encodedCredential == null) {
            return false;
        }
        byte[] expected = credentialFingerprint.getBytes(StandardCharsets.US_ASCII);
        byte[] actual = fingerprint(encodedCredential).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }

    private static String fingerprint(String encodedCredential) {
        Objects.requireNonNull(encodedCredential, "encodedCredential must not be null");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(encodedCredential.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
