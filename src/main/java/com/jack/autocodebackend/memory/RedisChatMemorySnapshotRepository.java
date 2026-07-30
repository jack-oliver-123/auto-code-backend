package com.jack.autocodebackend.memory;

import com.jack.autocodebackend.config.AppChatMemoryProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

@Repository
public class RedisChatMemorySnapshotRepository implements ChatMemorySnapshotRepository {

    private static final int ENTRY_OVERHEAD_CHARS = 32;

    private static final String VERSION_FIELD = "version";

    private static final String PAYLOAD_FIELD = "payload";

    static final DefaultRedisScript<Long> SAVE_SCRIPT = new DefaultRedisScript<>("""
            redis.call('del', KEYS[1])
            redis.call('hset', KEYS[1], ARGV[1], ARGV[2], ARGV[3], ARGV[4])
            redis.call('pexpire', KEYS[1], ARGV[5])
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper;

    private final AppChatMemoryProperties properties;

    private final ChatMemoryInvalidationBus invalidationBus;

    public RedisChatMemorySnapshotRepository(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            AppChatMemoryProperties properties,
            ChatMemoryInvalidationBus invalidationBus
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.invalidationBus = invalidationBus;
    }

    @Override
    public Optional<VersionedChatMemorySnapshot> find(long appId) {
        requirePositiveAppId(appId);
        try {
            String payload = redisTemplate.<String, String>opsForHash()
                    .get(key(appId), PAYLOAD_FIELD);
            if (payload == null) {
                return Optional.empty();
            }
            return Optional.of(deserialize(appId, payload));
        } catch (ChatMemoryStoreException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ChatMemoryStoreException("failed to read chat-memory snapshot", exception);
        }
    }

    @Override
    public OptionalLong findVersion(long appId) {
        requirePositiveAppId(appId);
        try {
            String version = redisTemplate.<String, String>opsForHash()
                    .get(key(appId), VERSION_FIELD);
            if (version == null) {
                return OptionalLong.empty();
            }
            long parsedVersion = Long.parseLong(version);
            if (parsedVersion < 0) {
                throw new IllegalArgumentException("version must not be negative");
            }
            return OptionalLong.of(parsedVersion);
        } catch (ChatMemoryStoreException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ChatMemoryStoreException(
                    "failed to read chat-memory snapshot version", exception);
        }
    }

    @Override
    public VersionedChatMemorySnapshot save(ChatMemorySnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        VersionedChatMemorySnapshot versioned = new VersionedChatMemorySnapshot(
                snapshot.lastHistoryId(), snapshot);
        try {
            String payload = serialize(versioned);
            Long stored = redisTemplate.execute(
                    SAVE_SCRIPT,
                    List.of(key(snapshot.appId())),
                    VERSION_FIELD,
                    Long.toString(versioned.version()),
                    PAYLOAD_FIELD,
                    payload,
                    Long.toString(properties.getSnapshotTtl().toMillis())
            );
            if (!Long.valueOf(1L).equals(stored)) {
                throw new ChatMemoryStoreException(
                        "failed to atomically save chat-memory snapshot");
            }
            invalidationBus.publishRefresh(snapshot.appId(), versioned.version());
            return versioned;
        } catch (ChatMemoryStoreException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ChatMemoryStoreException("failed to save chat-memory snapshot", exception);
        }
    }

    @Override
    public void delete(long appId) {
        requirePositiveAppId(appId);
        try {
            Boolean deleted = redisTemplate.delete(key(appId));
            if (deleted == null) {
                throw new ChatMemoryStoreException(
                        "chat-memory snapshot deletion was not confirmed");
            }
            invalidationBus.publishDelete(appId);
        } catch (ChatMemoryStoreException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ChatMemoryStoreException("failed to delete chat-memory snapshot", exception);
        }
    }

    private String serialize(VersionedChatMemorySnapshot versioned) {
        ChatMemorySnapshot snapshot = versioned.snapshot();
        validateBounds(snapshot);
        StoredSnapshot stored = new StoredSnapshot(
                snapshot.schemaVersion(),
                versioned.version(),
                snapshot.appId(),
                snapshot.lastHistoryId(),
                snapshot.messages().stream()
                        .map(message -> new StoredMessage(
                                message.historyId(),
                                message.role().getValue(),
                                message.content()))
                        .toList()
        );
        try {
            String payload = objectMapper.writeValueAsString(stored);
            validatePayloadSize(payload);
            return payload;
        } catch (JacksonException exception) {
            throw new ChatMemoryStoreException(
                    "failed to serialize chat-memory snapshot", exception);
        }
    }

    private VersionedChatMemorySnapshot deserialize(long expectedAppId, String payload) {
        validatePayloadSize(payload);
        try {
            StoredSnapshot stored = objectMapper.readValue(payload, StoredSnapshot.class);
            if (stored == null || stored.messages() == null) {
                throw new IllegalArgumentException("missing chat-memory fields");
            }
            List<ChatMemoryMessage> messages = stored.messages().stream()
                    .map(message -> new ChatMemoryMessage(
                            message.historyId(),
                            ChatMemoryRole.fromValue(message.role()),
                            message.content()))
                    .toList();
            ChatMemorySnapshot snapshot = new ChatMemorySnapshot(
                    stored.schemaVersion(),
                    stored.appId(),
                    stored.lastHistoryId(),
                    messages
            );
            if (snapshot.appId() != expectedAppId) {
                throw new IllegalArgumentException("chat-memory app id mismatch");
            }
            validateBounds(snapshot);
            return new VersionedChatMemorySnapshot(stored.revision(), snapshot);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new ChatMemoryStoreException("malformed chat-memory snapshot", exception);
        }
    }

    private void validateBounds(ChatMemorySnapshot snapshot) {
        if (snapshot.messages().size() > properties.getHistoryLimit()) {
            throw new ChatMemoryStoreException("chat-memory history limit exceeded");
        }
        int totalChars = 0;
        for (ChatMemoryMessage message : snapshot.messages()) {
            if (message.content().length() > properties.getMessageMaxChars()) {
                throw new ChatMemoryStoreException("chat-memory message limit exceeded");
            }
            totalChars += message.content().length() + ENTRY_OVERHEAD_CHARS;
            if (totalChars > properties.getTotalMaxChars()) {
                throw new ChatMemoryStoreException("chat-memory total limit exceeded");
            }
        }
    }

    private void validatePayloadSize(String payload) {
        if (payload == null
                || payload.getBytes(StandardCharsets.UTF_8).length
                > properties.getPayloadMaxBytes()) {
            throw new ChatMemoryStoreException("chat-memory payload limit exceeded");
        }
    }

    private String key(long appId) {
        return properties.getRedisKeyPrefix() + appId;
    }

    private void requirePositiveAppId(long appId) {
        if (appId <= 0) {
            throw new IllegalArgumentException("appId must be positive");
        }
    }

    private record StoredMessage(long historyId, String role, String content) {
    }

    private record StoredSnapshot(
            int schemaVersion,
            long revision,
            long appId,
            long lastHistoryId,
            List<StoredMessage> messages
    ) {
    }
}
