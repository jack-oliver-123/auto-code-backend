package com.jack.autocodebackend.memory;

import com.jack.autocodebackend.model.enums.ChatHistoryMessageTypeEnum;

import java.util.Arrays;

public enum ChatMemoryRole {

    USER("user", "user"),
    AI("ai", "assistant");

    private final String value;

    private final String providerRole;

    ChatMemoryRole(String value, String providerRole) {
        this.value = value;
        this.providerRole = providerRole;
    }

    public String getValue() {
        return value;
    }

    public String getProviderRole() {
        return providerRole;
    }

    public static ChatMemoryRole fromHistoryType(String value) {
        ChatHistoryMessageTypeEnum historyType =
                ChatHistoryMessageTypeEnum.getEnumByValue(value);
        if (historyType == null) {
            throw new IllegalArgumentException("unsupported chat-memory role");
        }
        return historyType == ChatHistoryMessageTypeEnum.USER ? USER : AI;
    }

    public static ChatMemoryRole fromValue(String value) {
        return Arrays.stream(values())
                .filter(role -> role.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unsupported chat-memory role"));
    }
}
