package com.jack.autocodebackend.config;

import com.jack.autocodebackend.ai.AiCodeGeneratorService;
import com.jack.autocodebackend.core.AiCodeGeneratorFacade;
import com.jack.autocodebackend.core.saver.CodeFileSaverRegistry;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.AllNestedConditions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.ConfigurationCondition.ConfigurationPhase;
import org.springframework.beans.factory.ObjectProvider;

@AutoConfiguration(afterName = "dev.langchain4j.openai.spring.OpenAiAutoConfiguration")
@ConditionalOnClass(AiServices.class)
@Conditional(AiCodeGeneratorAutoConfiguration.RequiredChatModelsCondition.class)
public class AiCodeGeneratorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    AiCodeGeneratorService aiCodeGeneratorService(
            ChatModel chatModel,
            StreamingChatModel streamingChatModel
    ) {
        return AiServices.builder(AiCodeGeneratorService.class)
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    AiCodeGeneratorFacade aiCodeGeneratorFacade(
            AiCodeGeneratorService aiCodeGeneratorService,
            ObjectProvider<CodeFileSaverRegistry> saverRegistryProvider,
            ObjectProvider<AppVueProjectProperties> vueProjectPropertiesProvider
    ) {
        return new AiCodeGeneratorFacade(
                aiCodeGeneratorService,
                saverRegistryProvider.getIfAvailable(CodeFileSaverRegistry::legacy),
                vueProjectPropertiesProvider.getIfAvailable(AppVueProjectProperties::defaults)
        );
    }

    static final class RequiredChatModelsCondition extends AllNestedConditions {

        RequiredChatModelsCondition() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnSingleCandidate(ChatModel.class)
        static class ChatModelCandidate {
        }

        @ConditionalOnSingleCandidate(StreamingChatModel.class)
        static class StreamingChatModelCandidate {
        }
    }
}
