package com.jack.autocodebackend.core.lock;

import com.jack.autocodebackend.config.AppProcessingLeaseProperties;
import com.jack.autocodebackend.exception.BusinessException;
import com.jack.autocodebackend.exception.ErrorCode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class RedisAppProcessingLeaseManager implements AppProcessingLeaseManager {

    private static final Logger log =
            LoggerFactory.getLogger(RedisAppProcessingLeaseManager.class);

    static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('pexpire', KEYS[1], ARGV[2])
            end
            return 0
            """, Long.class);

    static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    private final AppProcessingLeaseProperties properties;

    private final ScheduledExecutorService renewalExecutor;

    private final SecureRandom secureRandom;

    private final Set<RedisLease> activeLeases = ConcurrentHashMap.newKeySet();

    @Autowired
    public RedisAppProcessingLeaseManager(
            StringRedisTemplate redisTemplate,
            AppProcessingLeaseProperties properties
    ) {
        this(
                redisTemplate,
                properties,
                createRenewalExecutor(properties),
                new SecureRandom()
        );
    }

    private static ScheduledExecutorService createRenewalExecutor(
            AppProcessingLeaseProperties properties
    ) {
        AtomicInteger threadSequence = new AtomicInteger();
        return Executors.newScheduledThreadPool(
                properties.getRenewalParallelism(),
                runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "app-processing-lease-renewal-"
                                    + threadSequence.incrementAndGet()
                    );
                    thread.setDaemon(true);
                    return thread;
                }
        );
    }

    RedisAppProcessingLeaseManager(
            StringRedisTemplate redisTemplate,
            AppProcessingLeaseProperties properties,
            ScheduledExecutorService renewalExecutor,
            SecureRandom secureRandom
    ) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.renewalExecutor = renewalExecutor;
        this.secureRandom = secureRandom;
    }

    @Override
    public AppProcessingLease acquire(Long appId) {
        long validAppId = requirePositiveAppId(appId);
        String key = properties.getRedisKeyPrefix() + validAppId;
        String ownerToken = newOwnerToken();
        Boolean acquired;
        try {
            acquired = redisTemplate.opsForValue().setIfAbsent(
                    key, ownerToken, properties.getDuration());
        } catch (RuntimeException exception) {
            throw operationFailure("应用处理租约不可用", exception);
        }
        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("Application processing lease is busy for app {}", validAppId);
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR, "应用正在处理中，请稍后重试");
        }

        RedisLease lease = new RedisLease(validAppId, key, ownerToken);
        activeLeases.add(lease);
        try {
            lease.startRenewal();
        } catch (RuntimeException exception) {
            lease.close();
            throw operationFailure("启动应用处理租约续期失败", exception);
        }
        log.debug("Acquired application processing lease for app {}", validAppId);
        return lease;
    }

    @Override
    public LeasePresence checkPresence(Long appId) {
        long validAppId = requirePositiveAppId(appId);
        String key = properties.getRedisKeyPrefix() + validAppId;
        try {
            Boolean present = redisTemplate.hasKey(key);
            if (present == null) {
                return LeasePresence.UNKNOWN;
            }
            return present ? LeasePresence.PRESENT : LeasePresence.ABSENT;
        } catch (RuntimeException exception) {
            log.warn("Application processing lease presence check failed for app {}",
                    validAppId);
            return LeasePresence.UNKNOWN;
        }
    }

    @PreDestroy
    public void shutdown() {
        for (RedisLease lease : List.copyOf(activeLeases)) {
            lease.close();
        }
        renewalExecutor.shutdownNow();
        try {
            if (!renewalExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Application processing lease scheduler did not terminate cleanly");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void renew(RedisLease lease) {
        if (lease.closed.get() || lease.lost.get()) {
            return;
        }
        try {
            Long renewed = redisTemplate.execute(
                    RENEW_SCRIPT,
                    List.of(lease.key),
                    lease.ownerToken,
                    Long.toString(properties.getDuration().toMillis())
            );
            if (!Long.valueOf(1L).equals(renewed)) {
                lease.markLost();
                return;
            }
            log.debug("Renewed application processing lease for app {}", lease.appId);
        } catch (RuntimeException exception) {
            lease.markLost();
            log.warn("Application processing lease renewal failed for app {}", lease.appId);
        }
    }

    private void release(RedisLease lease) {
        try {
            Long released = redisTemplate.execute(
                    RELEASE_SCRIPT, List.of(lease.key), lease.ownerToken);
            if (Long.valueOf(1L).equals(released)) {
                log.debug("Released application processing lease for app {}", lease.appId);
            } else {
                log.debug("Application processing lease was no longer owned for app {}",
                        lease.appId);
            }
        } catch (RuntimeException exception) {
            log.warn("Application processing lease release failed for app {}; TTL will recover it",
                    lease.appId);
        } finally {
            activeLeases.remove(lease);
        }
    }

    private String newOwnerToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private long requirePositiveAppId(Long appId) {
        if (appId == null || appId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 id 不合法");
        }
        return appId;
    }

    private BusinessException operationFailure(String message, RuntimeException cause) {
        BusinessException exception = new BusinessException(ErrorCode.OPERATION_ERROR, message);
        exception.initCause(cause);
        return exception;
    }

    final class RedisLease implements AppProcessingLease {

        private final long appId;

        private final String key;

        private final String ownerToken;

        private final AtomicBoolean closed = new AtomicBoolean();

        private final AtomicBoolean lost = new AtomicBoolean();

        private final Sinks.Empty<Void> lossSink = Sinks.empty();

        private volatile ScheduledFuture<?> renewalFuture;

        private RedisLease(long appId, String key, String ownerToken) {
            this.appId = appId;
            this.key = key;
            this.ownerToken = ownerToken;
        }

        private void startRenewal() {
            Duration interval = properties.getRenewalInterval();
            renewalFuture = renewalExecutor.scheduleAtFixedRate(
                    () -> renew(this),
                    interval.toMillis(),
                    interval.toMillis(),
                    TimeUnit.MILLISECONDS
            );
        }

        private void markLost() {
            if (lost.compareAndSet(false, true)) {
                ScheduledFuture<?> future = renewalFuture;
                if (future != null) {
                    future.cancel(false);
                }
                lossSink.tryEmitError(new AppProcessingLeaseLostException());
                log.warn("Lost application processing lease for app {}", appId);
            }
        }

        @Override
        public long appId() {
            return appId;
        }

        @Override
        public boolean isLost() {
            return lost.get();
        }

        @Override
        public void assertHeld() {
            if (lost.get() || closed.get()) {
                throw new AppProcessingLeaseLostException();
            }
        }

        @Override
        public Mono<Void> lossSignal() {
            return lossSink.asMono();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            ScheduledFuture<?> future = renewalFuture;
            if (future != null) {
                future.cancel(false);
            }
            release(this);
        }
    }
}
