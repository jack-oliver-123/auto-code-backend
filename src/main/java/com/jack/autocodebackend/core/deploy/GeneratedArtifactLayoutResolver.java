package com.jack.autocodebackend.core.deploy;

import com.jack.autocodebackend.ai.model.enums.CodeGenTypeEnum;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public final class GeneratedArtifactLayoutResolver {

    private GeneratedArtifactLayoutResolver() {
    }

    public static GeneratedArtifactLayout resolve(
            Path outputRoot,
            CodeGenTypeEnum type,
            long appId
    ) {
        if (appId <= 0) {
            throw new IllegalArgumentException("appId must be positive");
        }
        Objects.requireNonNull(type, "code generation type must not be null");
        Path root = Objects.requireNonNull(outputRoot)
                .toAbsolutePath()
                .normalize();
        Path projectRoot = root.resolve(type.getValue() + "_" + appId).normalize();
        requireContained(root, projectRoot);
        Path staticRoot = type.getStaticRootDirectory().isEmpty()
                ? projectRoot
                : projectRoot.resolve(type.getStaticRootDirectory()).normalize();
        requireContained(root, staticRoot);
        return new GeneratedArtifactLayout(
                projectRoot,
                staticRoot,
                type.getRequiredStaticFiles()
        );
    }

    private static void requireContained(Path root, Path path) {
        if (path.equals(root) || !path.startsWith(root)) {
            throw new IllegalArgumentException("generated artifact path escapes output root");
        }
    }

    public record GeneratedArtifactLayout(
            Path projectRoot,
            Path staticRoot,
            List<String> requiredStaticFiles
    ) {

        public GeneratedArtifactLayout {
            projectRoot = projectRoot.toAbsolutePath().normalize();
            staticRoot = staticRoot.toAbsolutePath().normalize();
            requiredStaticFiles = List.copyOf(requiredStaticFiles);
        }
    }
}
