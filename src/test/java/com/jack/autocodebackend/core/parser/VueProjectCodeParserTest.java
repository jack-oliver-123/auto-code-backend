package com.jack.autocodebackend.core.parser;

import com.jack.autocodebackend.ai.model.VueProjectCodeResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VueProjectCodeParserTest {

    private final VueProjectCodeParser parser = new VueProjectCodeParser();

    @Test
    void parsesCompleteEnvelopeAndPreservesFileWhitespaceExactly() {
        String appContent = "  <template>\r\n  <main>hello</main>  \r\n</template>\r\n";
        String response = "计划很短\n"
                + VueProjectCodeParser.START_MARKER + "\n"
                + "FILE: src/main.js\n```javascript\nimport './style.css'\n```\n"
                + "FILE: src/App.vue\n```vue\n" + appContent + "```\n"
                + "FILE: src/router/index.js\n```javascript\n"
                + "createWebHashHistory()\n```\n"
                + VueProjectCodeParser.END_MARKER + "\n完成";

        VueProjectCodeResult result = parser.parseCode(response);

        assertThat(result.files()).extracting("path").containsExactly(
                "src/main.js", "src/App.vue", "src/router/index.js");
        assertThat(result.files().get(1).content()).isEqualTo(appContent);
    }

    @Test
    void concatenatedSplitMarkersParseWithoutChangingTheResponse() {
        List<String> chunks = List.of(
                "<<<AUTO_CODE_", "PROJECT_V1>>>\nFILE: src/main.js\n```js\n",
                "const x = '  ';\n", "```\n<<<END_AUTO_CODE_PROJECT_V1>>>"
        );
        String response = String.join("", chunks);

        assertThat(parser.parseCode(response).files().getFirst().content())
                .isEqualTo("const x = '  ';\n");
    }

    @Test
    void noMarkerIsDistinctFromMalformedProjectOutput() {
        assertThatThrownBy(() -> parser.parseCode("这是普通问题的回答。"))
                .isInstanceOf(VueProjectCodeParser.NoProjectEnvelopeException.class);
        assertThatThrownBy(() -> parser.parseCode(
                "<<<AUTO_CODE_PROJECT_V2>>>\n<<<END_AUTO_CODE_PROJECT_V2>>>"))
                .isInstanceOf(VueProjectCodeParser.VueProjectProtocolException.class);
        assertThatThrownBy(() -> parser.parseCode(
                "计划\n<<<AUTO_CODE_PROJECT_V1>> missing delimiter"))
                .isInstanceOf(VueProjectCodeParser.VueProjectProtocolException.class);
        assertThatThrownBy(() -> parser.parseCode(
                "自然语言中的 <<<AUTO_CODE_PROJECT_V3>>> 不完整输出"))
                .isInstanceOf(VueProjectCodeParser.VueProjectProtocolException.class);
        assertThatThrownBy(() -> parser.parseCode(
                "<<<AUTO_CODE_PROJECT_V1>>>\nFILE: src/App.vue\n```vue\n<div/>\n```"))
                .isInstanceOf(VueProjectCodeParser.VueProjectProtocolException.class);
    }

    @Test
    void rejectsDuplicatesTrailingSyntaxAndMalformedFences() {
        String validBlock = "FILE: src/App.vue\n```vue\n<div/>\n```\n";
        assertProtocolFailure(VueProjectCodeParser.START_MARKER + "\n"
                + validBlock + validBlock + VueProjectCodeParser.END_MARKER);
        assertProtocolFailure(VueProjectCodeParser.START_MARKER + "\n"
                + validBlock + VueProjectCodeParser.END_MARKER + "\n```js\nx\n```");
        assertProtocolFailure(VueProjectCodeParser.START_MARKER + "\n"
                + "FILE: src/App.vue\n```vue\n<div/>\n```js\nx\n```\n"
                + VueProjectCodeParser.END_MARKER);
        assertProtocolFailure(VueProjectCodeParser.START_MARKER + "\n"
                + validBlock + VueProjectCodeParser.END_MARKER + "\n"
                + VueProjectCodeParser.END_MARKER);
    }

    private void assertProtocolFailure(String response) {
        assertThatThrownBy(() -> parser.parseCode(response))
                .isInstanceOf(VueProjectCodeParser.VueProjectProtocolException.class);
    }
}
