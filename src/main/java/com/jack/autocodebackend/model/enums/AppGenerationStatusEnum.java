package com.jack.autocodebackend.model.enums;

import java.util.Arrays;

/**
 * Persisted lifecycle of an application's latest generation attempt.
 */
public enum AppGenerationStatusEnum {

    PENDING,
    GENERATING,
    SUCCEEDED,
    FAILED;

    public String getValue() {
        return name();
    }

    public static AppGenerationStatusEnum getEnumByValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Arrays.stream(values())
                .filter(status -> status.name().equals(value))
                .findFirst()
                .orElse(null);
    }
}
