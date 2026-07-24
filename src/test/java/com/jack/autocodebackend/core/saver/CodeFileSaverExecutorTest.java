package com.jack.autocodebackend.core.saver;

import cn.hutool.core.io.FileUtil;
import com.jack.autocodebackend.ai.model.CodeResult;
import com.jack.autocodebackend.ai.model.HtmlCodeResult;
import com.jack.autocodebackend.ai.model.MultiFileCodeResult;
import com.jack.autocodebackend.ai.model.enums.CodeGenTypeEnum;
import com.jack.autocodebackend.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
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

    private final List<File> generatedDirectories = new ArrayList<>();

    @AfterEach
    void cleanGeneratedDirectories() {
        generatedDirectories.forEach(FileUtil::del);
    }

    @Test
    void saveHtmlCodeWritesIndexFile() {
        HtmlCodeResult result = new HtmlCodeResult();
        result.setHtmlCode(HTML_CODE);

        File directory = save(result);
        File indexFile = new File(directory, "index.html");

        assertAll(
                () -> assertTrue(directory.isDirectory()),
                () -> assertTrue(indexFile.isFile()),
                () -> assertEquals(HTML_CODE, FileUtil.readString(indexFile, StandardCharsets.UTF_8))
        );
    }

    @Test
    void saveMultiFileCodeWritesAllRequiredFiles() {
        File directory = save(completeMultiFileResult());

        assertAll(
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
                () -> CodeFileSaverExecutor.executeSaver(result)
        );

        assertEquals("CSS 代码内容不能为空", exception.getMessage());
    }

    @Test
    void saverRejectsNullAndUnsupportedResultTypes() {
        CodeResult unsupportedResult = new CodeResult() {
        };

        assertAll(
                () -> assertThrows(BusinessException.class,
                        () -> CodeFileSaverExecutor.executeSaver(null)),
                () -> assertThrows(BusinessException.class,
                        () -> CodeFileSaverExecutor.executeSaver(unsupportedResult))
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
                        () -> CodeFileSaverExecutor.executeSaver(blankHtml)),
                () -> assertThrows(BusinessException.class,
                        () -> CodeFileSaverExecutor.executeSaver(blankMultiHtml)),
                () -> assertThrows(BusinessException.class,
                        () -> CodeFileSaverExecutor.executeSaver(blankMultiJavaScript))
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
                        () -> CodeFileSaverExecutor.executeSaver(incompleteHtml)
                ),
                () -> assertThrows(
                        BusinessException.class,
                        () -> CodeFileSaverExecutor.executeSaver(incompleteMultiFile)
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
                () -> saver.saveCode(result)
        );

        assertEquals("simulated write failure", exception.getMessage());
        assertFalse(createdDirectory.get().toFile().exists());
    }

    private File save(CodeResult result) {
        File directory = CodeFileSaverExecutor.executeSaver(result);
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
