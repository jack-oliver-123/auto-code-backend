package com.jack.autocodebackend.core.parser;

import com.jack.autocodebackend.ai.model.HtmlCodeResult;

import java.util.regex.Pattern;

/**
 * HTML 单文件代码解析器。
 */
public final class HtmlCodeParser implements CodeParser<HtmlCodeResult> {

    private static final Pattern HTML_CODE_PATTERN = CodeBlockExtractor.createCodeBlockPattern("html");
    private static final Pattern COMPLETE_HTML_DOCUMENT_PATTERN = Pattern.compile(
            "^(?:<!doctype\\s+html[^>]*>\\s*)?<html(?:\\s[^>]*)?>[\\s\\S]*</html>$",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public HtmlCodeResult parseCode(String codeContent) {
        String content = CodeBlockExtractor.requireContent(codeContent);
        String htmlCode = CodeBlockExtractor.extractCodeBlock(content, HTML_CODE_PATTERN, "HTML");
        if (htmlCode == null) {
            String rawHtml = content.strip();
            if (!COMPLETE_HTML_DOCUMENT_PATTERN.matcher(rawHtml).matches()) {
                throw new IllegalArgumentException("未找到 HTML 代码块，且返回内容不是完整的 HTML 文档");
            }
            htmlCode = rawHtml;
        }

        HtmlCodeResult result = new HtmlCodeResult();
        result.setHtmlCode(htmlCode);
        return result;
    }
}
