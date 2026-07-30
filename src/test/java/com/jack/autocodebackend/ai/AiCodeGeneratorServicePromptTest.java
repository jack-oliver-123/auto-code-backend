package com.jack.autocodebackend.ai;

import dev.langchain4j.service.SystemMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiCodeGeneratorServicePromptTest {

    @Test
    void usesOutputFormatSpecificPrompts() throws Exception {
        assertPromptContract(
                "generateHtmlCode",
                "prompt/codegen-html-structured-system-prompt.md",
                false,
                "htmlCode",
                "description"
        );
        assertPromptContract(
                "generateMultiFileCode",
                "prompt/codegen-multi-file-structured-system-prompt.md",
                false,
                "htmlCode",
                "cssCode",
                "jsCode",
                "description"
        );
        assertPromptContract(
                "generateVueProjectCode",
                "prompt/codegen-vue-structured-system-prompt.md",
                false,
                "src/main.js",
                "src/App.vue",
                "src/router/index.js",
                "createWebHashHistory()"
        );
        assertPromptContract(
                "generateHtmlCodeStream",
                "prompt/codegen-html-system-prompt.md",
                true
        );
        assertPromptContract(
                "generateMultiFileCodeStream",
                "prompt/codegen-multi-file-system-prompt.md",
                true
        );
        assertPromptContract(
                "generateVueProjectCodeStream",
                "prompt/codegen-vue-system-prompt.md",
                true,
                "<<<AUTO_CODE_PROJECT_V1>>>",
                "<<<END_AUTO_CODE_PROJECT_V1>>>",
                "createWebHashHistory()",
                "普通对话模式"
        );

        String vuePrompt = readClasspathResource("prompt/codegen-vue-system-prompt.md");
        assertTrue(vuePrompt.contains("没有文件写入、读取或终端工具"));
        assertTrue(!vuePrompt.contains("writeFile("));
    }

    private void assertPromptContract(
            String methodName,
            String expectedResource,
            boolean expectsMarkdownFences,
            String... requiredFieldNames
    ) throws NoSuchMethodException, IOException {
        Method method = AiCodeGeneratorService.class.getMethod(methodName, String.class);
        SystemMessage systemMessage = method.getAnnotation(SystemMessage.class);

        assertNotNull(systemMessage);
        assertEquals(expectedResource, systemMessage.fromResource());

        String prompt = readClasspathResource(expectedResource);
        assertEquals(expectsMarkdownFences, prompt.contains("```"));
        for (String fieldName : requiredFieldNames) {
            assertTrue(prompt.contains(fieldName), () -> expectedResource + " must describe " + fieldName);
        }
    }

    private String readClasspathResource(String resourcePath) throws IOException {
        ClassLoader classLoader = AiCodeGeneratorServicePromptTest.class.getClassLoader();
        try (InputStream inputStream = classLoader.getResourceAsStream(resourcePath)) {
            assertNotNull(inputStream, () -> "Missing prompt resource: " + resourcePath);
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
