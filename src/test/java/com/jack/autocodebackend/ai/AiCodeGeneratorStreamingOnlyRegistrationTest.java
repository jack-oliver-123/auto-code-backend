package com.jack.autocodebackend.ai;

import com.jack.autocodebackend.AutoCodeBackendApplication;
import com.jack.autocodebackend.core.AiCodeGeneratorFacade;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@SpringBootTest(
        classes = {
                AutoCodeBackendApplication.class,
                AiCodeGeneratorStreamingOnlyRegistrationTest.TestModel.class
        },
        properties = {
                "spring.profiles.active=test",
                "app.redis.startup-check-enabled=false",
                "spring.autoconfigure.exclude=dev.langchain4j.openai.spring.OpenAiAutoConfiguration"
        }
)
class AiCodeGeneratorStreamingOnlyRegistrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void doesNotRegisterMixedPipelineWhenChatModelIsMissing() {
        assertTrue(applicationContext.getBeansOfType(AiCodeGeneratorService.class).isEmpty());
        assertTrue(applicationContext.getBeansOfType(AiCodeGeneratorFacade.class).isEmpty());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestModel {

        @Bean
        StreamingChatModel streamingChatModel() {
            return mock(StreamingChatModel.class);
        }
    }
}
