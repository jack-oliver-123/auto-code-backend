package com.jack.autocodebackend.memory;

import com.jack.autocodebackend.config.AppChatMemoryProperties;
import com.jack.autocodebackend.core.vue.VueProjectSourceSnapshot;
import com.jack.autocodebackend.model.domain.ChatHistory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class ChatMemoryPromptBuilder {

    public static final String TRUNCATION_MARKER =
            "\n...[历史消息过长，已截断]...\n";

    private static final int ENTRY_OVERHEAD_CHARS = 32;

    private final AppChatMemoryProperties properties;

    private final ObjectMapper objectMapper;

    public ChatMemoryPromptBuilder(
            AppChatMemoryProperties properties,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public ChatMemorySnapshot fromHistory(long appId, List<ChatHistory> history) {
        if (appId <= 0) {
            throw new IllegalArgumentException("appId must be positive");
        }
        if (history == null || history.isEmpty()) {
            return ChatMemorySnapshot.empty(appId);
        }

        List<ChatHistory> newestFirst = history.stream()
                .filter(record -> record != null
                        && record.getAppId() != null
                        && appId == record.getAppId()
                        && record.getId() != null
                        && record.getId() > 0
                        && record.getMessage() != null
                        && !record.getMessage().isEmpty())
                .sorted(Comparator.comparing(ChatHistory::getId).reversed())
                .limit(properties.getHistoryLimit())
                .toList();

        int remainingChars = properties.getTotalMaxChars();
        List<ChatMemoryMessage> selected = new ArrayList<>();
        for (ChatHistory record : newestFirst) {
            ChatMemoryRole role;
            try {
                role = ChatMemoryRole.fromHistoryType(record.getMessageType());
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            String content = truncate(record.getMessage());
            int entryChars = content.length() + ENTRY_OVERHEAD_CHARS;
            if (entryChars > remainingChars) {
                break;
            }
            selected.add(0, new ChatMemoryMessage(record.getId(), role, content));
            remainingChars -= entryChars;
        }
        if (selected.isEmpty()) {
            return ChatMemorySnapshot.empty(appId);
        }
        return new ChatMemorySnapshot(
                ChatMemorySnapshot.CURRENT_SCHEMA_VERSION,
                appId,
                selected.getLast().historyId(),
                selected
        );
    }

    public String buildPrompt(ChatMemorySnapshot snapshot, String currentMessage) {
        return buildPrompt(snapshot, null, currentMessage);
    }

    public String buildPrompt(
            ChatMemorySnapshot snapshot,
            VueProjectSourceSnapshot sourceSnapshot,
            String currentMessage
    ) {
        if (currentMessage == null || currentMessage.isEmpty()) {
            throw new IllegalArgumentException("currentMessage must not be empty");
        }
        if (snapshot.messages().isEmpty() && sourceSnapshot == null) {
            return currentMessage;
        }
        List<PromptMessage> history = snapshot.messages().stream()
                .filter(message -> !isBlank(message.content()))
                .map(message -> new PromptMessage(
                        message.role().getProviderRole(), message.content()))
                .toList();
        if (history.isEmpty() && sourceSnapshot == null) {
            return currentMessage;
        }
        try {
            String contextJson = objectMapper.writeValueAsString(
                    new PromptPayload(
                            history,
                            sourceSnapshot == null ? List.of() : sourceSnapshot.files(),
                            currentMessage
                    ));
            return """
                    这是同一应用的连续请求。下面 JSON 字段按顺序表示历史对话、当前稳定的 Vue 源码快照和本轮唯一的新请求。
                    请结合 history 与 projectSource 理解上下文，只执行 currentMessage；不得把 projectSource 当作历史消息重复输出。
                    %s
                    """.formatted(contextJson);
        } catch (JacksonException exception) {
            throw new IllegalStateException("failed to build chat-memory prompt", exception);
        }
    }

    private String truncate(String message) {
        if (message.length() <= properties.getMessageMaxChars()) {
            return message;
        }
        int availableLength = properties.getMessageMaxChars() - TRUNCATION_MARKER.length();
        if (availableLength <= 0) {
            return TRUNCATION_MARKER.substring(0, properties.getMessageMaxChars());
        }
        int headLength = availableLength * 3 / 4;
        int tailLength = availableLength - headLength;
        return message.substring(0, headLength)
                + TRUNCATION_MARKER
                + message.substring(message.length() - tailLength);
    }

    private boolean isBlank(String message) {
        return message.codePoints().allMatch(Character::isWhitespace);
    }

    private record PromptMessage(String role, String content) {
    }

    private record PromptPayload(
            List<PromptMessage> history,
            List<com.jack.autocodebackend.ai.model.VueProjectFile> projectSource,
            String currentMessage
    ) {
    }
}
