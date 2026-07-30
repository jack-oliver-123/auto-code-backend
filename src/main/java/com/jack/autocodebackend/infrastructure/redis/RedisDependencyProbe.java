package com.jack.autocodebackend.infrastructure.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
@Slf4j
public class RedisDependencyProbe {

    private static final String EXPECTED_PING_RESPONSE = "PONG";

    private final RedisConnectionFactory connectionFactory;

    private final RedisDependencyAvailability availability;

    private final DataRedisProperties redisProperties;

    public RedisDependencyProbe(
            RedisConnectionFactory connectionFactory,
            RedisDependencyAvailability availability,
            DataRedisProperties redisProperties
    ) {
        this.connectionFactory = connectionFactory;
        this.availability = availability;
        this.redisProperties = redisProperties;
    }

    public boolean checkReadiness() {
        return probe("readiness").available();
    }

    public void requireAvailableAtStartup() {
        ProbeResult result = probe("startup");
        if (!result.available()) {
            throw new RedisDependencyStartupException(
                    "Redis dependency startup probe failed on configured host, port "
                            + redisProperties.getPort()
                            + ", database "
                            + redisProperties.getDatabase()
                            + " (" + result.category() + "); verify service reachability "
                            + "and credentials");
        }
    }

    private ProbeResult probe(String operation) {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            String response = Objects.requireNonNull(connection, "Redis connection")
                    .ping();
            if (!EXPECTED_PING_RESPONSE.equals(response)) {
                return unavailable(operation, "unexpected-response");
            }
            availability.markAvailable();
            return new ProbeResult(true, "ok");
        } catch (RuntimeException exception) {
            return unavailable(operation, exception.getClass().getSimpleName());
        }
    }

    private ProbeResult unavailable(String operation, String category) {
        availability.markUnavailable();
        log.warn("Redis dependency probe failed: operation={}, category={}",
                operation, category);
        return new ProbeResult(false, category);
    }

    private record ProbeResult(boolean available, String category) {
    }
}
