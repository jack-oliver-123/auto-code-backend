package com.jack.autocodebackend.core;

import com.jack.autocodebackend.ai.AiCodeGeneratorService;
import com.jack.autocodebackend.ai.model.CodeResult;
import com.jack.autocodebackend.ai.model.VueProjectCodeResult;
import com.jack.autocodebackend.ai.model.VueProjectFile;
import com.jack.autocodebackend.ai.model.enums.CodeGenTypeEnum;
import com.jack.autocodebackend.config.AppVueProjectProperties;
import com.jack.autocodebackend.core.saver.CodeFilePublication;
import com.jack.autocodebackend.core.saver.CodeFileSaverRegistry;
import com.jack.autocodebackend.core.saver.CodeResultSaver;
import com.jack.autocodebackend.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AiCodeGeneratorFacadeVueTest {

    private static final long APP_ID = 9_223_372_036_854_775L;

    private final AiCodeGeneratorService aiService = mock(AiCodeGeneratorService.class);
    private final CodeFilePublication publication = mock(CodeFilePublication.class);
    private CapturingVueSaver saver;
    private AiCodeGeneratorFacade facade;

    @BeforeEach
    void setUp() {
        given(publication.directory()).willReturn(new File("vue_project_" + APP_ID));
        saver = new CapturingVueSaver(publication);
        facade = new AiCodeGeneratorFacade(
                aiService,
                new CodeFileSaverRegistry(List.of(saver)),
                AppVueProjectProperties.defaults());
    }

    @Test
    void structuredAndStreamingVueMethodsUseTheSameTypedSaver() throws Exception {
        VueProjectCodeResult structured = project("structured");
        given(aiService.generateVueProjectCode("first")).willReturn(structured);

        assertThat(facade.generateAndSaveVueProjectCode("first", APP_ID))
                .isEqualTo(publication.directory());
        assertThat(saver.results).containsExactly(structured);
        verify(publication).commit();

        String appContent = "  <template>\n  <main>streamed</main>  \n</template>\n";
        String response = envelope(appContent);
        List<String> chunks = List.of(
                response.substring(0, 17),
                response.substring(17, 73),
                response.substring(73));
        given(aiService.generateVueProjectCodeStream("later"))
                .willReturn(Flux.fromIterable(chunks));
        CodeGenerationSession session = facade.startCodeGeneration(
                "later", CodeGenTypeEnum.VUE_PROJECT, APP_ID);

        assertThat(session.stream().collectList().block()).isEqualTo(chunks);
        assertThat(saver.results).hasSize(2);
        assertThat(((VueProjectCodeResult) saver.results.getLast()).files().get(1).content())
                .isEqualTo(appContent);
        session.rollback();
        verify(publication).rollback();
    }

    @Test
    void distinguishesOrdinaryConversationFromMalformedProjectProtocol() {
        given(aiService.generateVueProjectCodeStream("question"))
                .willReturn(Flux.just("这是一个不修改代码的正常回答。"));
        CodeGenerationSession ordinary = facade.startCodeGeneration(
                "question", CodeGenTypeEnum.VUE_PROJECT, APP_ID);

        Throwable ordinaryFailure = catchThrowable(() -> ordinary.stream().blockLast());

        assertThat(ordinaryFailure)
                .isInstanceOf(AiCodeGeneratorFacade.CodeResponseFormatException.class);
        assertThat(((AiCodeGeneratorFacade.CodeResponseFormatException) ordinaryFailure)
                .isOrdinaryConversationCandidate()).isTrue();
        ordinary.rollback();

        given(aiService.generateVueProjectCodeStream("broken"))
                .willReturn(Flux.just(
                        "<<<AUTO_CODE_PROJECT_V1>>>\nFILE: src/App.vue\n```vue\n<div/>"));
        CodeGenerationSession malformed = facade.startCodeGeneration(
                "broken", CodeGenTypeEnum.VUE_PROJECT, APP_ID);
        Throwable malformedFailure = catchThrowable(() -> malformed.stream().blockLast());

        assertThat(malformedFailure)
                .isInstanceOf(AiCodeGeneratorFacade.CodeResponseFormatException.class);
        assertThat(((AiCodeGeneratorFacade.CodeResponseFormatException) malformedFailure)
                .isOrdinaryConversationCandidate()).isFalse();
        malformed.rollback();
        assertThat(saver.results).isEmpty();
        verify(publication, never()).commit();
    }

    @Test
    void rejectsOverflowBeforeForwardingTheOffendingChunk() {
        facade = new AiCodeGeneratorFacade(
                aiService,
                new CodeFileSaverRegistry(List.of(saver)),
                tightResponseProperties());
        String first = "a".repeat(60);
        String overflowing = "b".repeat(42);
        given(aiService.generateVueProjectCodeStream("large"))
                .willReturn(Flux.just(first, overflowing));
        List<String> emitted = new ArrayList<>();
        CodeGenerationSession session = facade.startCodeGeneration(
                "large", CodeGenTypeEnum.VUE_PROJECT, APP_ID);

        Throwable failure = catchThrowable(() -> session.stream()
                .doOnNext(emitted::add)
                .blockLast());

        assertThat(failure).isInstanceOf(BusinessException.class)
                .hasMessageContaining("response exceeds");
        assertThat(emitted).containsExactly(first);
        assertThat(saver.results).isEmpty();
        session.rollback();
    }

    @Test
    void abnormalProviderClosureNeverParsesEvenAfterApparentEndMarker() {
        IllegalStateException closed = new IllegalStateException("closed");
        String completeLookingPrefix = envelope("<template>complete-looking</template>\n");
        given(aiService.generateVueProjectCodeStream("closed-before-marker"))
                .willReturn(Flux.concat(
                        Flux.just(completeLookingPrefix.substring(0, 80)),
                        Flux.error(closed)
                ));
        given(aiService.generateVueProjectCodeStream("closed-after-marker"))
                .willReturn(Flux.concat(
                        Flux.just(completeLookingPrefix),
                        Flux.error(closed)
                ));

        for (String prompt : List.of("closed-before-marker", "closed-after-marker")) {
            CodeGenerationSession session = facade.startCodeGeneration(
                    prompt, CodeGenTypeEnum.VUE_PROJECT, APP_ID);

            Throwable failure = catchThrowable(() -> session.stream().blockLast());

            assertThat(failure).isSameAs(closed);
            session.rollback();
        }
        assertThat(saver.results).isEmpty();
        verify(publication, never()).commit();
    }

    private static VueProjectCodeResult project(String value) {
        return new VueProjectCodeResult(List.of(
                new VueProjectFile("src/main.js", "import { createApp } from 'vue'\n"),
                new VueProjectFile("src/App.vue", "<template>" + value + "</template>\n"),
                new VueProjectFile("src/router/index.js", "createWebHashHistory()\n")), null);
    }

    private static String envelope(String appContent) {
        return "<<<AUTO_CODE_PROJECT_V1>>>\n"
                + "FILE: src/main.js\n```js\nimport { createApp } from 'vue'\n```\n"
                + "FILE: src/App.vue\n```vue\n" + appContent + "```\n"
                + "FILE: src/router/index.js\n```js\ncreateWebHashHistory()\n```\n"
                + "<<<END_AUTO_CODE_PROJECT_V1>>>";
    }

    private static AppVueProjectProperties tightResponseProperties() {
        return new AppVueProjectProperties(
                1, 101, 24, 4, 28, 100, 100, 100,
                180, 8, 200, 1024, Duration.ofSeconds(1), Duration.ofMillis(10),
                1, "docker", "builder:test", "1", "64m", 32, "16m", 128);
    }

    private static final class CapturingVueSaver implements CodeResultSaver {

        private final CodeFilePublication publication;
        private final List<CodeResult> results = new ArrayList<>();

        private CapturingVueSaver(CodeFilePublication publication) {
            this.publication = publication;
        }

        @Override
        public CodeGenTypeEnum codeGenType() {
            return CodeGenTypeEnum.VUE_PROJECT;
        }

        @Override
        public Class<? extends CodeResult> resultType() {
            return VueProjectCodeResult.class;
        }

        @Override
        public CodeFilePublication publish(CodeResult result, Long appId) {
            results.add(result);
            return publication;
        }
    }
}
