package com.jack.autocodebackend.core.vue;

import com.jack.autocodebackend.ai.model.VueProjectFile;

import java.util.List;

public record VueProjectSourceSnapshot(List<VueProjectFile> files, int totalCharacters) {

    public VueProjectSourceSnapshot {
        files = List.copyOf(files);
        if (files.isEmpty() || totalCharacters <= 0) {
            throw new IllegalArgumentException("Vue project source snapshot must not be empty");
        }
    }
}
