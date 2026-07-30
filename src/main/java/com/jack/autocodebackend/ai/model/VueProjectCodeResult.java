package com.jack.autocodebackend.ai.model;

import dev.langchain4j.model.output.structured.Description;

import java.util.List;

@Description("A complete snapshot of model-owned Vue project source files")
public record VueProjectCodeResult(
        @Description("Ordered complete source files below src/ or public/")
        List<VueProjectFile> files,
        @Description("Short generation summary")
        String description
) implements CodeResult {

    public VueProjectCodeResult {
        files = files == null ? null : List.copyOf(files);
    }
}
