package com.jack.autocodebackend.memory;

import com.jack.autocodebackend.config.AppChatMemoryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RedisChatMemorySnapshotRepositoryTest {

    private static final long APP_ID = 5_000_000_000L;

    private static final String KEY = "test:memory:" + APP_ID;

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final HashOperations<String, String, String> hashOperations =
            mock(HashOperations.class);

    private final ChatMemoryInvalidationBus invalidationBus =
            mock(ChatMemoryInvalidationBus.class);

    private final AppChatMemoryProperties properties = new AppChatMemoryProperties(
            10, 6000, 24000, 262144, Duration.ofDays(7), 100,
            Duration.ofMinutes(10), "test:memory:", "test:invalidation");

    private RedisChatMemorySnapshotRepository repository;

    @BeforeEach
    void setUp() {
        given(redisTemplate.<String, String>opsForHash()).willReturn(hashOperations);
        given(redisTemplate.execute(
                eq(RedisChatMemorySnapshotRepository.SAVE_SCRIPT),
                anyList(),
                any(Object[].class)
        )).willReturn(1L);
        repository = new RedisChatMemorySnapshotRepository(
                redisTemplate,
                JsonMapper.builder().build(),
                properties,
                invalidationBus
        );
    }

    @Test
    void atomicallyRoundTripsVersionedSnapshotWithTtlAndTypedRoles() {
        ChatMemorySnapshot snapshot = snapshot(APP_ID, 9L);

        VersionedChatMemorySnapshot saved = repository.save(snapshot);

        Object[] arguments = capturedSaveArguments(KEY);
        assertThat(arguments[0]).isEqualTo("version");
        assertThat(arguments[1]).isEqualTo("9");
        assertThat(arguments[2]).isEqualTo("payload");
        assertThat(arguments[3].toString()).contains(
                "\"schemaVersion\":1", "\"role\":\"user\"")
                .doesNotContain("USER");
        assertThat(arguments[4]).isEqualTo(
                Long.toString(Duration.ofDays(7).toMillis()));
        verify(invalidationBus).publishRefresh(APP_ID, 9L);

        given(hashOperations.get(KEY, "payload"))
                .willReturn(arguments[3].toString());
        given(hashOperations.get(KEY, "version")).willReturn("9");

        assertThat(repository.find(APP_ID)).contains(saved);
        clearInvocations(hashOperations);
        assertThat(repository.findVersion(APP_ID)).hasValue(9L);
        verify(hashOperations).get(KEY, "version");
        verify(hashOperations, never()).get(KEY, "payload");
    }

    @Test
    void rejectsMalformedOversizedCrossApplicationAndInvalidVersionData() {
        given(hashOperations.get(KEY, "payload"))
                .willReturn("not-json")
                .willReturn("x".repeat(properties.getPayloadMaxBytes() + 1));

        assertThatThrownBy(() -> repository.find(APP_ID))
                .isInstanceOf(ChatMemoryStoreException.class);
        assertThatThrownBy(() -> repository.find(APP_ID))
                .isInstanceOf(ChatMemoryStoreException.class);

        ChatMemorySnapshot foreign = snapshot(APP_ID + 1, 9L);
        repository.save(foreign);
        Object[] arguments = capturedSaveArguments("test:memory:" + (APP_ID + 1));
        given(hashOperations.get(KEY, "payload"))
                .willReturn(arguments[3].toString());
        assertThatThrownBy(() -> repository.find(APP_ID))
                .isInstanceOf(ChatMemoryStoreException.class);

        given(hashOperations.get(KEY, "version")).willReturn("invalid");
        assertThatThrownBy(() -> repository.findVersion(APP_ID))
                .isInstanceOf(ChatMemoryStoreException.class);
    }

    @Test
    void rejectsFailedAtomicWriteBeforePublishingInvalidation() {
        given(redisTemplate.execute(
                eq(RedisChatMemorySnapshotRepository.SAVE_SCRIPT),
                anyList(),
                any(Object[].class)
        )).willReturn(0L);

        assertThatThrownBy(() -> repository.save(snapshot(APP_ID, 9L)))
                .isInstanceOf(ChatMemoryStoreException.class);

        verify(invalidationBus, never()).publishRefresh(APP_ID, 9L);
    }

    @Test
    void deletionIsIdempotentAndPublishesContentFreeInvalidation() {
        given(redisTemplate.delete(KEY)).willReturn(false);

        repository.delete(APP_ID);

        verify(redisTemplate).delete(KEY);
        verify(invalidationBus).publishDelete(APP_ID);
    }

    @Test
    void rejectsUnconfirmedDeletionWithoutPublishingInvalidation() {
        given(redisTemplate.delete(KEY)).willReturn(null);

        assertThatThrownBy(() -> repository.delete(APP_ID))
                .isInstanceOf(ChatMemoryStoreException.class);

        verify(invalidationBus, never()).publishDelete(APP_ID);
    }

    private Object[] capturedSaveArguments(String key) {
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate).execute(
                eq(RedisChatMemorySnapshotRepository.SAVE_SCRIPT),
                eq(List.of(key)),
                arguments.capture()
        );
        return arguments.getValue();
    }

    private static ChatMemorySnapshot snapshot(long appId, long id) {
        return new ChatMemorySnapshot(
                1,
                appId,
                id,
                List.of(new ChatMemoryMessage(
                        id, ChatMemoryRole.USER, "message"))
        );
    }
}
