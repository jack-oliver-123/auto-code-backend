package com.jack.autocodebackend.core;

import cn.hutool.core.io.FileUtil;
import com.jack.autocodebackend.ai.AiCodeGeneratorService;
import com.jack.autocodebackend.ai.model.HtmlCodeResult;
import com.jack.autocodebackend.ai.model.MultiFileCodeResult;
import com.jack.autocodebackend.ai.model.enums.CodeGenTypeEnum;
import com.jack.autocodebackend.constant.AppConstant;
import com.jack.autocodebackend.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AiCodeGeneratorFacadeTest {

    private static final Path OUTPUT_ROOT = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR);
    private static final long APP_ID = 9_000_000_000_000_201L;
    private static final String HTML_CODE = "<html><body>Hello</body></html>";
    private static final String CSS_CODE = "body { color: black; }";
    private static final String JS_CODE = "console.log('ready');";
    private static final String USER_MESSAGE = "build page";
    private static final String FENCED_HTML = "```html\n" + HTML_CODE + "\n```\n";
    private static final String FENCED_MULTI_FILE = "index.html\n```html\n" + HTML_CODE
            + "\n```\nstyle.css\n```css\n" + CSS_CODE
            + "\n```\nscript.js\n```javascript\n" + JS_CODE + "\n```\n";

    private final AiCodeGeneratorService aiCodeGeneratorService = mock(AiCodeGeneratorService.class);
    private final List<Path> generatedDirectories = new ArrayList<>();

    private AiCodeGeneratorFacade facade;

    @BeforeEach
    void setUp() {
        facade = new AiCodeGeneratorFacade(aiCodeGeneratorService);
    }

    @AfterEach
    void cleanGeneratedDirectories() {
        generatedDirectories.forEach(path -> FileUtil.del(path.toFile()));
    }

    @Test
    void generateAndSaveCodeUsesTypedSaver() {
        HtmlCodeResult result = new HtmlCodeResult();
        result.setHtmlCode(HTML_CODE);
        given(aiCodeGeneratorService.generateHtmlCode("生成页面")).willReturn(result);

        File directory = facade.generateAndSaveHtmlCode("生成页面", APP_ID);
        generatedDirectories.add(directory.toPath());

        assertEquals("html_" + APP_ID, directory.getName());
        assertEquals(HTML_CODE, FileUtil.readString(
                new File(directory, "index.html"),
                StandardCharsets.UTF_8
        ));
        verify(aiCodeGeneratorService).generateHtmlCode("生成页面");
    }

    @Test
    void generateAndSaveCodeWritesEveryMultiFileResult() throws IOException {
        MultiFileCodeResult result = completeMultiFileResult();
        given(aiCodeGeneratorService.generateMultiFileCode(USER_MESSAGE)).willReturn(result);

        File directory = facade.generateAndSaveCode(USER_MESSAGE, CodeGenTypeEnum.MULTI_FILE, APP_ID);
        generatedDirectories.add(directory.toPath());

        assertEquals("multi_file_" + APP_ID, directory.getName());
        assertEquals(HTML_CODE, Files.readString(directory.toPath().resolve("index.html")));
        assertEquals(CSS_CODE, Files.readString(directory.toPath().resolve("style.css")));
        assertEquals(JS_CODE, Files.readString(directory.toPath().resolve("script.js")));
        verify(aiCodeGeneratorService).generateMultiFileCode(USER_MESSAGE);
    }

    @Test
    void generateAndSaveCodeRejectsNullTypeBeforeCallingAi() {
        assertThrows(BusinessException.class, () -> facade.generateAndSaveCode("生成页面", null, APP_ID));

        verifyNoInteractions(aiCodeGeneratorService);
    }

    @Test
    void generateAndSaveCodeRejectsInvalidAppIdBeforeCallingAi() {
        assertThrows(
                BusinessException.class,
                () -> facade.generateAndSaveCode("生成页面", CodeGenTypeEnum.HTML, 0L)
        );

        verifyNoInteractions(aiCodeGeneratorService);
    }

    @Test
    void streamRelaysChunksAndSavesAfterCompletion() throws IOException {
        List<String> chunks = List.of("  说明  \n```ht", "ml\n" + HTML_CODE, "\n```\n  ");
        given(aiCodeGeneratorService.generateHtmlCodeStream("生成页面"))
                .willReturn(Flux.fromIterable(chunks));
        Set<Path> before = outputDirectories();

        List<String> emitted = facade
                .generateAndSaveCodeStream("生成页面", CodeGenTypeEnum.HTML, APP_ID)
                .collectList()
                .block(Duration.ofSeconds(10));
        Set<Path> created = trackNewDirectories(before);

        assertEquals(chunks, emitted);
        assertEquals(1, created.size());
        Path directory = created.iterator().next();
        assertEquals("html_" + APP_ID, directory.getFileName().toString());
        assertEquals(HTML_CODE, Files.readString(directory.resolve("index.html")));
    }

    @Test
    void streamWritesEveryMultiFileResultAfterCompletion() throws IOException {
        List<String> chunks = List.of(
                FENCED_MULTI_FILE.substring(0, FENCED_MULTI_FILE.length() / 2),
                FENCED_MULTI_FILE.substring(FENCED_MULTI_FILE.length() / 2)
        );
        given(aiCodeGeneratorService.generateMultiFileCodeStream(USER_MESSAGE))
                .willReturn(Flux.fromIterable(chunks));
        Set<Path> before = outputDirectories();

        List<String> emitted = facade
                .generateAndSaveCodeStream(USER_MESSAGE, CodeGenTypeEnum.MULTI_FILE, APP_ID)
                .collectList()
                .block(Duration.ofSeconds(10));
        Set<Path> created = trackNewDirectories(before);

        assertEquals(chunks, emitted);
        assertEquals(1, created.size());
        Path directory = created.iterator().next();
        assertEquals("multi_file_" + APP_ID, directory.getFileName().toString());
        assertEquals(HTML_CODE, Files.readString(directory.resolve("index.html")));
        assertEquals(CSS_CODE, Files.readString(directory.resolve("style.css")));
        assertEquals(JS_CODE, Files.readString(directory.resolve("script.js")));
    }

    @Test
    void streamCreatesIndependentStateForEverySubscription() throws IOException {
        given(aiCodeGeneratorService.generateHtmlCodeStream("生成页面"))
                .willReturn(Flux.just(FENCED_HTML));
        Set<Path> before = outputDirectories();
        Flux<String> result = facade.generateAndSaveCodeStream(
                "生成页面",
                CodeGenTypeEnum.HTML,
                APP_ID
        );

        List<String> first = result.collectList().block(Duration.ofSeconds(10));
        List<String> second = result.collectList().block(Duration.ofSeconds(10));
        Set<Path> created = trackNewDirectories(before);

        assertEquals(List.of(FENCED_HTML), first);
        assertEquals(first, second);
        assertEquals(1, created.size());
        assertEquals("html_" + APP_ID, created.iterator().next().getFileName().toString());
        verify(aiCodeGeneratorService, times(2)).generateHtmlCodeStream("生成页面");
    }

    @Test
    void streamPropagatesParseFailureAndDoesNotCreateDirectory() throws IOException {
        String incompleteResponse = "```html\n" + HTML_CODE + "\n```\n";
        given(aiCodeGeneratorService.generateMultiFileCodeStream("生成页面"))
                .willReturn(Flux.just(incompleteResponse));
        Set<Path> before = outputDirectories();

        assertThrows(
                IllegalArgumentException.class,
                () -> facade.generateAndSaveCodeStream(
                                "生成页面",
                                CodeGenTypeEnum.MULTI_FILE,
                                APP_ID
                        )
                        .collectList()
                        .block(Duration.ofSeconds(10))
        );

        assertTrue(newDirectoriesSince(before).isEmpty());
    }

    @Test
    void streamRejectsNullPublisherWithoutCreatingDirectory() throws IOException {
        given(aiCodeGeneratorService.generateHtmlCodeStream(USER_MESSAGE)).willReturn(null);
        Set<Path> before = outputDirectories();

        assertThrows(
                BusinessException.class,
                () -> facade.generateAndSaveCodeStream(USER_MESSAGE, CodeGenTypeEnum.HTML, APP_ID)
                        .collectList()
                        .block(Duration.ofSeconds(10))
        );

        assertTrue(newDirectoriesSince(before).isEmpty());
    }

    @Test
    void streamPropagatesUpstreamFailureWithoutCreatingDirectory() throws IOException {
        IllegalStateException providerFailure = new IllegalStateException("provider failed");
        given(aiCodeGeneratorService.generateHtmlCodeStream(USER_MESSAGE))
                .willReturn(Flux.concat(Flux.just("partial"), Flux.error(providerFailure)));
        Set<Path> before = outputDirectories();

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> facade.generateAndSaveCodeStream(USER_MESSAGE, CodeGenTypeEnum.HTML, APP_ID)
                        .collectList()
                        .block(Duration.ofSeconds(10))
        );

        assertEquals(providerFailure, thrown);
        assertTrue(newDirectoriesSince(before).isEmpty());
    }

    @Test
    void cancellingStreamDoesNotCreateDirectory() throws IOException {
        given(aiCodeGeneratorService.generateHtmlCodeStream(USER_MESSAGE))
                .willReturn(Flux.concat(Flux.just("partial"), Flux.never()));
        Set<Path> before = outputDirectories();

        Disposable subscription = facade
                .generateAndSaveCodeStream(USER_MESSAGE, CodeGenTypeEnum.HTML, APP_ID)
                .subscribe();
        subscription.dispose();

        assertTrue(newDirectoriesSince(before).isEmpty());
    }

    @Test
    void transactionalStreamRollbackRestoresPreviousStableVersion() throws Exception {
        Path directory = OUTPUT_ROOT.resolve("html_" + APP_ID);
        Path indexFile = directory.resolve("index.html");
        Path markerFile = directory.resolve("previous-version.marker");
        String previousHtml = "<html><body>previous version</body></html>";
        Files.createDirectories(directory);
        Files.writeString(indexFile, previousHtml);
        Files.writeString(markerFile, "keep");
        generatedDirectories.add(directory);
        List<String> chunks = List.of("  preface\n```ht", "ml\n" + HTML_CODE, "\n```\n  ");
        given(aiCodeGeneratorService.generateHtmlCodeStream(USER_MESSAGE))
                .willReturn(Flux.fromIterable(chunks));

        CodeGenerationSession session = facade.startCodeGeneration(
                USER_MESSAGE, CodeGenTypeEnum.HTML, APP_ID);
        List<String> emitted = session.stream().collectList().block(Duration.ofSeconds(10));

        assertEquals(chunks, emitted);
        assertEquals(HTML_CODE, Files.readString(indexFile));
        assertTrue(Files.notExists(markerFile));

        session.rollback();

        assertEquals(previousHtml, Files.readString(indexFile));
        assertEquals("keep", Files.readString(markerFile));
    }

    @Test
    void transactionalStreamCommitKeepsGeneratedVersion() throws Exception {
        Path directory = OUTPUT_ROOT.resolve("html_" + APP_ID);
        Path markerFile = directory.resolve("previous-version.marker");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("index.html"),
                "<html><body>previous version</body></html>");
        Files.writeString(markerFile, "remove");
        generatedDirectories.add(directory);
        given(aiCodeGeneratorService.generateHtmlCodeStream(USER_MESSAGE))
                .willReturn(Flux.just(FENCED_HTML));

        CodeGenerationSession session = facade.startCodeGeneration(
                USER_MESSAGE, CodeGenTypeEnum.HTML, APP_ID);
        assertEquals(List.of(FENCED_HTML),
                session.stream().collectList().block(Duration.ofSeconds(10)));

        session.commit();
        session.close();

        assertEquals(HTML_CODE, Files.readString(directory.resolve("index.html")));
        assertTrue(Files.notExists(markerFile));
    }

    @Test
    void transactionalFirstGenerationRollbackRemovesPublishedDirectory() throws Exception {
        given(aiCodeGeneratorService.generateHtmlCodeStream(USER_MESSAGE))
                .willReturn(Flux.just(FENCED_HTML));
        Path directory = OUTPUT_ROOT.resolve("html_" + APP_ID);
        generatedDirectories.add(directory);

        CodeGenerationSession session = facade.startCodeGeneration(
                USER_MESSAGE, CodeGenTypeEnum.HTML, APP_ID);
        session.stream().blockLast(Duration.ofSeconds(10));
        assertTrue(Files.isDirectory(directory));

        session.rollback();

        assertTrue(Files.notExists(directory));
    }

    @Test
    void rollbackBeforeLatePublicationRemovesGeneratedDirectory() throws Exception {
        given(aiCodeGeneratorService.generateHtmlCodeStream(USER_MESSAGE))
                .willReturn(Flux.just(FENCED_HTML));
        Path directory = OUTPUT_ROOT.resolve("html_" + APP_ID);
        generatedDirectories.add(directory);

        CodeGenerationSession session = facade.startCodeGeneration(
                USER_MESSAGE, CodeGenTypeEnum.HTML, APP_ID);
        session.rollback();

        assertThrows(IllegalStateException.class,
                () -> session.stream().blockLast(Duration.ofSeconds(10)));
        assertTrue(Files.notExists(directory));
    }

    @Test
    void transactionalSessionAllowsOnlyOneSubscription() {
        given(aiCodeGeneratorService.generateHtmlCodeStream(USER_MESSAGE))
                .willReturn(Flux.just(FENCED_HTML));
        Path directory = OUTPUT_ROOT.resolve("html_" + APP_ID);
        generatedDirectories.add(directory);
        CodeGenerationSession session = facade.startCodeGeneration(
                USER_MESSAGE, CodeGenTypeEnum.HTML, APP_ID);

        session.stream().blockLast(Duration.ofSeconds(10));
        session.rollback();

        assertThrows(IllegalStateException.class,
                () -> session.stream().blockLast(Duration.ofSeconds(10)));
    }

    private MultiFileCodeResult completeMultiFileResult() {
        MultiFileCodeResult result = new MultiFileCodeResult();
        result.setHtmlCode(HTML_CODE);
        result.setCssCode(CSS_CODE);
        result.setJsCode(JS_CODE);
        return result;
    }

    private Set<Path> trackNewDirectories(Set<Path> before) throws IOException {
        Set<Path> created = newDirectoriesSince(before);
        generatedDirectories.addAll(created);
        return created;
    }

    private Set<Path> newDirectoriesSince(Set<Path> before) throws IOException {
        Set<Path> created = new HashSet<>(outputDirectories());
        created.removeAll(before);
        return created;
    }

    private Set<Path> outputDirectories() throws IOException {
        if (!Files.isDirectory(OUTPUT_ROOT)) {
            return Set.of();
        }
        try (Stream<Path> paths = Files.list(OUTPUT_ROOT)) {
            return paths
                    .filter(Files::isDirectory)
                    .map(Path::toAbsolutePath)
                    .collect(Collectors.toSet());
        }
    }
}
