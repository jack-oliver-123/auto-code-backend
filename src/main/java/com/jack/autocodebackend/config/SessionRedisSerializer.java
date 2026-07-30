package com.jack.autocodebackend.config;

import com.jack.autocodebackend.model.session.AuthenticatedSession;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;

import java.nio.charset.StandardCharsets;

final class SessionRedisSerializer implements RedisSerializer<Object> {

    private static final int MAX_SERIALIZED_BYTES = 256;

    private static final String AUTHENTICATED_SESSION_PREFIX = "auth:";

    private static final String LONG_PREFIX = "long:";

    private static final String INTEGER_PREFIX = "int:";

    @Override
    public byte[] serialize(Object value) throws SerializationException {
        if (value == null) {
            return new byte[0];
        }
        String serialized = switch (value) {
            case AuthenticatedSession authenticatedSession -> AUTHENTICATED_SESSION_PREFIX
                    + authenticatedSession.userId()
                    + ':'
                    + authenticatedSession.credentialFingerprint();
            case Long longValue -> LONG_PREFIX + longValue;
            case Integer integerValue -> INTEGER_PREFIX + integerValue;
            default -> throw new SerializationException(
                    "Unsupported Redis Session value type: " + value.getClass().getName());
        };
        byte[] bytes = serialized.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length > MAX_SERIALIZED_BYTES) {
            throw new SerializationException("Redis Session value exceeds the size limit");
        }
        return bytes;
    }

    @Override
    public Object deserialize(byte[] bytes) throws SerializationException {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        if (bytes.length > MAX_SERIALIZED_BYTES) {
            throw new SerializationException("Redis Session value exceeds the size limit");
        }
        String serialized = new String(bytes, StandardCharsets.US_ASCII);
        try {
            if (serialized.startsWith(AUTHENTICATED_SESSION_PREFIX)) {
                return deserializeAuthenticatedSession(
                        serialized.substring(AUTHENTICATED_SESSION_PREFIX.length()));
            }
            if (serialized.startsWith(LONG_PREFIX)) {
                return Long.valueOf(serialized.substring(LONG_PREFIX.length()));
            }
            if (serialized.startsWith(INTEGER_PREFIX)) {
                return Integer.valueOf(serialized.substring(INTEGER_PREFIX.length()));
            }
        } catch (IllegalArgumentException exception) {
            throw new SerializationException("Malformed Redis Session value", exception);
        }
        throw new SerializationException("Unsupported Redis Session value format");
    }

    private AuthenticatedSession deserializeAuthenticatedSession(String serialized) {
        int separator = serialized.indexOf(':');
        if (separator <= 0 || separator == serialized.length() - 1) {
            throw new IllegalArgumentException("missing authenticated-session field");
        }
        long userId = Long.parseLong(serialized.substring(0, separator));
        String fingerprint = serialized.substring(separator + 1);
        return new AuthenticatedSession(userId, fingerprint);
    }
}
