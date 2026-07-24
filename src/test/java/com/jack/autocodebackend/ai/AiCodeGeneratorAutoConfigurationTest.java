package com.jack.autocodebackend.ai;

import com.jack.autocodebackend.config.AiCodeGeneratorAutoConfiguration;
import com.jack.autocodebackend.core.AiCodeGeneratorFacade;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AiCodeGeneratorAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AiCodeGeneratorAutoConfiguration.class));

    @Test
    void backsOffWhenChatModelCandidateIsAmbiguous() {
        contextRunner
                .withBean("chatModelOne", ChatModel.class, () -> mock(ChatModel.class))
                .withBean("chatModelTwo", ChatModel.class, () -> mock(ChatModel.class))
                .withBean(StreamingChatModel.class, () -> mock(StreamingChatModel.class))
                .run(context -> assertAiPipelineIsAbsent(context));
    }

    @Test
    void backsOffWhenStreamingModelCandidateIsAmbiguous() {
        contextRunner
                .withBean(ChatModel.class, () -> mock(ChatModel.class))
                .withBean(
                        "streamingChatModelOne",
                        StreamingChatModel.class,
                        () -> mock(StreamingChatModel.class)
                )
                .withBean(
                        "streamingChatModelTwo",
                        StreamingChatModel.class,
                        () -> mock(StreamingChatModel.class)
                )
                .run(context -> assertAiPipelineIsAbsent(context));
    }

    @Test
    void respectsCustomServiceAndFacadeBeans() {
        AiCodeGeneratorService customService = mock(AiCodeGeneratorService.class);
        AiCodeGeneratorFacade customFacade = new AiCodeGeneratorFacade(customService);

        contextRunner
                .withBean(ChatModel.class, () -> mock(ChatModel.class))
                .withBean(StreamingChatModel.class, () -> mock(StreamingChatModel.class))
                .withBean(AiCodeGeneratorService.class, () -> customService)
                .withBean(AiCodeGeneratorFacade.class, () -> customFacade)
                .run(context -> {
                    assertEquals(1, context.getBeansOfType(AiCodeGeneratorService.class).size());
                    assertEquals(1, context.getBeansOfType(AiCodeGeneratorFacade.class).size());
                    assertSame(customService, context.getBean(AiCodeGeneratorService.class));
                    assertSame(customFacade, context.getBean(AiCodeGeneratorFacade.class));
                });
    }

    private void assertAiPipelineIsAbsent(
            org.springframework.context.ApplicationContext applicationContext
    ) {
        assertTrue(applicationContext.getBeansOfType(AiCodeGeneratorService.class).isEmpty());
        assertTrue(applicationContext.getBeansOfType(AiCodeGeneratorFacade.class).isEmpty());
    }
}
