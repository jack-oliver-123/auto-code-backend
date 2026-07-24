package com.jack.autocodebackend.ai;

import com.jack.autocodebackend.AutoCodeBackendApplication;
import com.jack.autocodebackend.core.AiCodeGeneratorFacade;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

@SpringBootTest(
        classes = {
                AutoCodeBackendApplication.class,
                AiCodeGeneratorRegistrationWithModelsTest.TestModels.class
        },
        properties = {
                "spring.profiles.active=test",
                "spring.autoconfigure.exclude=dev.langchain4j.openai.spring.OpenAiAutoConfiguration"
        }
)
class AiCodeGeneratorRegistrationWithModelsTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void registersAiPipelineWhenRequiredChatModelsExist() {
        assertEquals(1, applicationContext.getBeansOfType(AiCodeGeneratorService.class).size());
        assertEquals(1, applicationContext.getBeansOfType(AiCodeGeneratorFacade.class).size());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestModels {

        @Bean
        ChatModel chatModel() {
            return mock(ChatModel.class);
        }

        @Bean
        StreamingChatModel streamingChatModel() {
            return mock(StreamingChatModel.class);
        }
    }
}
