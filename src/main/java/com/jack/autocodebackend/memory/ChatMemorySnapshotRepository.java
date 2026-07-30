package com.jack.autocodebackend.memory;

import java.util.Optional;
import java.util.OptionalLong;

public interface ChatMemorySnapshotRepository {

    Optional<VersionedChatMemorySnapshot> find(long appId);

    OptionalLong findVersion(long appId);

    VersionedChatMemorySnapshot save(ChatMemorySnapshot snapshot);

    void delete(long appId);
}
