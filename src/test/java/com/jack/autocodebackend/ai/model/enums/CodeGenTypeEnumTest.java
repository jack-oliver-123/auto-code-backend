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
                () -> assertNull(CodeGenTypeEnum.getEnumByValue(null)),
                () -> assertNull(CodeGenTypeEnum.getEnumByValue("")),
                () -> assertNull(CodeGenTypeEnum.getEnumByValue("unsupported"))
        );
    }
}
