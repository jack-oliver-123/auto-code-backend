package com.jack.autocodebackend.core.saver;

import cn.hutool.core.io.FileUtil;
import com.jack.autocodebackend.ai.model.CodeResult;
import com.jack.autocodebackend.ai.model.HtmlCodeResult;
import com.jack.autocodebackend.ai.model.MultiFileCodeResult;
import com.jack.autocodebackend.ai.model.enums.CodeGenTypeEnum;
import com.jack.autocodebackend.constant.AppConstant;
import com.jack.autocodebackend.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeFileSaverExecutorTest {

    private static final String HTML_CODE = "<html><body>Hello</body></html>";
    private static final String CSS_CODE = "body { color: black; }";
    private static final String JS_CODE = "console.log('ready');";
    private static final long HTML_APP_ID = 9_000_000_000_000_101L;
    private static final long MULTI_FILE_APP_ID = 9_000_000_000_000_102L;
    private static final long REPLACEMENT_APP_ID = 9_000_000_000_000_104L;
    private static final long FIRST_PUBLICATION_APP_ID = 9_000_000_000_000_105L;

    private final List<File> generatedDirectories = new ArrayList<>();

    @AfterEach
    void cleanGeneratedDirectories() {
        generatedDirectories.forEach(FileUtil::del);
    }

    @Test
    void saveHtmlCodeWritesIndexFile() {
        HtmlCodeResult result = new HtmlCodeResult();
        result.setHtmlCode(HTML_CODE);

        File directory = save(result, HTML_APP_ID);
        File indexFile = new File(directory, "index.html");

        assertAll(
                () -> assertTrue(directory.isDirectory()),
                () -> assertEquals("html_" + HTML_APP_ID, directory.getName()),
                () -> assertEquals(new File(AppConstant.CODE_OUTPUT_ROOT_DIR), directory.getParentFile()),
                () -> assertTrue(indexFile.isFile()),
                () -> assertEquals(HTML_CODE, FileUtil.readString(indexFile, StandardCharsets.UTF_8))
        );
    }

    @Test
    void saveMultiFileCodeWritesAllRequiredFiles() {
        File directory = save(completeMultiFileResult(), MULTI_FILE_APP_ID);

        assertAll(
                () -> assertEquals("multi_file_" + MULTI_FILE_APP_ID, directory.getName()),
                () -> assertEquals(HTML_CODE, readFile(directory, "index.html")),
                () -> assertEquals(CSS_CODE, readFile(directory, "style.css")),
                () -> assertEquals(JS_CODE, readFile(directory, "script.js"))
        );
    }

    @Test
    void saveMultiFileCodeRejectsIncompleteResultBeforeCreatingDirectory() {
        MultiFileCodeResult result = completeMultiFileResult();
        result.setCssCode(" ");

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> CodeFileSaverExecutor.executeSaver(result, MULTI_FILE_APP_ID)
        );

        assertEquals("CSS 代码内容不能为空", exception.getMessage());
    }

    @Test
    void saverRejectsNullAndUnsupportedResultTypes() {
        CodeResult unsupportedResult = new CodeResult() {
        };

        assertAll(
                () -> assertThrows(BusinessException.class,
                        () -> CodeFileSaverExecutor.executeSaver(null, HTML_APP_ID)),
                () -> assertThrows(BusinessException.class,
                        () -> CodeFileSaverExecutor.executeSaver(unsupportedResult, HTML_APP_ID))
        );
    }

    @Test
    void saverRejectsNonPositiveAppId() {
        HtmlCodeResult result = new HtmlCodeResult();
        result.setHtmlCode(HTML_CODE);

        assertAll(
                () -> assertThrows(BusinessException.class,
                        () -> CodeFileSaverExecutor.executeSaver(result, null)),
                () -> assertThrows(BusinessException.class,
                        () -> CodeFileSaverExecutor.executeSaver(result, 0L)),
                () -> assertThrows(BusinessException.class,
                        () -> CodeFileSaverExecutor.executeSaver(result, -1L))
        );
    }

    @Test
    void saverRejectsEveryBlankRequiredField() {
        HtmlCodeResult blankHtml = new HtmlCodeResult();
        blankHtml.setHtmlCode(" ");
        MultiFileCodeResult blankMultiHtml = completeMultiFileResult();
        blankMultiHtml.setHtmlCode(" ");
        MultiFileCodeResult blankMultiJavaScript = completeMultiFileResult();
        blankMultiJavaScript.setJsCode(" ");

        assertAll(
                () -> assertThrows(BusinessException.class,
                        () -> CodeFileSaverExecutor.executeSaver(blankHtml, HTML_APP_ID)),
                () -> assertThrows(BusinessException.class,
                        () -> CodeFileSaverExecutor.executeSaver(blankMultiHtml, MULTI_FILE_APP_ID)),
                () -> assertThrows(BusinessException.class,
                        () -> CodeFileSaverExecutor.executeSaver(blankMultiJavaScript, MULTI_FILE_APP_ID))
        );
    }

    @Test
    void saverRejectsIncompleteHtmlFromStructuredResults() {
        HtmlCodeResult incompleteHtml = new HtmlCodeResult();
        incompleteHtml.setHtmlCode("<main>partial</main>");
        MultiFileCodeResult incompleteMultiFile = completeMultiFileResult();
        incompleteMultiFile.setHtmlCode("<main>partial</main>");

        assertAll(
                () -> assertThrows(
                        BusinessException.class,
                        () -> CodeFileSaverExecutor.executeSaver(incompleteHtml, HTML_APP_ID)
                ),
                () -> assertThrows(
                        BusinessException.class,
                        () -> CodeFileSaverExecutor.executeSaver(incompleteMultiFile, MULTI_FILE_APP_ID)
                )
        );
    }

    @Test
    void saverDeletesPartialDirectoryWhenWritingFails() {
        AtomicReference<Path> createdDirectory = new AtomicReference<>();
        CodeFileSaverTemplate<HtmlCodeResult> saver = new CodeFileSaverTemplate<>() {
            @Override
            protected CodeGenTypeEnum getCodeType() {
                return CodeGenTypeEnum.HTML;
            }

            @Override
            protected void saveFiles(HtmlCodeResult result, String baseDirPath) {
                Path directory = Path.of(baseDirPath);
                createdDirectory.set(directory);
                generatedDirectories.add(directory.toFile());
                writeToFile(baseDirPath, "index.html", result.getHtmlCode());
                throw new IllegalStateException("simulated write failure");
            }
        };
        HtmlCodeResult result = new HtmlCodeResult();
        result.setHtmlCode(HTML_CODE);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> saver.saveCode(result, HTML_APP_ID)
        );

        assertEquals("simulated write failure", exception.getMessage());
        assertFalse(createdDirectory.get().toFile().exists());
    }

    @Test
    void saverKeepsPreExistingFilesUnchangedWhenStagingWriteFails() throws IOException {
        long appId = 9_000_000_000_000_103L;
        File directory = new File(AppConstant.CODE_OUTPUT_ROOT_DIR, "html_" + appId);
        File indexFile = new File(directory, "index.html");
        File markerFile = new File(directory, "previous-version.marker");
        String previousHtml = "<html><body>previous version</body></html>";
        FileUtil.writeString(previousHtml, indexFile, StandardCharsets.UTF_8);
        FileUtil.writeString("keep", markerFile, StandardCharsets.UTF_8);
        generatedDirectories.add(directory);

        CodeFileSaverTemplate<HtmlCodeResult> saver = new CodeFileSaverTemplate<>() {
            @Override
            protected CodeGenTypeEnum getCodeType() {
                return CodeGenTypeEnum.HTML;
            }

            @Override
            protected void saveFiles(HtmlCodeResult result, String baseDirPath) {
                writeToFile(baseDirPath, "index.html", result.getHtmlCode());
                throw new IllegalStateException("simulated regeneration failure");
            }
        };
        HtmlCodeResult result = new HtmlCodeResult();
        result.setHtmlCode(HTML_CODE);

        assertThrows(IllegalStateException.class, () -> saver.saveCode(result, appId));

        assertAll(
                () -> assertTrue(directory.isDirectory()),
                () -> assertEquals(previousHtml, FileUtil.readString(indexFile, StandardCharsets.UTF_8)),
                () -> assertTrue(markerFile.isFile()),
                () -> assertEquals("keep", FileUtil.readString(markerFile, StandardCharsets.UTF_8)),
                () -> assertTrue(findPublishArtifacts("html_" + appId).isEmpty())
        );
    }

    @Test
    void pendingReplacementCanRollbackToPreviousStableDirectory() throws Exception {
        File directory = new File(AppConstant.CODE_OUTPUT_ROOT_DIR, "html_" + REPLACEMENT_APP_ID);
        File indexFile = new File(directory, "index.html");
        File markerFile = new File(directory, "previous-version.marker");
        String previousHtml = "<html><body>previous version</body></html>";
        FileUtil.writeString(previousHtml, indexFile, StandardCharsets.UTF_8);
        FileUtil.writeString("keep", markerFile, StandardCharsets.UTF_8);
        generatedDirectories.add(directory);

        HtmlCodeResult result = new HtmlCodeResult();
        result.setHtmlCode(HTML_CODE);

        try (CodeFilePublication publication =
                     CodeFileSaverExecutor.executeSaverPublication(result, REPLACEMENT_APP_ID)) {
            assertAll(
                    () -> assertEquals(HTML_CODE, readFile(directory, "index.html")),
                    () -> assertFalse(markerFile.exists()),
                    () -> assertEquals(1, findPublishArtifacts(
                            "html_" + REPLACEMENT_APP_ID).size())
            );

            publication.rollback();
        }

        assertAll(
                () -> assertEquals(previousHtml, readFile(directory, "index.html")),
                () -> assertEquals("keep", readFile(directory, "previous-version.marker")),
                () -> assertTrue(findPublishArtifacts(
                        "html_" + REPLACEMENT_APP_ID).isEmpty())
        );
    }

    @Test
    void pendingFirstPublicationRollbackRemovesNewDirectory() throws Exception {
        HtmlCodeResult result = new HtmlCodeResult();
        result.setHtmlCode(HTML_CODE);

        CodeFilePublication publication =
                CodeFileSaverExecutor.executeSaverPublication(result, FIRST_PUBLICATION_APP_ID);
        File directory = publication.directory();
        generatedDirectories.add(directory);
        assertTrue(directory.isDirectory());

        publication.rollback();

        assertAll(
                () -> assertFalse(directory.exists()),
                () -> assertTrue(findPublishArtifacts(
                        "html_" + FIRST_PUBLICATION_APP_ID).isEmpty())
        );
    }

    @Test
    void committedReplacementKeepsNewDirectoryAndRemovesBackup() throws Exception {
        File directory = new File(AppConstant.CODE_OUTPUT_ROOT_DIR, "html_" + REPLACEMENT_APP_ID);
        File markerFile = new File(directory, "previous-version.marker");
        FileUtil.writeString("<html><body>previous version</body></html>",
                new File(directory, "index.html"), StandardCharsets.UTF_8);
        FileUtil.writeString("remove", markerFile, StandardCharsets.UTF_8);
        generatedDirectories.add(directory);

        HtmlCodeResult result = new HtmlCodeResult();
        result.setHtmlCode(HTML_CODE);
        try (CodeFilePublication publication =
                     CodeFileSaverExecutor.executeSaverPublication(result, REPLACEMENT_APP_ID)) {
            publication.commit();
        }

        assertAll(
                () -> assertEquals(HTML_CODE, readFile(directory, "index.html")),
                () -> assertFalse(markerFile.exists()),
                () -> assertTrue(findPublishArtifacts(
                        "html_" + REPLACEMENT_APP_ID).isEmpty())
        );
    }

    private List<Path> findPublishArtifacts(String dirName) throws IOException {
        Path outputRoot = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR);
        if (Files.notExists(outputRoot)) {
            return List.of();
        }
        try (var entries = Files.list(outputRoot)) {
            return entries
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith("." + dirName + ".staging-")
                                || name.startsWith("." + dirName + ".backup-");
                    })
                    .toList();
        }
    }

    private File save(CodeResult result, Long appId) {
        File directory = CodeFileSaverExecutor.executeSaver(result, appId);
        generatedDirectories.add(directory);
        return directory;
    }

    private String readFile(File directory, String filename) {
        File file = new File(directory, filename);
        assertTrue(file.isFile());
        return FileUtil.readString(file, StandardCharsets.UTF_8);
    }

    private MultiFileCodeResult completeMultiFileResult() {
        MultiFileCodeResult result = new MultiFileCodeResult();
        result.setHtmlCode(HTML_CODE);
        result.setCssCode(CSS_CODE);
        result.setJsCode(JS_CODE);
        return result;
    }
}
