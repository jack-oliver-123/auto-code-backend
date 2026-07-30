package com.jack.autocodebackend.memory;

import com.jack.autocodebackend.config.AppChatMemoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongConsumer;

@Component
public class RedisChatMemoryInvalidationBus implements ChatMemoryInvalidationBus {

    private static final Logger log =
            LoggerFactory.getLogger(RedisChatMemoryInvalidationBus.class);

    private static final String REFRESH_PREFIX = "refresh:";

    private static final String DELETE_PREFIX = "delete:";

    private final StringRedisTemplate redisTemplate;

    private final RedisMessageListenerContainer listenerContainer;

    private final String invalidationChannel;

    private final List<LongConsumer> listeners = new CopyOnWriteArrayList<>();

    private final Object lifecycleMonitor = new Object();

    public RedisChatMemoryInvalidationBus(
            StringRedisTemplate redisTemplate,
            @Qualifier("chatMemoryRedisMessageListenerContainer")
            RedisMessageListenerContainer listenerContainer,
            AppChatMemoryProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.listenerContainer = listenerContainer;
        this.invalidationChannel = properties.getInvalidationChannel();
        ChannelTopic topic = new ChannelTopic(invalidationChannel);
        listenerContainer.addMessageListener(
                (message, pattern) -> handleMessage(message.getBody()),
                topic
        );
    }

    @Override
    public void register(LongConsumer listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        listeners.add(listener);
    }

    @Override
    public void ensureListening() {
        if (listenerContainer.isRunning()) {
            return;
        }
        synchronized (lifecycleMonitor) {
            if (!listenerContainer.isRunning()) {
                listenerContainer.start();
                log.info("Chat-memory invalidation listener started");
            }
        }
    }

    @Override
    public void publishRefresh(long appId, long version) {
        requirePositiveAppId(appId);
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        publish(REFRESH_PREFIX + appId + ':' + version);
    }

    @Override
    public void publishDelete(long appId) {
        requirePositiveAppId(appId);
        publish(DELETE_PREFIX + appId);
    }

    private void publish(String message) {
        try {
            ensureListening();
            redisTemplate.convertAndSend(invalidationChannel, message);
        } catch (RuntimeException exception) {
            throw new ChatMemoryStoreException(
                    "failed to publish chat-memory invalidation", exception);
        }
    }

    private void handleMessage(byte[] body) {
        if (body == null || body.length == 0 || body.length > 128) {
            log.warn("Ignored malformed chat-memory invalidation message");
            return;
        }
        String message = new String(body, StandardCharsets.UTF_8);
        try {
            long appId = parseAppId(message);
            for (LongConsumer listener : listeners) {
                listener.accept(appId);
            }
            log.debug("Invalidated local chat memory for app {}", appId);
        } catch (RuntimeException exception) {
            log.warn("Ignored malformed chat-memory invalidation message");
        }
    }

    private long parseAppId(String message) {
        String appIdText;
        if (message.startsWith(DELETE_PREFIX)) {
            appIdText = message.substring(DELETE_PREFIX.length());
        } else if (message.startsWith(REFRESH_PREFIX)) {
            int versionSeparator = message.indexOf(':', REFRESH_PREFIX.length());
            if (versionSeparator < 0) {
                throw new IllegalArgumentException("missing version");
            }
            appIdText = message.substring(REFRESH_PREFIX.length(), versionSeparator);
            long version = Long.parseLong(message.substring(versionSeparator + 1));
            if (version < 0) {
                throw new IllegalArgumentException("invalid version");
            }
        } else {
            throw new IllegalArgumentException("unknown invalidation action");
        }
        long appId = Long.parseLong(appIdText);
        requirePositiveAppId(appId);
        return appId;
    }

    private void requirePositiveAppId(long appId) {
        if (appId <= 0) {
            throw new IllegalArgumentException("appId must be positive");
        }
    }
}
