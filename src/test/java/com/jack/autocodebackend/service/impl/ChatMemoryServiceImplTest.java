package com.jack.autocodebackend.service.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.jack.autocodebackend.config.AppChatMemoryProperties;
import com.jack.autocodebackend.ai.model.VueProjectFile;
import com.jack.autocodebackend.core.vue.VueProjectSourceSnapshot;
import com.jack.autocodebackend.exception.BusinessException;
import com.jack.autocodebackend.memory.ChatMemoryInvalidationBus;
import com.jack.autocodebackend.memory.ChatMemoryMessage;
import com.jack.autocodebackend.memory.ChatMemoryPromptBuilder;
import com.jack.autocodebackend.memory.ChatMemoryRole;
import com.jack.autocodebackend.memory.ChatMemorySnapshot;
import com.jack.autocodebackend.memory.ChatMemorySnapshotRepository;
import com.jack.autocodebackend.memory.ChatMemoryStoreException;
import com.jack.autocodebackend.memory.VersionedChatMemorySnapshot;
import com.jack.autocodebackend.model.domain.ChatHistory;
import com.jack.autocodebackend.service.ChatHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ChatMemoryServiceImplTest {

    private static final long APP_ID = 5_000_000_000L;

    private final ChatHistoryService chatHistoryService = mock(ChatHistoryService.class);

    private final FakeSnapshotRepository repository = new FakeSnapshotRepository();

    private final FakeInvalidationBus invalidationBus = new FakeInvalidationBus();

    private final AppChatMemoryProperties properties = properties(Duration.ofMinutes(10));

    private ChatMemoryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = serviceWithCache(Caffeine.newBuilder()
                .maximumSize(100)
                .expireAfterWrite(properties.getCacheTtl())
                .build());
    }

    @Test
    void initialGenerationBypassesEveryMemoryTierAndPreservesMessage() {
        String message = "  initial\nmessage  ";

        assertThat(service.buildPrompt(APP_ID, null, message, true)).isEqualTo(message);

        verifyNoInteractions(chatHistoryService);
        assertThat(repository.findCalls).isZero();
        assertThat(repository.versionCalls).isZero();
    }

    @Test
    void usesRedisThenVersionValidatedNearCacheAndExcludesCurrentRecord() {
        repository.put(snapshot(
                APP_ID,
                message(1L, ChatMemoryRole.USER, "first"),
                message(3L, ChatMemoryRole.AI, "third"),
                message(5L, ChatMemoryRole.USER, "current")));

        String first = service.buildPrompt(APP_ID, 5L, "next", false);
        String second = service.buildPrompt(APP_ID, 5L, "next", false);

        assertThat(first).isEqualTo(second)
                .contains("first", "third", "next")
                .doesNotContain("\"content\":\"current\"");
        assertThat(repository.findCalls).isEqualTo(1);
        assertThat(repository.versionCalls).isEqualTo(1);
        verifyNoInteractions(chatHistoryService);
    }

    @Test
    void versionMismatchReloadsRedisAndCrossInstanceInvalidationEvictsLocalEntry() {
        repository.put(snapshot(APP_ID, message(1L, ChatMemoryRole.USER, "old")));
        assertThat(service.loadSnapshot(APP_ID, 10L).messages().getFirst().content())
                .isEqualTo("old");

        repository.put(snapshot(APP_ID, message(2L, ChatMemoryRole.AI, "new")));
        assertThat(service.loadSnapshot(APP_ID, 10L).messages().getFirst().content())
                .isEqualTo("new");
        assertThat(repository.findCalls).isEqualTo(2);
        assertThat(repository.versionCalls).isEqualTo(1);

        invalidationBus.invalidate(APP_ID);
        service.loadSnapshot(APP_ID, 10L);
        assertThat(repository.findCalls).isEqualTo(3);
    }

    @Test
    void redisFailureFallsBackToExclusiveMysqlWithoutTrustingLocalState() {
        repository.failReads = true;
        repository.failWrites = true;
        List<ChatHistory> records = List.of(
                history(8L, "ai", "reply"),
                history(7L, "user", "request"));
        given(chatHistoryService.listLatestForMemory(APP_ID, 9L, 10))
                .willReturn(records);

        ChatMemorySnapshot first = service.loadSnapshot(APP_ID, 9L);
        ChatMemorySnapshot second = service.loadSnapshot(APP_ID, 9L);

        assertThat(first).isEqualTo(second);
        assertThat(first.messages()).extracting(ChatMemoryMessage::historyId)
                .containsExactly(7L, 8L);
        verify(chatHistoryService, org.mockito.Mockito.times(2))
                .listLatestForMemory(APP_ID, 9L, 10);
    }

    @Test
    void coldEmptySnapshotIsPersistedAndMysqlFailurePropagates() {
        given(chatHistoryService.listLatestForMemory(APP_ID, 11L, 10))
                .willReturn(List.of());

        ChatMemorySnapshot empty = service.loadSnapshot(APP_ID, 11L);

        assertThat(empty.messages()).isEmpty();
        assertThat(repository.saved.get(APP_ID).snapshot()).isEqualTo(empty);

        repository.saved.clear();
        service.invalidate(APP_ID);
        given(chatHistoryService.listLatestForMemory(APP_ID, 12L, 10))
                .willThrow(new BusinessException(
                        com.jack.autocodebackend.exception.ErrorCode.OPERATION_ERROR));
        assertThatThrownBy(() -> service.loadSnapshot(APP_ID, 12L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void cacheExpiryForcesRedisReload() {
        AtomicLong tickerNanos = new AtomicLong();
        Ticker ticker = tickerNanos::get;
        Cache<Long, VersionedChatMemorySnapshot> cache = Caffeine.newBuilder()
                .maximumSize(100)
                .expireAfterWrite(Duration.ofSeconds(1))
                .ticker(ticker)
                .build();
        service = serviceWithCache(cache);
        repository.put(snapshot(APP_ID, message(1L, ChatMemoryRole.USER, "cached")));

        service.loadSnapshot(APP_ID, 10L);
        tickerNanos.addAndGet(TimeUnit.SECONDS.toNanos(2));
        cache.cleanUp();
        service.loadSnapshot(APP_ID, 10L);

        assertThat(repository.findCalls).isEqualTo(2);
        assertThat(repository.versionCalls).isZero();
    }

    @Test
    void refreshIsRecoverableWhilePurgeFailureIsRequired() {
        given(chatHistoryService.listLatestForMemory(APP_ID, null, 10))
                .willThrow(new IllegalStateException("database unavailable"));

        service.refresh(APP_ID);

        repository.failDeletes = true;
        assertThatThrownBy(() -> service.purge(APP_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage("清理对话记忆失败");
    }

    @Test
    void sourceContextIsRequestLocalAndNeverStoredInRedisSnapshots() {
        repository.put(snapshot(APP_ID,
                message(1L, ChatMemoryRole.USER, "history-only")));
        VueProjectSourceSnapshot source = new VueProjectSourceSnapshot(List.of(
                new VueProjectFile("src/App.vue", "<template>source-only</template>")), 32);

        String prompt = service.buildPrompt(APP_ID, 2L, "current-only", false, source);

        assertThat(prompt).contains("history-only", "source-only", "current-only");
        assertThat(repository.saved.get(APP_ID).snapshot().messages())
                .extracting(ChatMemoryMessage::content)
                .containsExactly("history-only")
                .doesNotContain("source-only");
        assertThatThrownBy(() -> service.buildPrompt(
                APP_ID, null, "initial", true, source))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("initial generation");
    }

    private ChatMemoryServiceImpl serviceWithCache(
            Cache<Long, VersionedChatMemorySnapshot> cache
    ) {
        return new ChatMemoryServiceImpl(
                chatHistoryService,
                repository,
                invalidationBus,
                new ChatMemoryPromptBuilder(properties, JsonMapper.builder().build()),
                properties,
                cache
        );
    }

    private static AppChatMemoryProperties properties(Duration cacheTtl) {
        return new AppChatMemoryProperties(
                10, 6000, 24000, 262144, Duration.ofDays(7), 100,
                cacheTtl, "test:memory:", "test:invalidation");
    }

    private static ChatMemoryMessage message(long id, ChatMemoryRole role, String content) {
        return new ChatMemoryMessage(id, role, content);
    }

    private static ChatMemorySnapshot snapshot(
            long appId,
            ChatMemoryMessage... messages
    ) {
        List<ChatMemoryMessage> list = List.of(messages);
        return new ChatMemorySnapshot(1, appId, list.getLast().historyId(), list);
    }

    private static ChatHistory history(long id, String role, String content) {
        ChatHistory history = new ChatHistory();
        history.setId(id);
        history.setAppId(APP_ID);
        history.setUserId(1001L);
        history.setMessageType(role);
        history.setMessage(content);
        return history;
    }

    private static final class FakeSnapshotRepository
            implements ChatMemorySnapshotRepository {

        private final Map<Long, VersionedChatMemorySnapshot> saved = new HashMap<>();

        private int findCalls;

        private int versionCalls;

        private boolean failReads;

        private boolean failWrites;

        private boolean failDeletes;

        @Override
        public Optional<VersionedChatMemorySnapshot> find(long appId) {
            findCalls++;
            if (failReads) {
                throw new ChatMemoryStoreException("Redis unavailable");
            }
            return Optional.ofNullable(saved.get(appId));
        }

        @Override
        public OptionalLong findVersion(long appId) {
            versionCalls++;
            if (failReads) {
                throw new ChatMemoryStoreException("Redis unavailable");
            }
            VersionedChatMemorySnapshot versioned = saved.get(appId);
            return versioned == null
                    ? OptionalLong.empty()
                    : OptionalLong.of(versioned.version());
        }

        @Override
        public VersionedChatMemorySnapshot save(ChatMemorySnapshot snapshot) {
            if (failWrites) {
                throw new ChatMemoryStoreException("Redis unavailable");
            }
            VersionedChatMemorySnapshot versioned = new VersionedChatMemorySnapshot(
                    snapshot.lastHistoryId(), snapshot);
            saved.put(snapshot.appId(), versioned);
            return versioned;
        }

        @Override
        public void delete(long appId) {
            if (failDeletes) {
                throw new ChatMemoryStoreException("Redis unavailable");
            }
            saved.remove(appId);
        }

        void put(ChatMemorySnapshot snapshot) {
            save(snapshot);
        }
    }

    private static final class FakeInvalidationBus implements ChatMemoryInvalidationBus {

        private final List<LongConsumer> listeners = new ArrayList<>();

        @Override
        public void register(LongConsumer listener) {
            listeners.add(listener);
        }

        @Override
        public void ensureListening() {
        }

        @Override
        public void publishRefresh(long appId, long version) {
        }

        @Override
        public void publishDelete(long appId) {
        }

        void invalidate(long appId) {
            listeners.forEach(listener -> listener.accept(appId));
        }
    }
}
