package com.jack.autocodebackend.core.parser;

import com.jack.autocodebackend.ai.model.VueProjectCodeResult;
import com.jack.autocodebackend.ai.model.VueProjectFile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VueProjectCodeParser implements CodeParser<VueProjectCodeResult> {

    public static final String START_MARKER = "<<<AUTO_CODE_PROJECT_V1>>>";
    public static final String END_MARKER = "<<<END_AUTO_CODE_PROJECT_V1>>>";

    private static final String PROJECT_START_PREFIX = "<<<AUTO_CODE_PROJECT_";
    private static final String PROJECT_END_PREFIX = "<<<END_AUTO_CODE_PROJECT_";
    private static final Pattern FILE_DECLARATION = Pattern.compile("FILE: ([^\\r\\n]+)");
    private static final Pattern OPENING_FENCE =
            Pattern.compile("```[A-Za-z0-9_+.-]+[\\t ]*");

    @Override
    public VueProjectCodeResult parseCode(String codeContent) {
        String content = CodeBlockExtractor.requireContent(codeContent);
        List<Line> lines = scanLines(content);
        int startIndex = findSingleBoundary(lines, START_MARKER, true);
        int endIndex = findSingleBoundary(lines, END_MARKER, false);
        if (startIndex < 0) {
            if (containsProjectMarkerSyntax(lines)) {
                throw new VueProjectProtocolException("Unsupported Vue project protocol marker");
            }
            throw new NoProjectEnvelopeException();
        }
        if (endIndex < 0 || endIndex <= startIndex) {
            throw new VueProjectProtocolException("Vue project envelope is incomplete");
        }
        rejectOtherProjectMarkers(lines, startIndex, endIndex);
        rejectProjectSyntaxOutsideEnvelope(lines, startIndex, endIndex);

        List<VueProjectFile> files = new ArrayList<>();
        Set<String> declaredPaths = new HashSet<>();
        int index = startIndex + 1;
        while (index < endIndex) {
            Line declarationLine = lines.get(index);
            if (declarationLine.text().isBlank()) {
                index++;
                continue;
            }
            Matcher declaration = FILE_DECLARATION.matcher(declarationLine.text());
            if (!declaration.matches()) {
                throw protocolFailure("Expected a FILE declaration", declarationLine);
            }
            String path = declaration.group(1);
            if (!path.equals(path.strip())) {
                throw protocolFailure("FILE path contains surrounding whitespace", declarationLine);
            }
            String duplicateKey = path.toLowerCase(Locale.ROOT);
            if (!declaredPaths.add(duplicateKey)) {
                throw protocolFailure("Duplicate FILE declaration", declarationLine);
            }

            int fenceIndex = index + 1;
            if (fenceIndex >= endIndex
                    || !OPENING_FENCE.matcher(lines.get(fenceIndex).text()).matches()) {
                throw protocolFailure("FILE declaration is not followed by a source fence",
                        declarationLine);
            }
            int closingFenceIndex = findClosingFence(lines, fenceIndex + 1, endIndex);
            Line openingFence = lines.get(fenceIndex);
            Line closingFence = lines.get(closingFenceIndex);
            String fileContent = content.substring(openingFence.nextStart(), closingFence.start());
            if (fileContent.isBlank()) {
                throw protocolFailure("Project file content must not be blank", declarationLine);
            }
            files.add(new VueProjectFile(path, fileContent));
            index = closingFenceIndex + 1;
        }
        if (files.isEmpty()) {
            throw new VueProjectProtocolException("Vue project envelope contains no files");
        }
        return new VueProjectCodeResult(files, null);
    }

    private int findSingleBoundary(List<Line> lines, String marker, boolean opening) {
        int found = -1;
        for (int index = 0; index < lines.size(); index++) {
            if (!lines.get(index).text().equals(marker)) {
                continue;
            }
            if (found >= 0) {
                throw new VueProjectProtocolException(
                        opening ? "Repeated project opening marker" : "Repeated project ending marker");
            }
            found = index;
        }
        return found;
    }

    private boolean containsProjectMarkerSyntax(List<Line> lines) {
        return lines.stream().map(Line::text).anyMatch(this::hasProjectMarkerSyntax);
    }

    private void rejectOtherProjectMarkers(List<Line> lines, int startIndex, int endIndex) {
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index).text();
            if (!isProjectMarker(line)) {
                continue;
            }
            if ((index == startIndex && line.equals(START_MARKER))
                    || (index == endIndex && line.equals(END_MARKER))) {
                continue;
            }
            throw protocolFailure("Unexpected or unsupported project marker", lines.get(index));
        }
    }

    private void rejectProjectSyntaxOutsideEnvelope(
            List<Line> lines,
            int startIndex,
            int endIndex
    ) {
        for (int index = 0; index < lines.size(); index++) {
            if (index >= startIndex && index <= endIndex) {
                continue;
            }
            String line = lines.get(index).text();
            if (line.startsWith("FILE:") || line.startsWith("```")
                    || hasProjectMarkerSyntax(line)) {
                throw protocolFailure("Project syntax appears outside the envelope", lines.get(index));
            }
        }
    }

    private boolean hasProjectMarkerSyntax(String line) {
        return line.contains("<<<AUTO_CODE_PROJECT_")
                || line.contains("<<<END_AUTO_CODE_PROJECT_");
    }

    private int findClosingFence(List<Line> lines, int fromIndex, int endIndex) {
        for (int index = fromIndex; index < endIndex; index++) {
            String line = lines.get(index).text();
            if (line.equals("```")) {
                return index;
            }
            if (line.startsWith("```")) {
                throw protocolFailure("Nested or malformed source fence", lines.get(index));
            }
        }
        throw new VueProjectProtocolException("Project file source fence is incomplete");
    }

    private boolean isProjectMarker(String line) {
        return (line.startsWith(PROJECT_START_PREFIX) || line.startsWith(PROJECT_END_PREFIX))
                && line.endsWith(">>>");
    }

    private VueProjectProtocolException protocolFailure(String message, Line line) {
        return new VueProjectProtocolException(message + " at line " + line.number());
    }

    private List<Line> scanLines(String content) {
        List<Line> lines = new ArrayList<>();
        int start = 0;
        int lineNumber = 1;
        while (start < content.length()) {
            int newline = content.indexOf('\n', start);
            int rawEnd = newline < 0 ? content.length() : newline;
            int textEnd = rawEnd > start && content.charAt(rawEnd - 1) == '\r'
                    ? rawEnd - 1
                    : rawEnd;
            int nextStart = newline < 0 ? content.length() : newline + 1;
            lines.add(new Line(
                    lineNumber++, start, nextStart, content.substring(start, textEnd)));
            start = nextStart;
        }
        if (content.endsWith("\n")) {
            lines.add(new Line(lineNumber, content.length(), content.length(), ""));
        }
        return List.copyOf(lines);
    }

    private record Line(int number, int start, int nextStart, String text) {
    }

    public static final class NoProjectEnvelopeException extends IllegalArgumentException {

        public NoProjectEnvelopeException() {
            super("AI response does not contain a Vue project envelope");
        }
    }

    public static final class VueProjectProtocolException extends IllegalArgumentException {

        public VueProjectProtocolException(String message) {
            super(message);
        }
    }
}
