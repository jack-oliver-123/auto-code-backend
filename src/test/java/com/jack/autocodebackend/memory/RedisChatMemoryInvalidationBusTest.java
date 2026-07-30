package com.jack.autocodebackend.memory;

import com.jack.autocodebackend.config.AppChatMemoryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RedisChatMemoryInvalidationBusTest {

    private static final long APP_ID = 5_000_000_000L;

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

    private final RedisMessageListenerContainer container =
            mock(RedisMessageListenerContainer.class);

    private final ArgumentCaptor<MessageListener> listenerCaptor =
            ArgumentCaptor.forClass(MessageListener.class);

    private RedisChatMemoryInvalidationBus bus;

    @BeforeEach
    void setUp() {
        AppChatMemoryProperties properties = new AppChatMemoryProperties(
                10, 6000, 24000, 262144, Duration.ofDays(7), 100,
                Duration.ofMinutes(10), "test:memory:", "test:invalidation");
        bus = new RedisChatMemoryInvalidationBus(
                redisTemplate, container, properties);
        verify(container).addMessageListener(
                listenerCaptor.capture(), any(ChannelTopic.class));
    }

    @Test
    void publishesContentFreeRefreshAndDeleteMessages() {
        given(container.isRunning()).willReturn(true);

        bus.publishRefresh(APP_ID, 42L);
        bus.publishDelete(APP_ID);

        verify(redisTemplate).convertAndSend(
                "test:invalidation", "refresh:" + APP_ID + ":42");
        verify(redisTemplate).convertAndSend(
                "test:invalidation", "delete:" + APP_ID);
    }

    @Test
    void startsListenerLazilyAndValidatesInboundApplicationIds() {
        given(container.isRunning()).willReturn(false, false, true);
        AtomicLong invalidatedAppId = new AtomicLong();
        bus.register(invalidatedAppId::set);

        bus.ensureListening();
        deliver("refresh:" + APP_ID + ":8");

        verify(container).start();
        assertThat(invalidatedAppId).hasValue(APP_ID);

        invalidatedAppId.set(0);
        deliver("delete:0");
        deliver("refresh:not-an-id:8");
        deliver("message content that must never be accepted");
        assertThat(invalidatedAppId).hasValue(0);
    }

    @Test
    void rejectsInvalidOutboundIdsBeforeRedisAccess() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> bus.publishDelete(0))
                .isInstanceOf(IllegalArgumentException.class);

        verify(redisTemplate, never()).convertAndSend(any(), any());
    }

    private void deliver(String payload) {
        Message message = mock(Message.class);
        given(message.getBody()).willReturn(payload.getBytes(StandardCharsets.UTF_8));
        listenerCaptor.getValue().onMessage(message, null);
    }
}
