package com.jack.autocodebackend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Objects;

/**
 * Coherent finite limits for one interactive generation attempt.
 */
@ConfigurationProperties("app.generation")
public final class AppGenerationProperties {

    private static final Duration MINIMUM_FINALIZATION_ALLOWANCE = Duration.ofSeconds(30);

    private final Duration providerTimeout;
    private final Duration completeAttemptTimeout;
    private final Duration servletAsyncTimeout;
    private final Duration heartbeatInterval;
    private final Duration staleAttemptAge;

    public AppGenerationProperties(
            @DefaultValue("5m") Duration providerTimeout,
            @DefaultValue("8m") Duration completeAttemptTimeout,
            @DefaultValue("9m") Duration servletAsyncTimeout,
            @DefaultValue("15s") Duration heartbeatInterval,
            @DefaultValue("12m") Duration staleAttemptAge
    ) {
        this.providerTimeout = requirePositive(providerTimeout, "providerTimeout");
        this.completeAttemptTimeout = requirePositive(
                completeAttemptTimeout, "completeAttemptTimeout");
        this.servletAsyncTimeout = requirePositive(servletAsyncTimeout, "servletAsyncTimeout");
        this.heartbeatInterval = requirePositive(heartbeatInterval, "heartbeatInterval");
        this.staleAttemptAge = requirePositive(staleAttemptAge, "staleAttemptAge");
        validateOrdering();
    }

    public static AppGenerationProperties defaults() {
        return new AppGenerationProperties(
                Duration.ofMinutes(5),
                Duration.ofMinutes(8),
                Duration.ofMinutes(9),
                Duration.ofSeconds(15),
                Duration.ofMinutes(12)
        );
    }

    public void validateAgainstVueBuildTimeout(Duration buildTimeout) {
        Duration validBuildTimeout = requirePositive(buildTimeout, "vueBuildTimeout");
        Duration requiredAttemptTimeout = providerTimeout
                .plus(validBuildTimeout)
                .plus(MINIMUM_FINALIZATION_ALLOWANCE);
        if (completeAttemptTimeout.compareTo(requiredAttemptTimeout) <= 0) {
            throw new IllegalArgumentException(
                    "completeAttemptTimeout must exceed providerTimeout, Vue build timeout, "
                            + "and finalization allowance");
        }
    }

    public Duration getProviderTimeout() {
        return providerTimeout;
    }

    public Duration getCompleteAttemptTimeout() {
        return completeAttemptTimeout;
    }

    public Duration getServletAsyncTimeout() {
        return servletAsyncTimeout;
    }

    public Duration getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public Duration getStaleAttemptAge() {
        return staleAttemptAge;
    }

    private void validateOrdering() {
        if (heartbeatInterval.compareTo(providerTimeout) >= 0) {
            throw new IllegalArgumentException(
                    "heartbeatInterval must be less than providerTimeout");
        }
        if (providerTimeout.compareTo(completeAttemptTimeout) >= 0) {
            throw new IllegalArgumentException(
                    "providerTimeout must be less than completeAttemptTimeout");
        }
        if (completeAttemptTimeout.compareTo(servletAsyncTimeout) >= 0) {
            throw new IllegalArgumentException(
                    "completeAttemptTimeout must be less than servletAsyncTimeout");
        }
        if (staleAttemptAge.compareTo(servletAsyncTimeout) <= 0) {
            throw new IllegalArgumentException(
                    "staleAttemptAge must exceed the maximum live request timeout");
        }
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative() || value.toMillis() <= 0) {
            throw new IllegalArgumentException(name + " must be at least one millisecond");
        }
        return value;
    }
}
