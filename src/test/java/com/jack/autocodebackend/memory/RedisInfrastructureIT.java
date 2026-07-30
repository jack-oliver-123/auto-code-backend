package com.jack.autocodebackend.memory;

import com.jack.autocodebackend.config.AppChatMemoryProperties;
import com.jack.autocodebackend.config.AppProcessingLeaseProperties;
import com.jack.autocodebackend.core.lock.AppProcessingLeaseManager;
import com.jack.autocodebackend.core.lock.RedisAppProcessingLeaseManager;
import com.jack.autocodebackend.exception.BusinessException;
import com.jack.autocodebackend.infrastructure.redis.RedisDependencyAvailability;
import com.jack.autocodebackend.infrastructure.redis.RedisDependencyProbe;
import com.jack.autocodebackend.model.session.AuthenticatedSession;
import com.jack.autocodebackend.service.ChatHistoryService;
import com.jack.autocodebackend.service.ChatMemoryService;
import com.jack.autocodebackend.service.impl.ChatMemoryServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.data.redis.RedisSessionRepository;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@EnabledIfSystemProperty(named = "redis.smoke.enabled", matches = "true")
class RedisInfrastructureIT {

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private ChatMemorySnapshotRepository snapshotRepository;

    @Autowired
    private ChatMemoryInvalidationBus invalidationBus;

    @Autowired
    private ChatMemoryService chatMemoryService;

    @Autowired
    private AppProcessingLeaseManager leaseManager;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private AppChatMemoryProperties memoryProperties;

    @Autowired
    private AppProcessingLeaseProperties leaseProperties;

    @Autowired
    private RedisConnectionFactory connectionFactory;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ChatMemoryPromptBuilder promptBuilder;

    @Autowired
    private ChatHistoryService chatHistoryService;

    @Autowired
    private Environment environment;

    @Autowired
    private RedisDependencyAvailability redisDependencyAvailability;

    @Autowired
    private RedisDependencyProbe redisDependencyProbe;

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void redisStateIsSharedAcrossIndependentBackendComponents() throws Exception {
        redisDependencyAvailability.markUnavailable();
        assertThat(redisDependencyAvailability.isAvailable()).isFalse();
        assertThat(redisDependencyProbe.checkReadiness()).isTrue();
        assertThat(redisDependencyAvailability.isAvailable()).isTrue();

        long appId = 8_000_000_000L + Math.floorMod(System.nanoTime(), 1_000_000_000L);
        Session session = sessionRepository.createSession();
        session.setAttribute("user_login", AuthenticatedSession.fromCredential(
                7_000_000_001L, "{pbkdf2}redis-smoke-credential"));
        sessionRepository.save(session);

        RedisSessionRepository primarySessionRepository =
                (RedisSessionRepository) sessionRepository;
        RedisSessionRepository restartedSessionRepository =
                new RedisSessionRepository(
                        primarySessionRepository.getSessionRedisOperations());
        restartedSessionRepository.setRedisKeyNamespace(
                environment.getRequiredProperty("spring.session.data.redis.namespace"));
        Session loaded = restartedSessionRepository.findById(session.getId());
        assertThat(loaded).isNotNull();
        assertThat((AuthenticatedSession) loaded.getAttribute("user_login"))
                .isEqualTo(session.getAttribute("user_login"));

        StringRedisTemplate secondInstanceTemplate =
                new StringRedisTemplate(connectionFactory);
        ChatMemorySnapshotRepository secondInstanceSnapshotRepository =
                new RedisChatMemorySnapshotRepository(
                        secondInstanceTemplate,
                        objectMapper,
                        memoryProperties,
                        invalidationBus
                );
        ChatMemoryService secondInstanceMemoryService = new ChatMemoryServiceImpl(
                chatHistoryService,
                secondInstanceSnapshotRepository,
                invalidationBus,
                promptBuilder,
                memoryProperties
        );

        CountDownLatch invalidated = new CountDownLatch(1);
        invalidationBus.register(receivedAppId -> {
            if (receivedAppId == appId) {
                invalidated.countDown();
            }
        });
        ChatMemorySnapshot snapshot = new ChatMemorySnapshot(
                1,
                appId,
                1L,
                List.of(new ChatMemoryMessage(
                        1L, ChatMemoryRole.USER, "Redis smoke message"))
        );
        snapshotRepository.save(snapshot);
        assertThat(snapshotRepository.find(appId))
                .contains(new VersionedChatMemorySnapshot(1L, snapshot));
        assertThat(secondInstanceMemoryService.loadSnapshot(appId, null))
                .isEqualTo(snapshot);
        Long ttlSeconds = redisTemplate.getExpire(
                memoryProperties.getRedisKeyPrefix() + appId, TimeUnit.SECONDS);
        assertThat(ttlSeconds).isPositive()
                .isLessThanOrEqualTo(memoryProperties.getSnapshotTtl().toSeconds());
        assertThat(invalidated.await(5, TimeUnit.SECONDS)).isTrue();

        RedisAppProcessingLeaseManager secondInstanceLeaseManager =
                new RedisAppProcessingLeaseManager(
                        secondInstanceTemplate, leaseProperties);
        try {
            AppProcessingLeaseManager.AppProcessingLease lease =
                    leaseManager.acquire(appId);
            try {
                assertThatThrownBy(() -> secondInstanceLeaseManager.acquire(appId))
                        .isInstanceOf(BusinessException.class);
            } finally {
                lease.close();
            }
            try (AppProcessingLeaseManager.AppProcessingLease reacquired =
                         secondInstanceLeaseManager.acquire(appId)) {
                reacquired.assertHeld();
            }
        } finally {
            secondInstanceLeaseManager.shutdown();
        }

        chatMemoryService.purge(appId);
        assertThat(secondInstanceSnapshotRepository.find(appId)).isEmpty();
        restartedSessionRepository.deleteById(session.getId());
        assertThat(sessionRepository.findById(session.getId())).isNull();
    }
}
