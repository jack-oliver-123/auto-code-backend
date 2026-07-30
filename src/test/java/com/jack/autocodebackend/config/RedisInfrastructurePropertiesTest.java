package com.jack.autocodebackend.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisInfrastructurePropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void bindsDocumentedDefaults() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            AppChatMemoryProperties memory = context.getBean(
                    AppChatMemoryProperties.class);
            assertThat(memory.getHistoryLimit()).isEqualTo(10);
            assertThat(memory.getMessageMaxChars()).isEqualTo(6000);
            assertThat(memory.getTotalMaxChars()).isEqualTo(24000);
            assertThat(memory.getPayloadMaxBytes()).isEqualTo(262144);
            assertThat(memory.getSnapshotTtl()).isEqualTo(Duration.ofDays(7));
            assertThat(memory.getCacheMaximumSize()).isEqualTo(1000);
            assertThat(memory.getCacheTtl()).isEqualTo(Duration.ofMinutes(10));

            AppProcessingLeaseProperties lease = context.getBean(
                    AppProcessingLeaseProperties.class);
            assertThat(lease.getDuration()).isEqualTo(Duration.ofSeconds(30));
            assertThat(lease.getRenewalInterval()).isEqualTo(Duration.ofSeconds(10));
        });
    }

    @Test
    void rejectsNonPositiveAndInternallyInconsistentValues() {
        assertBindingFailure("app.chat-memory.history-limit=0");
        assertBindingFailure("app.chat-memory.history-limit=101");
        assertBindingFailure("app.chat-memory.message-max-chars=-1");
        assertBindingFailure("app.chat-memory.total-max-chars=10",
                "app.chat-memory.message-max-chars=11");
        assertBindingFailure("app.chat-memory.payload-max-bytes=0");
        assertBindingFailure("app.chat-memory.snapshot-ttl=0s");
        assertBindingFailure("app.chat-memory.snapshot-ttl=1ns");
        assertBindingFailure("app.chat-memory.cache-maximum-size=0");
        assertBindingFailure("app.chat-memory.cache-ttl=-1s");
        assertBindingFailure("app.chat-memory.cache-ttl=1ns");
        assertBindingFailure("app.chat-memory.redis-key-prefix= ");
        assertBindingFailure("app.chat-memory.invalidation-channel= ");
        assertBindingFailure("app.processing-lease.duration=0s");
        assertBindingFailure("app.processing-lease.renewal-interval=15s",
                "app.processing-lease.duration=30s");
        assertBindingFailure("app.processing-lease.redis-key-prefix= ");
    }

    @Test
    void trackedConfigurationUsesSupportedSecretFreeRedisProperties() throws Exception {
        String yaml = Files.readString(
                Path.of("src", "main", "resources", "application.yaml"),
                StandardCharsets.UTF_8);
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);

        assertThat(yaml).contains(
                "database: ${REDIS_DATABASE:0}",
                "password: ${REDIS_PASSWORD:}",
                "namespace: ${SESSION_REDIS_NAMESPACE:auto-code:session}",
                "http-only: true",
                "secure: ${SESSION_COOKIE_SECURE:false}",
                "same-site: ${SESSION_COOKIE_SAME_SITE:lax}",
                "redis-key-prefix: ${CHAT_MEMORY_REDIS_KEY_PREFIX:auto-code:chat-memory:v1:}",
                "invalidation-channel: ${CHAT_MEMORY_INVALIDATION_CHANNEL:auto-code:chat-memory:invalidation:v1}",
                "redis-key-prefix: ${APP_PROCESSING_LEASE_REDIS_KEY_PREFIX:auto-code:processing-lease:v1:}")
                .contains("session:\n    timeout:",
                        "data:\n      redis:\n        namespace:")
                .doesNotContain("store-type:", "spring.data.redis.ttl",
                        "session:\n    redis:\n      namespace:");
        assertThat(pom)
                .contains("spring-boot-starter-session-data-redis")
                .doesNotContain(
                        "langchain4j-community-redis-spring-boot-starter",
                        "<artifactId>spring-session-data-redis</artifactId>");
    }

    private void assertBindingFailure(String... properties) {
        contextRunner.withPropertyValues(properties).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(
                    IllegalArgumentException.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
            AppChatMemoryProperties.class,
            AppProcessingLeaseProperties.class
    })
    static class PropertiesConfiguration {
    }
}
