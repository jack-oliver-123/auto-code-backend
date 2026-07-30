package com.jack.autocodebackend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.session.SessionRepository;
import org.springframework.session.data.redis.RedisSessionRepository;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "app.redis.startup-check-enabled=false")
class AutoCodeBackendApplicationTests {

    @Autowired
    private Environment environment;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void contextLoads() {
        assertThat(environment.getProperty("spring.web.error.include-stacktrace"))
                .isEqualTo("never");
        assertThat(applicationContext.getBean(SessionRepository.class))
                .isInstanceOf(RedisSessionRepository.class);
        assertThat(applicationContext.getBean(RedisConnectionFactory.class)).isNotNull();
        assertThat(applicationContext.getBean(
                "springSessionDefaultRedisSerializer", RedisSerializer.class)).isNotNull();
        assertThat(org.springframework.util.ClassUtils.isPresent(
                "dev.langchain4j.community.store.embedding.redis.spring.RedisEmbeddingStoreAutoConfiguration",
                getClass().getClassLoader())).isFalse();
    }

}
