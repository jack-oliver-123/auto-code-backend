package com.jack.autocodebackend.infrastructure.redis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RedisDependencyProbeTest {

    private final RedisConnectionFactory connectionFactory =
            mock(RedisConnectionFactory.class);

    private final RedisConnection connection = mock(RedisConnection.class);

    private final ApplicationEventPublisher eventPublisher =
            mock(ApplicationEventPublisher.class);

    private RedisDependencyAvailability availability;

    private RedisDependencyProbe probe;

    @BeforeEach
    void setUp() {
        DataRedisProperties properties = new DataRedisProperties();
        properties.setHost("redis.internal");
        properties.setPort(6380);
        properties.setDatabase(3);
        properties.setUsername("private-user");
        properties.setPassword("private-password");
        availability = new RedisDependencyAvailability(eventPublisher);
        probe = new RedisDependencyProbe(connectionFactory, availability, properties);
    }

    @Test
    void acceptsOnlyPongAndPublishesAvailableState() {
        given(connectionFactory.getConnection()).willReturn(connection);
        given(connection.ping()).willReturn("PONG");

        assertThat(probe.checkReadiness()).isTrue();
        assertThat(availability.isAvailable()).isTrue();
        verify(eventPublisher).publishEvent(argThat(event ->
                event instanceof AvailabilityChangeEvent<?> change
                        && change.getState() == ReadinessState.ACCEPTING_TRAFFIC));
        verify(connection).close();
    }

    @Test
    void marksUnavailableThenRecoversOnLaterSuccessfulProbe() {
        given(connectionFactory.getConnection()).willReturn(connection);
        given(connection.ping()).willReturn("PONG", "NOPE", "PONG");

        assertThat(probe.checkReadiness()).isTrue();
        assertThat(probe.checkReadiness()).isFalse();
        assertThat(availability.isAvailable()).isFalse();
        verify(eventPublisher).publishEvent(argThat(event ->
                event instanceof AvailabilityChangeEvent<?> change
                        && change.getState() == ReadinessState.REFUSING_TRAFFIC));

        assertThat(probe.checkReadiness()).isTrue();
        assertThat(availability.isAvailable()).isTrue();
    }

    @Test
    void startupFailureUsesSecretFreeDiagnostic() {
        given(connectionFactory.getConnection()).willThrow(
                new RedisConnectionFailureException(
                        "redis://private-user:private-password@redis.internal:6380"));

        assertThatThrownBy(probe::requireAvailableAtStartup)
                .isInstanceOf(RedisDependencyStartupException.class)
                .hasMessageContaining("port 6380", "database 3")
                .hasMessageNotContaining("private-user")
                .hasMessageNotContaining("private-password")
                .hasMessageNotContaining("redis.internal")
                .hasMessageNotContaining("redis://");
        assertThat(availability.isAvailable()).isFalse();
    }
}
