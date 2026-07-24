package com.jack.autocodebackend.core.parser;

import com.jack.autocodebackend.ai.model.HtmlCodeResult;
import com.jack.autocodebackend.core.validator.HtmlDocumentValidator;

import java.util.regex.Pattern;

/**
 * HTML 单文件代码解析器。
 */
public final class HtmlCodeParser implements CodeParser<HtmlCodeResult> {

    private static final Pattern HTML_CODE_PATTERN = CodeBlockExtractor.createCodeBlockPattern("html");
    @Override
    public HtmlCodeResult parseCode(String codeContent) {
        String content = CodeBlockExtractor.requireContent(codeContent);
        String htmlCode = CodeBlockExtractor.extractCodeBlock(content, HTML_CODE_PATTERN, "HTML");
        if (htmlCode == null) {
            htmlCode = content.strip();
        }
        if (!HtmlDocumentValidator.isCompleteDocument(htmlCode)) {
            throw new IllegalArgumentException("HTML 代码不是完整的 HTML 文档");
        }

        HtmlCodeResult result = new HtmlCodeResult();
        result.setHtmlCode(htmlCode);
        return result;
    }
}
