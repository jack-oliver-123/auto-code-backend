package com.jack.autocodebackend.core.saver;

import com.jack.autocodebackend.ai.model.CodeResult;
import com.jack.autocodebackend.ai.model.VueProjectCodeResult;
import com.jack.autocodebackend.ai.model.VueProjectFile;
import com.jack.autocodebackend.ai.model.enums.CodeGenTypeEnum;
import com.jack.autocodebackend.constant.AppConstant;
import com.jack.autocodebackend.core.deploy.DirectoryPublisher;
import com.jack.autocodebackend.core.vue.ValidatedVueProject;
import com.jack.autocodebackend.core.vue.VueDistValidator;
import com.jack.autocodebackend.core.vue.VueProjectBuilder;
import com.jack.autocodebackend.core.vue.VueProjectMaterializer;
import com.jack.autocodebackend.core.vue.VueProjectScaffold;
import com.jack.autocodebackend.core.vue.VueProjectSourceValidator;
import com.jack.autocodebackend.exception.BusinessException;
import com.jack.autocodebackend.exception.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Component
public final class VueProjectCodeFileSaver implements CodeResultSaver {

    private static final Object PUBLISH_LOCK = new Object();

    private final VueProjectSourceValidator sourceValidator;
    private final VueProjectMaterializer materializer;
    private final VueProjectScaffold scaffold;
    private final VueProjectBuilder builder;
    private final VueDistValidator distValidator;
    private final DirectoryPublisher directoryPublisher;
    private final Path outputRoot;

    @Autowired
    public VueProjectCodeFileSaver(
            VueProjectSourceValidator sourceValidator,
            VueProjectMaterializer materializer,
            VueProjectScaffold scaffold,
            VueProjectBuilder builder,
            VueDistValidator distValidator
    ) {
        this(
                sourceValidator,
                materializer,
                scaffold,
                builder,
                distValidator,
                new DirectoryPublisher(),
                Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR)
        );
    }

    VueProjectCodeFileSaver(
            VueProjectSourceValidator sourceValidator,
            VueProjectMaterializer materializer,
            VueProjectScaffold scaffold,
            VueProjectBuilder builder,
            VueDistValidator distValidator,
            DirectoryPublisher directoryPublisher,
            Path outputRoot
    ) {
        this.sourceValidator = sourceValidator;
        this.materializer = materializer;
        this.scaffold = scaffold;
        this.builder = builder;
        this.distValidator = distValidator;
        this.directoryPublisher = directoryPublisher;
        this.outputRoot = outputRoot.toAbsolutePath().normalize();
    }

    @Override
    public CodeGenTypeEnum codeGenType() {
        return CodeGenTypeEnum.VUE_PROJECT;
    }

    @Override
    public Class<VueProjectCodeResult> resultType() {
        return VueProjectCodeResult.class;
    }

    @Override
    public CodeFilePublication publish(CodeResult result, Long appId) {
        if (!(result instanceof VueProjectCodeResult vueResult)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "Vue project result is required");
        }
        long validAppId = requireAppId(appId);
        ValidatedVueProject project;
        try {
            project = sourceValidator.validate(vueResult);
        } catch (IllegalArgumentException exception) {
            throw operationFailure("Vue project source is invalid", exception);
        }

        Path target = outputRoot.resolve("vue_project_" + validAppId).normalize();
        requireContained(outputRoot, target);
        Path staging = null;
        try {
            Files.createDirectories(outputRoot);
            if (!Files.isDirectory(outputRoot, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(outputRoot)) {
                throw new IOException("Code output root is unsafe");
            }
            staging = Files.createTempDirectory(
                    outputRoot, ".vue_project_" + validAppId + ".staging-")
                    .toAbsolutePath().normalize();
            requireContained(outputRoot, staging);
            materializer.materialize(project, staging);
            builder.build(validAppId, staging);
            removeTransientDependencies(staging);
            distValidator.validateProjectDist(staging);
            validateCompleteProject(staging, project);

            DirectoryPublisher.PublishedDirectory publication;
            synchronized (PUBLISH_LOCK) {
                publication = directoryPublisher.publishReplacement(staging, target);
            }
            return new CodeFilePublication(target.toFile(), publication);
        } catch (BusinessException exception) {
            cleanupStaging(staging, exception);
            throw exception;
        } catch (IOException | RuntimeException exception) {
            BusinessException failure = operationFailure(
                    "Vue project publication failed", exception);
            cleanupStaging(staging, failure);
            throw failure;
        }
    }

    private void validateCompleteProject(Path staging, ValidatedVueProject project)
            throws IOException {
        Map<String, String> expectedFiles = new HashMap<>(scaffold.files());
        for (VueProjectFile file : project.files()) {
            expectedFiles.put(file.path(), file.content());
        }
        Set<String> expectedDirectories = new HashSet<>();
        for (String expectedPath : expectedFiles.keySet()) {
            Path parent = Path.of(expectedPath).getParent();
            while (parent != null) {
                expectedDirectories.add(parent.toString().replace('\\', '/'));
                parent = parent.getParent();
            }
        }

        Files.walkFileTree(staging, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(
                    Path directory,
                    BasicFileAttributes attributes
            ) throws IOException {
                requireRegularEntry(staging, directory, attributes, true);
                if (!directory.equals(staging)) {
                    String relative = relativePath(staging, directory);
                    if (!relative.equals("dist") && !relative.startsWith("dist/")
                            && !expectedDirectories.contains(relative)) {
                        throw new IOException("Vue builder created an unexpected directory");
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                requireRegularEntry(staging, file, attributes, false);
                String relative = relativePath(staging, file);
                if (relative.startsWith("dist/")) {
                    return FileVisitResult.CONTINUE;
                }
                String expected = expectedFiles.get(relative);
                if (expected == null
                        || !Files.readString(file, StandardCharsets.UTF_8).equals(expected)) {
                    throw new IOException("Vue builder changed trusted project source");
                }
                return FileVisitResult.CONTINUE;
            }
        });
        for (String expectedPath : expectedFiles.keySet()) {
            Path expected = staging.resolve(expectedPath).normalize();
            if (!Files.isRegularFile(expected, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(expected)) {
                throw new IOException("Vue project source is incomplete after build");
            }
        }
    }

    private void requireRegularEntry(
            Path root,
            Path entry,
            BasicFileAttributes attributes,
            boolean directory
    ) throws IOException {
        if (!entry.startsWith(root) || attributes.isSymbolicLink()
                || (directory ? !attributes.isDirectory() : !attributes.isRegularFile())) {
            throw new IOException("Vue project contains an unsafe build entry");
        }
    }

    private String relativePath(Path root, Path entry) {
        return root.relativize(entry).toString().replace('\\', '/');
    }

    private void removeTransientDependencies(Path staging) throws IOException {
        Path nodeModules = staging.resolve("node_modules").normalize();
        if (nodeModules.startsWith(staging) && Files.exists(nodeModules, LinkOption.NOFOLLOW_LINKS)) {
            deleteTree(nodeModules);
        }
    }

    private void cleanupStaging(Path staging, RuntimeException failure) {
        if (staging == null || !Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            deleteTree(staging);
        } catch (IOException | RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private void deleteTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception)
                    throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private long requireAppId(Long appId) {
        if (appId == null || appId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "App id must be positive");
        }
        return appId;
    }

    private void requireContained(Path root, Path path) {
        if (path.equals(root) || !path.startsWith(root)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "Vue project path escapes the code output root");
        }
    }

    private BusinessException operationFailure(String message, Throwable cause) {
        BusinessException exception = new BusinessException(ErrorCode.OPERATION_ERROR, message);
        exception.initCause(cause);
        return exception;
    }
}
