package com.jack.autocodebackend.core.deploy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PreviewSnapshotBundler {

    private static final int HTML_PATTERN_FLAGS = Pattern.CASE_INSENSITIVE | Pattern.DOTALL;

    private static final Pattern LINK_TAG_PATTERN = Pattern.compile("<link\\b[^>]*>", HTML_PATTERN_FLAGS);

    private static final Pattern SCRIPT_ELEMENT_PATTERN = Pattern.compile(
            "<script\\b[^>]*>.*?</script\\s*>",
            HTML_PATTERN_FLAGS
    );

    private static final Pattern HREF_ATTRIBUTE_PATTERN = attributePattern("href");

    private static final Pattern REL_ATTRIBUTE_PATTERN = attributePattern("rel");

    private static final Pattern SRC_ATTRIBUTE_PATTERN = attributePattern("src");

    private static final Pattern HEAD_END_PATTERN = Pattern.compile("</head\\s*>", HTML_PATTERN_FLAGS);

    private static final Pattern BODY_END_PATTERN = Pattern.compile("</body\\s*>", HTML_PATTERN_FLAGS);

    private static final Pattern HTML_END_PATTERN = Pattern.compile("</html\\s*>", HTML_PATTERN_FLAGS);

    private static final Pattern STYLE_END_SEQUENCE_PATTERN = Pattern.compile("</style", Pattern.CASE_INSENSITIVE);

    private static final Pattern SCRIPT_END_SEQUENCE_PATTERN = Pattern.compile("</script", Pattern.CASE_INSENSITIVE);

    private PreviewSnapshotBundler() {
    }

    static void bundle(Path snapshotDirectory) throws IOException {
        Path indexFile = snapshotDirectory.resolve("index.html");
        String html = Files.readString(indexFile, StandardCharsets.UTF_8);
        String css = Files.readString(snapshotDirectory.resolve("style.css"), StandardCharsets.UTF_8);
        String script = Files.readString(snapshotDirectory.resolve("script.js"), StandardCharsets.UTF_8);

        String inlineStyle = "<style data-auto-code-preview>\n"
                + escapeRawText(css, STYLE_END_SEQUENCE_PATTERN, "style")
                + "\n</style>";
        String inlineScript = "<script data-auto-code-preview>\n"
                + escapeRawText(script, SCRIPT_END_SEQUENCE_PATTERN, "script")
                + "\n</script>";

        Replacement styleReplacement = replaceMatchingElements(
                html,
                LINK_TAG_PATTERN,
                PreviewSnapshotBundler::isGeneratedStylesheetLink,
                inlineStyle
        );
        String bundledHtml = styleReplacement.replaced()
                ? styleReplacement.content()
                : insertBeforeClosingTag(html, HEAD_END_PATTERN, inlineStyle);

        Replacement scriptReplacement = replaceMatchingElements(
                bundledHtml,
                SCRIPT_ELEMENT_PATTERN,
                PreviewSnapshotBundler::isGeneratedScriptElement,
                inlineScript
        );
        bundledHtml = scriptReplacement.replaced()
                ? scriptReplacement.content()
                : insertBeforeClosingTag(bundledHtml, BODY_END_PATTERN, inlineScript);

        Files.writeString(
                indexFile,
                bundledHtml,
                StandardCharsets.UTF_8,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS
        );
    }

    private static boolean isGeneratedStylesheetLink(String tag) {
        return referencesAsset(tag, HREF_ATTRIBUTE_PATTERN, "style.css")
                && attributeContainsToken(tag, REL_ATTRIBUTE_PATTERN, "stylesheet");
    }

    private static boolean isGeneratedScriptElement(String element) {
        int startTagEnd = element.indexOf('>');
        String startTag = startTagEnd < 0 ? element : element.substring(0, startTagEnd + 1);
        return referencesAsset(startTag, SRC_ATTRIBUTE_PATTERN, "script.js");
    }

    private static boolean referencesAsset(String tag, Pattern attributePattern, String assetName) {
        String value = readAttribute(tag, attributePattern);
        if (value == null) {
            return false;
        }

        String normalized = value.strip().replace('\\', '/');
        int suffixIndex = firstSuffixIndex(normalized);
        if (suffixIndex >= 0) {
            normalized = normalized.substring(0, suffixIndex);
        }
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return assetName.equalsIgnoreCase(normalized);
    }

    private static boolean attributeContainsToken(String tag, Pattern attributePattern, String token) {
        String value = readAttribute(tag, attributePattern);
        if (value == null) {
            return false;
        }
        for (String candidate : value.strip().split("\\s+")) {
            if (token.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static String readAttribute(String tag, Pattern attributePattern) {
        Matcher matcher = attributePattern.matcher(tag);
        if (!matcher.find()) {
            return null;
        }
        for (int group = 1; group <= matcher.groupCount(); group++) {
            if (matcher.group(group) != null) {
                return matcher.group(group);
            }
        }
        return null;
    }

    private static Replacement replaceMatchingElements(
            String html,
            Pattern elementPattern,
            Predicate<String> predicate,
            String replacement
    ) {
        Matcher matcher = elementPattern.matcher(html);
        StringBuilder result = null;
        int cursor = 0;
        boolean replaced = false;

        while (matcher.find()) {
            String element = matcher.group();
            if (!predicate.test(element)) {
                continue;
            }
            if (result == null) {
                result = new StringBuilder(html.length() + replacement.length());
            }
            result.append(html, cursor, matcher.start());
            if (!replaced) {
                result.append(replacement);
                replaced = true;
            }
            cursor = matcher.end();
        }

        if (!replaced) {
            return new Replacement(html, false);
        }
        result.append(html, cursor, html.length());
        return new Replacement(result.toString(), true);
    }

    private static String insertBeforeClosingTag(String html, Pattern closingTag, String insertion) {
        Matcher matcher = closingTag.matcher(html);
        if (matcher.find()) {
            return html.substring(0, matcher.start())
                    + insertion
                    + '\n'
                    + html.substring(matcher.start());
        }

        Matcher htmlEnd = HTML_END_PATTERN.matcher(html);
        if (htmlEnd.find()) {
            return html.substring(0, htmlEnd.start())
                    + insertion
                    + '\n'
                    + html.substring(htmlEnd.start());
        }
        return html + '\n' + insertion;
    }

    private static String escapeRawText(String content, Pattern closingSequence, String tagName) {
        Matcher matcher = closingSequence.matcher(content);
        if (!matcher.find()) {
            return content;
        }

        StringBuilder escaped = new StringBuilder(content.length() + 8);
        int cursor = 0;
        do {
            escaped.append(content, cursor, matcher.start());
            escaped.append("<\\/").append(tagName);
            cursor = matcher.end();
        } while (matcher.find());
        escaped.append(content, cursor, content.length());
        return escaped.toString();
    }

    private static int firstSuffixIndex(String value) {
        int queryIndex = value.indexOf('?');
        int fragmentIndex = value.indexOf('#');
        if (queryIndex < 0) {
            return fragmentIndex;
        }
        if (fragmentIndex < 0) {
            return queryIndex;
        }
        return Math.min(queryIndex, fragmentIndex);
    }

    private static Pattern attributePattern(String name) {
        return Pattern.compile(
                "\\b" + name + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))",
                HTML_PATTERN_FLAGS
        );
    }

    private record Replacement(String content, boolean replaced) {
    }
}
