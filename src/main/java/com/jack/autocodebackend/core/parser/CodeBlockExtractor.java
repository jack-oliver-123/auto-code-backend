package com.jack.autocodebackend.core.parser;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 代码块提取工具。
 */
final class CodeBlockExtractor {

    private static final int CODE_BLOCK_PATTERN_FLAGS = Pattern.CASE_INSENSITIVE | Pattern.MULTILINE;

    private CodeBlockExtractor() {
    }

    static Pattern createCodeBlockPattern(String languagePattern) {
        return Pattern.compile(
                "^[\\t ]*```[\\t ]*(?:" + languagePattern + ")[\\t ]*\\R"
                        + "([\\s\\S]*?)"
                        + "^[\\t ]*```[\\t ]*(?:\\R|\\z)",
                CODE_BLOCK_PATTERN_FLAGS
        );
    }

    static String requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("AI 返回内容不能为空");
        }
        return content;
    }

    static String extractRequiredCodeBlock(String content, Pattern pattern, String blockName) {
        String code = extractCodeBlock(content, pattern, blockName);
        if (code == null) {
            throw new IllegalArgumentException("未找到 " + blockName + " 代码块");
        }
        return code;
    }

    static String extractCodeBlock(String content, Pattern pattern, String blockName) {
        Matcher matcher = pattern.matcher(content);
        if (!matcher.find()) {
            return null;
        }

        String code = matcher.group(1).strip();
        if (code.isEmpty()) {
            throw new IllegalArgumentException(blockName + " 代码块内容不能为空");
        }
        if (matcher.find()) {
            throw new IllegalArgumentException("检测到多个 " + blockName + " 代码块");
        }
        return code;
    }
}
