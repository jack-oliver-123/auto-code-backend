package com.jack.autocodebackend.core.validator;

import java.util.regex.Pattern;

public final class HtmlDocumentValidator {

    private static final Pattern COMPLETE_HTML_DOCUMENT_PATTERN = Pattern.compile(
            "^(?:<!doctype\\s+html[^>]*>\\s*)?<html(?:\\s[^>]*)?>[\\s\\S]*</html>$",
            Pattern.CASE_INSENSITIVE
    );

    private HtmlDocumentValidator() {
    }

    public static boolean isCompleteDocument(String htmlCode) {
        return htmlCode != null
                && COMPLETE_HTML_DOCUMENT_PATTERN.matcher(htmlCode.strip()).matches();
    }
}
