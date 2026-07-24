package com.jack.autocodebackend.core.parser;

import com.jack.autocodebackend.ai.model.CodeResult;
import com.jack.autocodebackend.ai.model.HtmlCodeResult;
import com.jack.autocodebackend.ai.model.MultiFileCodeResult;
import com.jack.autocodebackend.ai.model.enums.CodeGenTypeEnum;
import com.jack.autocodebackend.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CodeParserTest {

    private static final String HTML_CODE = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>测试页面</title>
            </head>
            <body>
                <h1>Hello World!</h1>
            </body>
            </html>
            """.strip();

    private static final String CSS_CODE = """
            h1 {
                color: blue;
                text-align: center;
            }
            """.strip();

    private static final String JS_CODE = "console.log('页面加载完成');";

    private final HtmlCodeParser htmlCodeParser = new HtmlCodeParser();
    private final MultiFileCodeParser multiFileCodeParser = new MultiFileCodeParser();

    @Test
    void parseHtmlCodeExtractsFencedCodeAndRemovesDescription() {
        String codeContent = """
                下面是生成的页面：
                ```html
                %s
                ```
                页面生成完成。
                """.formatted(HTML_CODE);

        HtmlCodeResult result = htmlCodeParser.parseCode(codeContent);

        assertEquals(HTML_CODE, result.getHtmlCode());
    }

    @Test
    void parseHtmlCodeAcceptsCompleteRawHtml() {
        HtmlCodeResult result = htmlCodeParser.parseCode("\n" + HTML_CODE + "\n");

        assertEquals(HTML_CODE, result.getHtmlCode());
    }

    @Test
    void parseHtmlCodeRejectsDescriptionWithoutCodeFence() {
        String codeContent = "下面是页面：\n" + HTML_CODE + "\n页面生成完成。";

        assertThrows(IllegalArgumentException.class, () -> htmlCodeParser.parseCode(codeContent));
    }

    @Test
    void parsersRejectIncompleteFencedHtmlDocuments() {
        String incompleteHtml = "<main>partial</main>";
        String singleFileResponse = "```html\n" + incompleteHtml + "\n```\n";
        String multiFileResponse = completeMultiFileResponse().replace(HTML_CODE, incompleteHtml);

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> htmlCodeParser.parseCode(singleFileResponse)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> multiFileCodeParser.parseCode(multiFileResponse)
                )
        );
    }

    @Test
    void parseMultiFileCodeExtractsAllFencedCode() {
        MultiFileCodeResult result = multiFileCodeParser.parseCode(completeMultiFileResponse());

        assertAll(
                () -> assertEquals(HTML_CODE, result.getHtmlCode()),
                () -> assertEquals(CSS_CODE, result.getCssCode()),
                () -> assertEquals(JS_CODE, result.getJsCode())
        );
    }

    @Test
    void parseMultiFileCodeSupportsUppercaseLanguageAndCrLf() {
        String codeContent = "说明\r\n"
                + "```HTML\r\n" + HTML_CODE + "\r\n```\r\n"
                + "```CSS\r\n" + CSS_CODE + "\r\n```\r\n"
                + "```JS\r\n" + JS_CODE + "\r\n```\r\n";

        MultiFileCodeResult result = multiFileCodeParser.parseCode(codeContent);

        assertAll(
                () -> assertEquals(HTML_CODE, result.getHtmlCode()),
                () -> assertEquals(CSS_CODE, result.getCssCode()),
                () -> assertEquals(JS_CODE, result.getJsCode())
        );
    }

    @Test
    void parseMultiFileCodeRejectsMissingOrBlankCodeBlock() {
        String missingJavaScript = """
                ```html
                %s
                ```
                ```css
                %s
                ```
                """.formatted(HTML_CODE, CSS_CODE);
        String blankCss = """
                ```html
                %s
                ```
                ```css

                ```
                ```javascript
                %s
                ```
                """.formatted(HTML_CODE, JS_CODE);

        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> multiFileCodeParser.parseCode(missingJavaScript)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> multiFileCodeParser.parseCode(blankCss))
        );
    }

    @Test
    void parserRejectsDuplicateCodeBlocks() {
        String codeContent = """
                ```html
                %s
                ```
                ```html
                %s
                ```
                """.formatted(HTML_CODE, HTML_CODE);

        assertThrows(IllegalArgumentException.class, () -> htmlCodeParser.parseCode(codeContent));
    }

    @Test
    void parserRejectsNullAndBlankContent() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> htmlCodeParser.parseCode(null)),
                () -> assertThrows(IllegalArgumentException.class, () -> htmlCodeParser.parseCode(" \n\t")),
                () -> assertThrows(IllegalArgumentException.class, () -> multiFileCodeParser.parseCode(null)),
                () -> assertThrows(IllegalArgumentException.class, () -> multiFileCodeParser.parseCode(" \n\t"))
        );
    }

    @Test
    void executorReturnsTypedResultAndRejectsNullType() {
        CodeResult result = CodeParserExecutor.executeParser(
                completeMultiFileResponse(),
                CodeGenTypeEnum.MULTI_FILE
        );

        assertAll(
                () -> assertInstanceOf(MultiFileCodeResult.class, result),
                () -> assertThrows(BusinessException.class,
                        () -> CodeParserExecutor.executeParser(HTML_CODE, null))
        );
    }

    private static String completeMultiFileResponse() {
        return """
                创建一个完整的网页：
                index.html
                ```html
                %s
                ```

                style.css
                ```css
                %s
                ```

                script.js
                ```javascript
                %s
                ```
                文件创建完成！
                """.formatted(HTML_CODE, CSS_CODE, JS_CODE);
    }
}
