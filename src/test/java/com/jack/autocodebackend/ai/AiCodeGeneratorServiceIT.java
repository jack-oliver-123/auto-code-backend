package com.jack.autocodebackend.ai;

import com.jack.autocodebackend.ai.model.HtmlCodeResult;
import com.jack.autocodebackend.ai.model.MultiFileCodeResult;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("local")
class AiCodeGeneratorServiceIT {

    @Resource
    private AiCodeGeneratorService aiCodeGeneratorService;

    @Test
    void generateHtmlCode() {
        HtmlCodeResult result = aiCodeGeneratorService.generateHtmlCode("做个Jack的工作记录小工具");

        Assertions.assertNotNull(result);
        Assertions.assertAll(
                () -> assertRawCode(result.getHtmlCode()),
                () -> assertDescription(result.getDescription())
        );
    }

    @Test
    void generateMultiFileCode() {
        MultiFileCodeResult multiFileCode = aiCodeGeneratorService.generateMultiFileCode("做个Jack的留言板");

        Assertions.assertNotNull(multiFileCode);
        Assertions.assertAll(
                () -> assertRawCode(multiFileCode.getHtmlCode()),
                () -> assertRawCode(multiFileCode.getCssCode()),
                () -> assertRawCode(multiFileCode.getJsCode()),
                () -> assertDescription(multiFileCode.getDescription())
        );
    }

    private void assertRawCode(String code) {
        Assertions.assertNotNull(code);
        Assertions.assertFalse(code.isBlank());
        Assertions.assertFalse(code.contains("```"));
    }

    private void assertDescription(String description) {
        Assertions.assertNotNull(description);
        Assertions.assertFalse(description.isBlank());
    }
}
