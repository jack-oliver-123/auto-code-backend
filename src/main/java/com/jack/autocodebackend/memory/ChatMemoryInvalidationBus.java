package com.jack.autocodebackend.memory;

import java.util.function.LongConsumer;

public interface ChatMemoryInvalidationBus {

    void register(LongConsumer listener);

    void ensureListening();

    void publishRefresh(long appId, long version);

    void publishDelete(long appId);
}
