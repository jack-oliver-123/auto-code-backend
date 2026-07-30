package com.jack.autocodebackend.service;

import com.jack.autocodebackend.memory.ChatMemorySnapshot;
import com.jack.autocodebackend.core.vue.VueProjectSourceSnapshot;

public interface ChatMemoryService {

    String buildPrompt(
            Long appId,
            Long beforeId,
            String currentMessage,
            boolean initialGeneration
    );

    String buildPrompt(
            Long appId,
            Long beforeId,
            String currentMessage,
            boolean initialGeneration,
            VueProjectSourceSnapshot sourceSnapshot
    );

    ChatMemorySnapshot loadSnapshot(Long appId, Long beforeId);

    void refresh(Long appId);

    void invalidate(Long appId);

    void purge(Long appId);
}
