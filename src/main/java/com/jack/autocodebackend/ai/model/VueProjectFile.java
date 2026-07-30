package com.jack.autocodebackend.ai.model;

import dev.langchain4j.model.output.structured.Description;

public record VueProjectFile(
        @Description("Relative POSIX path below src/ or public/") String path,
        @Description("Complete non-empty UTF-8 text file content") String content
) {
}
