package com.jack.autocodebackend.core.lock;

import com.jack.autocodebackend.config.AppProcessingLeaseProperties;
import com.jack.autocodebackend.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SuppressWarnings({"unchecked", "rawtypes"})
class RedisAppProcessingLeaseManagerTest {

    private static final long APP_ID = 5_000_000_000L;

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

    private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);

    private final ScheduledFuture renewalFuture = mock(ScheduledFuture.class);

    private final SecureRandom secureRandom = new SecureRandom();

    private final AtomicReference<Long> renewalResult = new AtomicReference<>(1L);

    private final AtomicReference<Long> releaseResult = new AtomicReference<>(1L);

    private final ArgumentCaptor<Runnable> renewalTask = ArgumentCaptor.forClass(Runnable.class);

    private RedisAppProcessingLeaseManager manager;

    @BeforeEach
    void setUp() {
        AppProcessingLeaseProperties properties = new AppProcessingLeaseProperties(
                Duration.ofSeconds(30), Duration.ofSeconds(10), "test:lease:");
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setIfAbsent(any(), any(), any(Duration.class)))
                .willReturn(true);
        given(scheduler.scheduleAtFixedRate(
                renewalTask.capture(), eq(10_000L), eq(10_000L),
                eq(TimeUnit.MILLISECONDS))).willReturn(renewalFuture);
        given(redisTemplate.execute(
                any(RedisScript.class), anyList(), any(Object[].class)))
                .willAnswer(invocation -> {
                    RedisScript<Long> script = invocation.getArgument(0);
                    return script == RedisAppProcessingLeaseManager.RENEW_SCRIPT
                            ? renewalResult.get()
                            : releaseResult.get();
                });
        manager = new RedisAppProcessingLeaseManager(
                redisTemplate, properties, scheduler, secureRandom);
    }

    @Test
    void acquiresNamespacedLongIdLeaseWithNxTtlAndUnpredictableToken() {
        AppProcessingLeaseManager.AppProcessingLease lease = manager.acquire(APP_ID);

        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).setIfAbsent(
                eq("test:lease:" + APP_ID),
                tokenCaptor.capture(),
                eq(Duration.ofSeconds(30)));
        assertThat(tokenCaptor.getValue()).hasSize(43);
        assertThat(lease.appId()).isEqualTo(APP_ID);
        assertThat(lease.isLost()).isFalse();
        lease.close();
    }

    @Test
    void rejectsBusyLeaseAndMapsRedisAcquisitionFailure() {
        given(valueOperations.setIfAbsent(any(), any(), any(Duration.class)))
                .willReturn(false)
                .willThrow(new IllegalStateException("Redis unavailable"));

        assertThatThrownBy(() -> manager.acquire(APP_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage("应用正在处理中，请稍后重试");
        assertThatThrownBy(() -> manager.acquire(APP_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage("应用处理租约不可用")
                .hasCauseInstanceOf(IllegalStateException.class);
        verify(scheduler, never()).scheduleAtFixedRate(
                any(), any(Long.class), any(Long.class), any(TimeUnit.class));
    }

    @Test
    void presenceCheckDistinguishesPresentAbsentAndRedisUncertainty() {
        given(redisTemplate.hasKey("test:lease:" + APP_ID))
                .willReturn(true)
                .willReturn(false)
                .willThrow(new IllegalStateException("Redis unavailable"));

        assertThat(manager.checkPresence(APP_ID))
                .isEqualTo(AppProcessingLeaseManager.LeasePresence.PRESENT);
        assertThat(manager.checkPresence(APP_ID))
                .isEqualTo(AppProcessingLeaseManager.LeasePresence.ABSENT);
        assertThat(manager.checkPresence(APP_ID))
                .isEqualTo(AppProcessingLeaseManager.LeasePresence.UNKNOWN);
    }

    @Test
    void renewsOnlyOwnedLeaseAndSignalsTokenMismatchAsLoss() {
        AppProcessingLeaseManager.AppProcessingLease lease = manager.acquire(APP_ID);
        CompletableFuture<Void> loss = lease.lossSignal().toFuture();

        renewalTask.getValue().run();
        assertThat(lease.isLost()).isFalse();
        assertThat(loss).isNotDone();

        renewalResult.set(0L);
        renewalTask.getValue().run();

        assertThat(lease.isLost()).isTrue();
        assertThatThrownBy(lease::assertHeld)
                .isInstanceOf(AppProcessingLeaseLostException.class);
        assertThatThrownBy(loss::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(AppProcessingLeaseLostException.class);
        verify(renewalFuture).cancel(false);
        lease.close();
    }

    @Test
    void renewalFailureAlsoLosesLeaseAndExpiryProvidesCrashRecovery() {
        AppProcessingLeaseManager.AppProcessingLease lease = manager.acquire(APP_ID);
        given(redisTemplate.execute(
                eq(RedisAppProcessingLeaseManager.RENEW_SCRIPT),
                anyList(), any(Object[].class)))
                .willThrow(new IllegalStateException("Redis unavailable"));

        renewalTask.getValue().run();

        assertThat(lease.isLost()).isTrue();
        verify(valueOperations).setIfAbsent(
                eq("test:lease:" + APP_ID), any(), eq(Duration.ofSeconds(30)));
        lease.close();
    }

    @Test
    void releaseIsOwnerCheckedThreadIndependentAndIdempotent() {
        AppProcessingLeaseManager.AppProcessingLease lease = manager.acquire(APP_ID);
        releaseResult.set(0L);

        CompletableFuture.runAsync(lease::close).join();
        lease.close();

        ArgumentCaptor<RedisScript<Long>> scriptCaptor = ArgumentCaptor.forClass(RedisScript.class);
        ArgumentCaptor<List<String>> keyCaptor = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate, times(1)).execute(
                scriptCaptor.capture(), keyCaptor.capture(), any(Object[].class));
        assertThat(scriptCaptor.getValue())
                .isSameAs(RedisAppProcessingLeaseManager.RELEASE_SCRIPT);
        assertThat(keyCaptor.getValue()).containsExactly("test:lease:" + APP_ID);
        verify(renewalFuture).cancel(false);
    }

    @Test
    void shutdownClosesActiveLeasesAndSchedulerDeterministically() throws Exception {
        manager.acquire(APP_ID);
        given(scheduler.awaitTermination(5, TimeUnit.SECONDS)).willReturn(true);

        manager.shutdown();

        verify(scheduler).shutdownNow();
        verify(scheduler).awaitTermination(5, TimeUnit.SECONDS);
        verify(redisTemplate).execute(
                eq(RedisAppProcessingLeaseManager.RELEASE_SCRIPT),
                anyList(), any(Object[].class));
    }
}
