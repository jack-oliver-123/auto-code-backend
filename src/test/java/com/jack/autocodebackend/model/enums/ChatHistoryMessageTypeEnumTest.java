package com.jack.autocodebackend.model.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatHistoryMessageTypeEnumTest {

    @Test
    void resolvesSupportedValuesAndRejectsUnknownValues() {
        assertThat(ChatHistoryMessageTypeEnum.getEnumByValue("user"))
                .isEqualTo(ChatHistoryMessageTypeEnum.USER);
        assertThat(ChatHistoryMessageTypeEnum.getEnumByValue("ai"))
                .isEqualTo(ChatHistoryMessageTypeEnum.AI);
        assertThat(ChatHistoryMessageTypeEnum.getEnumByValue(null)).isNull();
        assertThat(ChatHistoryMessageTypeEnum.getEnumByValue("")).isNull();
        assertThat(ChatHistoryMessageTypeEnum.getEnumByValue("system")).isNull();
    }
}
