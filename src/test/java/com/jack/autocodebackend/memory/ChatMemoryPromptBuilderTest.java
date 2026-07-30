package com.jack.autocodebackend.memory;

import com.jack.autocodebackend.config.AppChatMemoryProperties;
import com.jack.autocodebackend.ai.model.VueProjectFile;
import com.jack.autocodebackend.core.vue.VueProjectSourceSnapshot;
import com.jack.autocodebackend.model.domain.ChatHistory;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatMemoryPromptBuilderTest {

    private static final long APP_ID = 5_000_000_000L;

    @Test
    void buildsChronologicalStructuredPromptWithDeterministicTruncation() {
        ChatMemoryPromptBuilder builder = new ChatMemoryPromptBuilder(
                properties(10, 80, 300), JsonMapper.builder().build());
        List<ChatHistory> newestFirst = List.of(
                history(3L, "ai", "third"),
                history(2L, "user", "x".repeat(100)),
                history(1L, "user", "first")
        );

        ChatMemorySnapshot snapshot = builder.fromHistory(APP_ID, newestFirst);
        String prompt = builder.buildPrompt(snapshot, "  current\nmessage  ");

        assertThat(snapshot.messages()).extracting(ChatMemoryMessage::historyId)
                .containsExactly(1L, 2L, 3L);
        assertThat(snapshot.messages().get(1).content())
                .hasSize(80)
                .contains(ChatMemoryPromptBuilder.TRUNCATION_MARKER);
        assertThat(prompt).contains(
                "\"role\":\"user\"",
                "\"role\":\"assistant\"",
                "\"currentMessage\":\"  current\\nmessage  \"");
        assertThat(prompt.indexOf("first")).isLessThan(prompt.indexOf("third"));
        assertThat(prompt.split("current\\\\nmessage", -1)).hasSize(2);
    }

    @Test
    void enforcesRecordAndTotalBoundsUsingNewestMessages() {
        ChatMemoryPromptBuilder builder = new ChatMemoryPromptBuilder(
                properties(2, 50, 164), JsonMapper.builder().build());
        List<ChatHistory> records = new ArrayList<>();
        for (long id = 1; id <= 4; id++) {
            records.add(history(id, id % 2 == 0 ? "ai" : "user", "m".repeat(50)));
        }

        ChatMemorySnapshot snapshot = builder.fromHistory(APP_ID, records);

        assertThat(snapshot.messages()).hasSize(2);
        assertThat(snapshot.messages()).extracting(ChatMemoryMessage::historyId)
                .containsExactly(3L, 4L);
        assertThat(snapshot.lastHistoryId()).isEqualTo(4L);
        assertThat(snapshot.messages().stream()
                .mapToInt(message -> message.content().length() + 32)
                .sum()).isLessThanOrEqualTo(164);
    }

    @Test
    void modelsAreImmutableAndRejectInvalidRolesOrderingAndVersions() {
        List<ChatMemoryMessage> mutable = new ArrayList<>();
        mutable.add(new ChatMemoryMessage(1L, ChatMemoryRole.USER, "one"));
        ChatMemorySnapshot snapshot = new ChatMemorySnapshot(1, APP_ID, 1L, mutable);
        mutable.clear();

        assertThat(snapshot.messages()).hasSize(1);
        assertThatThrownBy(() -> snapshot.messages().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ChatMemoryRole.fromValue("system"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChatMemorySnapshot(
                1, APP_ID, 1L, List.of(
                        new ChatMemoryMessage(2L, ChatMemoryRole.USER, "two"),
                        new ChatMemoryMessage(1L, ChatMemoryRole.AI, "one"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VersionedChatMemorySnapshot(2L, snapshot))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void placesHistoryThenProjectSourceThenCurrentMessageExactlyOnce() {
        ChatMemoryPromptBuilder builder = new ChatMemoryPromptBuilder(
                properties(10, 100, 500), JsonMapper.builder().build());
        ChatMemorySnapshot snapshot = builder.fromHistory(
                APP_ID, List.of(history(1L, "user", "历史问题")));
        VueProjectSourceSnapshot source = new VueProjectSourceSnapshot(List.of(
                new VueProjectFile("src/App.vue", "<template>稳定源码</template>")), 27);

        String prompt = builder.buildPrompt(snapshot, source, "唯一当前问题");

        assertThat(prompt.indexOf("history")).isLessThan(prompt.indexOf("projectSource"));
        assertThat(prompt.indexOf("projectSource")).isLessThan(prompt.indexOf("currentMessage"));
        assertThat(prompt).contains("历史问题", "稳定源码", "唯一当前问题");
        assertThat(countOccurrences(prompt, "唯一当前问题")).isOne();
    }

    private static int countOccurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static AppChatMemoryProperties properties(
            int historyLimit,
            int messageMaxChars,
            int totalMaxChars
    ) {
        return new AppChatMemoryProperties(
                historyLimit,
                messageMaxChars,
                totalMaxChars,
                262_144,
                Duration.ofDays(7),
                100,
                Duration.ofMinutes(10),
                "test:memory:",
                "test:invalidation"
        );
    }

    private static ChatHistory history(long id, String role, String content) {
        ChatHistory history = new ChatHistory();
        history.setId(id);
        history.setAppId(APP_ID);
        history.setUserId(1001L);
        history.setMessageType(role);
        history.setMessage(content);
        return history;
    }
}
