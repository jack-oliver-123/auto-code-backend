package com.jack.autocodebackend.service.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.jack.autocodebackend.config.AppChatMemoryProperties;
import com.jack.autocodebackend.core.vue.VueProjectSourceSnapshot;
import com.jack.autocodebackend.exception.BusinessException;
import com.jack.autocodebackend.exception.ErrorCode;
import com.jack.autocodebackend.memory.ChatMemoryInvalidationBus;
import com.jack.autocodebackend.memory.ChatMemoryMessage;
import com.jack.autocodebackend.memory.ChatMemoryPromptBuilder;
import com.jack.autocodebackend.memory.ChatMemorySnapshot;
import com.jack.autocodebackend.memory.ChatMemorySnapshotRepository;
import com.jack.autocodebackend.memory.VersionedChatMemorySnapshot;
import com.jack.autocodebackend.service.ChatHistoryService;
import com.jack.autocodebackend.service.ChatMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

@Service
public class ChatMemoryServiceImpl implements ChatMemoryService {

    private static final Logger log = LoggerFactory.getLogger(ChatMemoryServiceImpl.class);

    private final ChatHistoryService chatHistoryService;

    private final ChatMemorySnapshotRepository snapshotRepository;

    private final ChatMemoryInvalidationBus invalidationBus;

    private final ChatMemoryPromptBuilder promptBuilder;

    private final AppChatMemoryProperties properties;

    private final Cache<Long, VersionedChatMemorySnapshot> nearCache;

    @Autowired
    public ChatMemoryServiceImpl(
            ChatHistoryService chatHistoryService,
            ChatMemorySnapshotRepository snapshotRepository,
            ChatMemoryInvalidationBus invalidationBus,
            ChatMemoryPromptBuilder promptBuilder,
            AppChatMemoryProperties properties
    ) {
        this(
                chatHistoryService,
                snapshotRepository,
                invalidationBus,
                promptBuilder,
                properties,
                Caffeine.newBuilder()
                        .maximumSize(properties.getCacheMaximumSize())
                        .expireAfterWrite(properties.getCacheTtl())
                        .build()
        );
    }

    ChatMemoryServiceImpl(
            ChatHistoryService chatHistoryService,
            ChatMemorySnapshotRepository snapshotRepository,
            ChatMemoryInvalidationBus invalidationBus,
            ChatMemoryPromptBuilder promptBuilder,
            AppChatMemoryProperties properties,
            Cache<Long, VersionedChatMemorySnapshot> nearCache
    ) {
        this.chatHistoryService = chatHistoryService;
        this.snapshotRepository = snapshotRepository;
        this.invalidationBus = invalidationBus;
        this.promptBuilder = promptBuilder;
        this.properties = properties;
        this.nearCache = nearCache;
        invalidationBus.register(this::evictLocal);
    }

    @Override
    public String buildPrompt(
            Long appId,
            Long beforeId,
            String currentMessage,
            boolean initialGeneration
    ) {
        return buildPrompt(appId, beforeId, currentMessage, initialGeneration, null);
    }

    @Override
    public String buildPrompt(
            Long appId,
            Long beforeId,
            String currentMessage,
            boolean initialGeneration,
            VueProjectSourceSnapshot sourceSnapshot
    ) {
        long validAppId = requirePositiveId(appId, "appId");
        if (currentMessage == null || currentMessage.isEmpty()) {
            throw new IllegalArgumentException("currentMessage must not be empty");
        }
        if (initialGeneration) {
            if (sourceSnapshot != null) {
                throw new IllegalArgumentException(
                        "initial generation must not contain project source");
            }
            log.debug("Bypassed chat memory for initial generation of app {}", validAppId);
            return currentMessage;
        }
        long validBeforeId = requirePositiveId(beforeId, "beforeId");
        return promptBuilder.buildPrompt(
                loadSnapshot(validAppId, validBeforeId), sourceSnapshot, currentMessage);
    }

    @Override
    public ChatMemorySnapshot loadSnapshot(Long appId, Long beforeId) {
        long validAppId = requirePositiveId(appId, "appId");
        Long validBeforeId = beforeId == null
                ? null
                : requirePositiveId(beforeId, "beforeId");
        tryStartInvalidationListener();

        try {
            VersionedChatMemorySnapshot cached = nearCache.getIfPresent(validAppId);
            if (cached != null) {
                OptionalLong redisVersion = snapshotRepository.findVersion(validAppId);
                if (redisVersion.isPresent()
                        && redisVersion.getAsLong() == cached.version()) {
                    log.debug("Chat-memory near-cache hit for app {}", validAppId);
                    return filterBefore(cached.snapshot(), validBeforeId);
                }
                evictLocal(validAppId);
                log.debug("Chat-memory near-cache version mismatch for app {}", validAppId);
            }

            Optional<VersionedChatMemorySnapshot> redisSnapshot =
                    snapshotRepository.find(validAppId);
            if (redisSnapshot.isPresent()) {
                VersionedChatMemorySnapshot versioned = redisSnapshot.get();
                if (versioned.snapshot().appId() != validAppId) {
                    throw new IllegalStateException("chat-memory app id mismatch");
                }
                nearCache.put(validAppId, versioned);
                log.debug("Chat-memory Redis hit for app {}", validAppId);
                return filterBefore(versioned.snapshot(), validBeforeId);
            }
            log.debug("Chat-memory Redis miss for app {}", validAppId);
        } catch (RuntimeException redisFailure) {
            evictLocal(validAppId);
            log.warn("Chat-memory Redis read failed for app {}; recovering from MySQL",
                    validAppId);
        }

        ChatMemorySnapshot recovered = rebuildFromHistory(validAppId, validBeforeId);
        refillCachesBestEffort(recovered);
        log.info("Recovered chat memory from MySQL for app {}", validAppId);
        return recovered;
    }

    @Override
    public void refresh(Long appId) {
        long validAppId = requirePositiveId(appId, "appId");
        try {
            ChatMemorySnapshot snapshot = rebuildFromHistory(validAppId, null);
            VersionedChatMemorySnapshot saved = snapshotRepository.save(snapshot);
            nearCache.put(validAppId, saved);
            log.debug("Refreshed chat memory for app {}", validAppId);
        } catch (RuntimeException refreshFailure) {
            evictLocal(validAppId);
            log.warn("Chat-memory refresh failed for app {}; local entry evicted", validAppId);
        }
    }

    @Override
    public void invalidate(Long appId) {
        evictLocal(requirePositiveId(appId, "appId"));
    }

    @Override
    public void purge(Long appId) {
        long validAppId = requirePositiveId(appId, "appId");
        evictLocal(validAppId);
        try {
            snapshotRepository.delete(validAppId);
            log.info("Purged chat memory for app {}", validAppId);
        } catch (RuntimeException purgeFailure) {
            BusinessException exception = new BusinessException(
                    ErrorCode.OPERATION_ERROR, "清理对话记忆失败");
            exception.initCause(purgeFailure);
            throw exception;
        }
    }

    private ChatMemorySnapshot rebuildFromHistory(long appId, Long beforeId) {
        return promptBuilder.fromHistory(
                appId,
                chatHistoryService.listLatestForMemory(
                        appId, beforeId, properties.getHistoryLimit())
        );
    }

    private void refillCachesBestEffort(ChatMemorySnapshot snapshot) {
        try {
            VersionedChatMemorySnapshot saved = snapshotRepository.save(snapshot);
            nearCache.put(snapshot.appId(), saved);
        } catch (RuntimeException redisFailure) {
            evictLocal(snapshot.appId());
            log.warn("Chat-memory Redis refill failed for app {}", snapshot.appId());
        }
    }

    private ChatMemorySnapshot filterBefore(
            ChatMemorySnapshot snapshot,
            Long beforeId
    ) {
        if (beforeId == null || snapshot.lastHistoryId() < beforeId) {
            return snapshot;
        }
        List<ChatMemoryMessage> messages = snapshot.messages().stream()
                .filter(message -> message.historyId() < beforeId)
                .toList();
        if (messages.isEmpty()) {
            return ChatMemorySnapshot.empty(snapshot.appId());
        }
        return new ChatMemorySnapshot(
                snapshot.schemaVersion(),
                snapshot.appId(),
                messages.getLast().historyId(),
                messages
        );
    }

    private void tryStartInvalidationListener() {
        try {
            invalidationBus.ensureListening();
        } catch (RuntimeException listenerFailure) {
            log.warn("Chat-memory invalidation listener is unavailable");
        }
    }

    private void evictLocal(long appId) {
        if (appId > 0) {
            nearCache.invalidate(appId);
        }
    }

    private long requirePositiveId(Long id, String name) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return id;
    }
}
