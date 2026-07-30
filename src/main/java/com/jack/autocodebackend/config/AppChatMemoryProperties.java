package com.jack.autocodebackend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Objects;

@ConfigurationProperties("app.chat-memory")
public final class AppChatMemoryProperties {

    public static final int MAX_HISTORY_LIMIT = 100;

    private final int historyLimit;

    private final int messageMaxChars;

    private final int totalMaxChars;

    private final int payloadMaxBytes;

    private final Duration snapshotTtl;

    private final long cacheMaximumSize;

    private final Duration cacheTtl;

    private final String redisKeyPrefix;

    private final String invalidationChannel;

    public AppChatMemoryProperties(
            @DefaultValue("10") int historyLimit,
            @DefaultValue("6000") int messageMaxChars,
            @DefaultValue("24000") int totalMaxChars,
            @DefaultValue("262144") int payloadMaxBytes,
            @DefaultValue("7d") Duration snapshotTtl,
            @DefaultValue("1000") long cacheMaximumSize,
            @DefaultValue("10m") Duration cacheTtl,
            @DefaultValue("auto-code:chat-memory:v1:") String redisKeyPrefix,
            @DefaultValue("auto-code:chat-memory:invalidation:v1") String invalidationChannel
    ) {
        this.historyLimit = requirePositive(historyLimit, "historyLimit");
        if (historyLimit > MAX_HISTORY_LIMIT) {
            throw new IllegalArgumentException(
                    "historyLimit must not exceed " + MAX_HISTORY_LIMIT);
        }
        this.messageMaxChars = requirePositive(messageMaxChars, "messageMaxChars");
        this.totalMaxChars = requirePositive(totalMaxChars, "totalMaxChars");
        if (totalMaxChars < messageMaxChars) {
            throw new IllegalArgumentException(
                    "totalMaxChars must be greater than or equal to messageMaxChars");
        }
        this.payloadMaxBytes = requirePositive(payloadMaxBytes, "payloadMaxBytes");
        this.snapshotTtl = requirePositive(snapshotTtl, "snapshotTtl");
        this.cacheMaximumSize = requirePositive(cacheMaximumSize, "cacheMaximumSize");
        this.cacheTtl = requirePositive(cacheTtl, "cacheTtl");
        this.redisKeyPrefix = requireNamespace(redisKeyPrefix, "redisKeyPrefix");
        this.invalidationChannel = requireNamespace(
                invalidationChannel, "invalidationChannel");
    }

    public int getHistoryLimit() {
        return historyLimit;
    }

    public int getMessageMaxChars() {
        return messageMaxChars;
    }

    public int getTotalMaxChars() {
        return totalMaxChars;
    }

    public int getPayloadMaxBytes() {
        return payloadMaxBytes;
    }

    public Duration getSnapshotTtl() {
        return snapshotTtl;
    }

    public long getCacheMaximumSize() {
        return cacheMaximumSize;
    }

    public Duration getCacheTtl() {
        return cacheTtl;
    }

    public String getRedisKeyPrefix() {
        return redisKeyPrefix;
    }

    public String getInvalidationChannel() {
        return invalidationChannel;
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static long requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative() || value.toMillis() <= 0) {
            throw new IllegalArgumentException(name + " must be at least one millisecond");
        }
        return value;
    }

    private static String requireNamespace(String value, String name) {
        String normalized = Objects.requireNonNull(value, name + " must not be null").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
