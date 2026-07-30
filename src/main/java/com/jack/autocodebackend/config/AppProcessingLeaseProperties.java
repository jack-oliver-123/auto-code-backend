package com.jack.autocodebackend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Objects;

@ConfigurationProperties("app.processing-lease")
public final class AppProcessingLeaseProperties {

    private final Duration duration;

    private final Duration renewalInterval;

    private final String redisKeyPrefix;

    public AppProcessingLeaseProperties(
            @DefaultValue("30s") Duration duration,
            @DefaultValue("10s") Duration renewalInterval,
            @DefaultValue("auto-code:processing-lease:v1:") String redisKeyPrefix
    ) {
        this.duration = requirePositive(duration, "duration");
        this.renewalInterval = requirePositive(renewalInterval, "renewalInterval");
        if (renewalInterval.multipliedBy(2).compareTo(duration) >= 0) {
            throw new IllegalArgumentException(
                    "renewalInterval must be less than half of duration");
        }
        String normalizedPrefix = Objects.requireNonNull(
                redisKeyPrefix, "redisKeyPrefix must not be null").trim();
        if (normalizedPrefix.isEmpty()) {
            throw new IllegalArgumentException("redisKeyPrefix must not be blank");
        }
        this.redisKeyPrefix = normalizedPrefix;
    }

    public Duration getDuration() {
        return duration;
    }

    public Duration getRenewalInterval() {
        return renewalInterval;
    }

    public String getRedisKeyPrefix() {
        return redisKeyPrefix;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative() || value.toMillis() <= 0) {
            throw new IllegalArgumentException(name + " must be at least one millisecond");
        }
        return value;
    }
}
