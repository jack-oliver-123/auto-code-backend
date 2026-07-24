package com.jack.autocodebackend.core;

import cn.hutool.core.io.FileUtil;
import com.jack.autocodebackend.ai.AiCodeGeneratorService;
import com.jack.autocodebackend.ai.model.HtmlCodeResult;
import com.jack.autocodebackend.ai.model.enums.CodeGenTypeEnum;
import com.jack.autocodebackend.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

    private static final Path OUTPUT_ROOT = Path.of(
            System.getProperty("user.dir"),
            "tmp",
            "code_output"
    );
    private static final String HTML_CODE = "<html><body>Hello</body></html>";
    private static final String FENCED_HTML = "```html\n" + HTML_CODE + "\n```\n";

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

        File directory = facade.generateAndSaveCode("生成页面", CodeGenTypeEnum.HTML);
        generatedDirectories.add(directory.toPath());

        assertEquals(
                HTML_CODE,
                FileUtil.readString(new File(directory, "index.html"), StandardCharsets.UTF_8)
        );
        verify(aiCodeGeneratorService).generateHtmlCode("生成页面");
    }

    @Test
    void generateAndSaveCodeRejectsNullTypeBeforeCallingAi() {
        assertThrows(BusinessException.class, () -> facade.generateAndSaveCode("生成页面", null));

        verifyNoInteractions(aiCodeGeneratorService);
    }

    @Test
    void streamRelaysChunksAndSavesAfterCompletion() throws IOException {
        List<String> chunks = List.of("说明\n```ht", "ml\n" + HTML_CODE, "\n```\n");
        given(aiCodeGeneratorService.generateHtmlCodeStream("生成页面"))
                .willReturn(Flux.fromIterable(chunks));
        Set<Path> before = outputDirectories();

        List<String> emitted = facade
                .generateAndSaveCodeStream("生成页面", CodeGenTypeEnum.HTML)
                .collectList()
                .block(Duration.ofSeconds(10));
        Set<Path> created = trackNewDirectories(before);

        assertEquals(chunks, emitted);
        assertEquals(1, created.size());
        Path directory = created.iterator().next();
        assertEquals(HTML_CODE, Files.readString(directory.resolve("index.html")));
    }

    @Test
    void streamCreatesIndependentStateForEverySubscription() throws IOException {
        given(aiCodeGeneratorService.generateHtmlCodeStream("生成页面"))
                .willReturn(Flux.just(FENCED_HTML));
        Set<Path> before = outputDirectories();
        Flux<String> result = facade.generateAndSaveCodeStream("生成页面", CodeGenTypeEnum.HTML);

        List<String> first = result.collectList().block(Duration.ofSeconds(10));
        List<String> second = result.collectList().block(Duration.ofSeconds(10));
        Set<Path> created = trackNewDirectories(before);

        assertEquals(List.of(FENCED_HTML), first);
        assertEquals(first, second);
        assertEquals(2, created.size());
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
                () -> facade.generateAndSaveCodeStream("生成页面", CodeGenTypeEnum.MULTI_FILE)
                        .collectList()
                        .block(Duration.ofSeconds(10))
        );

        assertTrue(newDirectoriesSince(before).isEmpty());
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
