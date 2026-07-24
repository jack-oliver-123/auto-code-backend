package com.jack.autocodebackend.core.parser;

import com.jack.autocodebackend.ai.model.MultiFileCodeResult;

import java.util.regex.Pattern;

/**
 * 多文件代码解析器（HTML + CSS + JavaScript）。
 */
public final class MultiFileCodeParser implements CodeParser<MultiFileCodeResult> {

    private static final Pattern HTML_CODE_PATTERN = CodeBlockExtractor.createCodeBlockPattern("html");
    private static final Pattern CSS_CODE_PATTERN = CodeBlockExtractor.createCodeBlockPattern("css");
    private static final Pattern JS_CODE_PATTERN = CodeBlockExtractor.createCodeBlockPattern("js|javascript");

    @Override
    public MultiFileCodeResult parseCode(String codeContent) {
        String content = CodeBlockExtractor.requireContent(codeContent);
        String htmlCode = CodeBlockExtractor.extractRequiredCodeBlock(content, HTML_CODE_PATTERN, "HTML");
        String cssCode = CodeBlockExtractor.extractRequiredCodeBlock(content, CSS_CODE_PATTERN, "CSS");
        String jsCode = CodeBlockExtractor.extractRequiredCodeBlock(content, JS_CODE_PATTERN, "JavaScript");

        MultiFileCodeResult result = new MultiFileCodeResult();
        result.setHtmlCode(htmlCode);
        result.setCssCode(cssCode);
        result.setJsCode(jsCode);
        return result;
    }
}
