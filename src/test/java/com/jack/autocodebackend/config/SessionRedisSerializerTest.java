package com.jack.autocodebackend.config;

import com.jack.autocodebackend.model.domain.User;
import com.jack.autocodebackend.model.session.AuthenticatedSession;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.SerializationException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionRedisSerializerTest {

    private final SessionRedisSerializer serializer = new SessionRedisSerializer();

    @Test
    void roundTripsSupportedSessionValuesAcrossInstances() {
        AuthenticatedSession loginState = AuthenticatedSession.fromCredential(
                9_000_000_000L, "{pbkdf2}encoded-credential");

        byte[] serialized = serializer.serialize(loginState);

        assertThat(new SessionRedisSerializer().deserialize(serialized)).isEqualTo(loginState);
        assertThat(serializer.deserialize(serializer.serialize(123L))).isEqualTo(123L);
        assertThat(serializer.deserialize(serializer.serialize(456))).isEqualTo(456);
        assertThat(new String(serialized, StandardCharsets.US_ASCII))
                .doesNotContain("encoded-credential", "pbkdf2");
    }

    @Test
    void rejectsPersistenceEntitiesAndMalformedOrOversizedValues() {
        assertThatThrownBy(() -> serializer.serialize(new User()))
                .isInstanceOf(SerializationException.class);
        assertThatThrownBy(() -> serializer.deserialize("auth:1:invalid".getBytes(
                StandardCharsets.US_ASCII)))
                .isInstanceOf(SerializationException.class);
        assertThatThrownBy(() -> serializer.deserialize(new byte[257]))
                .isInstanceOf(SerializationException.class);
    }
}
