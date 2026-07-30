package com.jack.autocodebackend.config;

import com.jack.autocodebackend.infrastructure.redis.RedisConnectionFailureClassifier;
import com.jack.autocodebackend.infrastructure.redis.RedisDependencyAvailability;
import com.jack.autocodebackend.infrastructure.redis.RedisSessionFailureFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.RedisSerializer;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        AppChatMemoryProperties.class,
        AppProcessingLeaseProperties.class
})
public class RedisInfrastructureConfiguration {

    @Bean
    RedisConnectionFailureClassifier redisConnectionFailureClassifier() {
        return new RedisConnectionFailureClassifier();
    }

    @Bean
    RedisSessionFailureFilter redisSessionFailureFilter(
            RedisConnectionFailureClassifier failureClassifier,
            RedisDependencyAvailability availability,
            ObjectMapper objectMapper
    ) {
        return new RedisSessionFailureFilter(
                failureClassifier, availability, objectMapper);
    }

    @Bean("springSessionDefaultRedisSerializer")
    RedisSerializer<Object> springSessionDefaultRedisSerializer() {
        return new SessionRedisSerializer();
    }

    @Bean
    RedisMessageListenerContainer chatMemoryRedisMessageListenerContainer(
            RedisConnectionFactory connectionFactory
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setAutoStartup(false);
        return container;
    }
}
