package com.jack.autocodebackend.core.vue;

import com.jack.autocodebackend.ai.model.VueProjectFile;

import java.util.List;

public record ValidatedVueProject(List<VueProjectFile> files, int totalCharacters) {

    public ValidatedVueProject {
        files = List.copyOf(files);
        if (files.isEmpty()) {
            throw new IllegalArgumentException("files must not be empty");
        }
        if (totalCharacters <= 0) {
            throw new IllegalArgumentException("totalCharacters must be positive");
        }
    }
}
