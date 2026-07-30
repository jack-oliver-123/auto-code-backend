package com.jack.autocodebackend.ai.model.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CodeGenTypeEnumTest {

    @Test
    void resolvesSupportedValuesAndRejectsMissingOrUnknownValues() {
        assertAll(
                () -> assertEquals(CodeGenTypeEnum.HTML, CodeGenTypeEnum.getEnumByValue("html")),
                () -> assertEquals(
                        CodeGenTypeEnum.MULTI_FILE,
                        CodeGenTypeEnum.getEnumByValue("multi_file")
                ),
                () -> assertEquals(
                        CodeGenTypeEnum.VUE_PROJECT,
                        CodeGenTypeEnum.getEnumByValue("vue_project")
                ),
                () -> assertEquals("dist",
                        CodeGenTypeEnum.VUE_PROJECT.getStaticRootDirectory()),
                () -> assertEquals(
                        java.util.List.of("index.html"),
                        CodeGenTypeEnum.VUE_PROJECT.getRequiredStaticFiles()),
                () -> assertNull(CodeGenTypeEnum.getEnumByValue(null)),
                () -> assertNull(CodeGenTypeEnum.getEnumByValue("")),
                () -> assertNull(CodeGenTypeEnum.getEnumByValue("unsupported"))
        );
    }
}
