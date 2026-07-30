package com.jack.autocodebackend.core.vue;

import java.nio.file.Path;
import java.time.Duration;

public interface VueProjectBuilder {

    VueBuildResult build(long appId, Path projectDirectory);

    record VueBuildResult(Duration duration, int diagnosticBytes) {

        public VueBuildResult {
            if (duration == null || duration.isNegative()) {
                throw new IllegalArgumentException("duration must not be negative");
            }
            if (diagnosticBytes < 0) {
                throw new IllegalArgumentException("diagnosticBytes must not be negative");
            }
        }
    }
}
