package com.jack.autocodebackend.memory;

import java.util.Objects;

public record ChatMemoryMessage(
        long historyId,
        ChatMemoryRole role,
        String content
) {

    public ChatMemoryMessage {
        if (historyId <= 0) {
            throw new IllegalArgumentException("historyId must be positive");
        }
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(content, "content must not be null");
        if (content.isEmpty()) {
            throw new IllegalArgumentException("content must not be empty");
        }
    }
}
