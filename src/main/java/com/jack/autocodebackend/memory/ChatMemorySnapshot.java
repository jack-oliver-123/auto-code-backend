package com.jack.autocodebackend.memory;

import java.util.List;

public record ChatMemorySnapshot(
        int schemaVersion,
        long appId,
        long lastHistoryId,
        List<ChatMemoryMessage> messages
) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public ChatMemorySnapshot {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported chat-memory schema version");
        }
        if (appId <= 0) {
            throw new IllegalArgumentException("appId must be positive");
        }
        if (lastHistoryId < 0) {
            throw new IllegalArgumentException("lastHistoryId must not be negative");
        }
        messages = List.copyOf(messages);
        long previousId = 0;
        for (ChatMemoryMessage message : messages) {
            if (message.historyId() <= previousId) {
                throw new IllegalArgumentException(
                        "chat-memory messages must be ordered by historyId");
            }
            previousId = message.historyId();
        }
        if (!messages.isEmpty() && lastHistoryId != messages.getLast().historyId()) {
            throw new IllegalArgumentException(
                    "lastHistoryId must match the terminal message");
        }
        if (messages.isEmpty() && lastHistoryId != 0) {
            throw new IllegalArgumentException(
                    "an empty snapshot must use lastHistoryId zero");
        }
    }

    public static ChatMemorySnapshot empty(long appId) {
        return new ChatMemorySnapshot(CURRENT_SCHEMA_VERSION, appId, 0, List.of());
    }
}
