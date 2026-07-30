package com.jack.autocodebackend.memory;

import java.util.Objects;

public record VersionedChatMemorySnapshot(
        long version,
        ChatMemorySnapshot snapshot
) {

    public VersionedChatMemorySnapshot {
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        if (version != snapshot.lastHistoryId()) {
            throw new IllegalArgumentException(
                    "version must match the snapshot terminal history id");
        }
    }
}
