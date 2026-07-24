package com.jack.autocodebackend.ai;

import com.jack.autocodebackend.core.AiCodeGeneratorFacade;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "spring.autoconfigure.exclude=dev.langchain4j.openai.spring.OpenAiAutoConfiguration"
})
class AiCodeGeneratorConditionalRegistrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void doesNotRegisterAiPipelineWithoutRequiredChatModels() {
        assertTrue(applicationContext.getBeansOfType(AiCodeGeneratorService.class).isEmpty());
        assertTrue(applicationContext.getBeansOfType(AiCodeGeneratorFacade.class).isEmpty());
    }
}
