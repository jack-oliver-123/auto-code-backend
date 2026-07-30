package com.jack.autocodebackend.core.deploy;

import com.jack.autocodebackend.ai.model.enums.CodeGenTypeEnum;
import com.jack.autocodebackend.config.AppDeploymentProperties;
import com.jack.autocodebackend.config.AppVueProjectProperties;
import com.jack.autocodebackend.constant.AppConstant;
import com.jack.autocodebackend.core.deploy.GeneratedArtifactLayoutResolver.GeneratedArtifactLayout;
import com.jack.autocodebackend.core.vue.VueDistValidator;
import com.jack.autocodebackend.exception.BusinessException;
import com.jack.autocodebackend.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Stages, publishes, and undeploys generated application directory snapshots.
 */
@Component
public class AppDeploymentFileManager {

    private static final Logger log = LoggerFactory.getLogger(AppDeploymentFileManager.class);
    private static final Pattern DEPLOY_KEY_PATTERN = Pattern.compile("[A-Za-z0-9]{6}");

    private final Path outputRoot;
    private final Path deploymentRoot;
    private final NioFileTreeOperations operations;
    private final DirectoryPublisher directoryPublisher;

    private final VueDistValidator vueDistValidator;

    @Autowired
    public AppDeploymentFileManager(
            AppDeploymentProperties properties,
            VueDistValidator vueDistValidator
    ) {
        this(
                Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR),
                Objects.requireNonNull(properties).getRootDir(),
                new NioFileTreeOperations(),
                vueDistValidator
        );
    }

    public AppDeploymentFileManager(AppDeploymentProperties properties) {
        this(properties, new VueDistValidator(AppVueProjectProperties.defaults()));
    }

    AppDeploymentFileManager(
            Path outputRoot,
            Path deploymentRoot,
            NioFileTreeOperations operations
    ) {
        this(
                outputRoot,
                deploymentRoot,
                operations,
                new VueDistValidator(AppVueProjectProperties.defaults())
        );
    }

    AppDeploymentFileManager(
            Path outputRoot,
            Path deploymentRoot,
            NioFileTreeOperations operations,
            VueDistValidator vueDistValidator
    ) {
        this.outputRoot = normalizeRoot(outputRoot, "code output root");
        this.deploymentRoot = normalizeRoot(deploymentRoot, "deployment root");
        this.operations = Objects.requireNonNull(operations);
        this.directoryPublisher = new DirectoryPublisher(operations);
        this.vueDistValidator = Objects.requireNonNull(vueDistValidator);
        requireNonOverlappingRoots(this.outputRoot, this.deploymentRoot);
    }

    public StagedDeployment stage(CodeGenTypeEnum codeGenType, Long appId) {
        GeneratedArtifactLayout layout = resolveLayout(codeGenType, appId);
        Path source = layout.staticRoot();
        try {
            validateSource(layout, codeGenType);
            ensureDeploymentRoot();
            Path staging = operations.createTempDirectory(deploymentRoot, ".deployment-staging-")
                    .toAbsolutePath()
                    .normalize();
            requireContained(deploymentRoot, staging, "staging directory");
            try {
                operations.copyRegularTree(source, staging);
                operations.validateRegularTree(staging);
                validateRequiredFiles(staging, codeGenType);
                return new StagedDeployment(this, staging);
            } catch (IOException | RuntimeException copyException) {
                cleanupAfterFailure(staging, copyException);
                throw copyException;
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException exception) {
            throw operationFailure("Failed to stage generated application files", exception);
        }
    }

    public boolean isTargetAvailableForNewKey(String deployKey) {
        Path target = resolveTarget(deployKey);
        return !operations.existsNoFollow(target);
    }

    public Undeployment prepareUndeployment(String deployKey) {
        Path target = resolveTarget(deployKey);
        if (!operations.existsNoFollow(target)) {
            return new Undeployment(this, target, null);
        }

        Path tombstone;
        try {
            ensureDeploymentRoot();
            tombstone = uniqueHiddenSibling(target, "tombstone");
            operations.move(target, tombstone);
        } catch (NoSuchFileException exception) {
            if (!operations.existsNoFollow(target)) {
                return new Undeployment(this, target, null);
            }
            throw operationFailure("Failed to move deployment out of service", exception);
        } catch (IOException exception) {
            throw operationFailure("Failed to move deployment out of service", exception);
        }
        return new Undeployment(this, target, tombstone);
    }

    private GeneratedArtifactLayout resolveLayout(CodeGenTypeEnum codeGenType, Long appId) {
        if (codeGenType == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "Unsupported code generation type");
        }
        if (appId == null || appId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "App id must be positive");
        }
        try {
            return GeneratedArtifactLayoutResolver.resolve(outputRoot, codeGenType, appId);
        } catch (IllegalArgumentException exception) {
            throw operationFailure("Generated artifact layout is invalid", exception);
        }
    }

    private Path resolveTarget(String deployKey) {
        requireDeployKey(deployKey);
        Path target = deploymentRoot.resolve(deployKey).normalize();
        requireContained(deploymentRoot, target, "deployment target");
        return target;
    }

    private void validateSource(GeneratedArtifactLayout layout, CodeGenTypeEnum codeGenType)
            throws IOException {
        if (codeGenType == CodeGenTypeEnum.VUE_PROJECT) {
            vueDistValidator.validateProjectDist(layout.projectRoot());
        }
        operations.validateRegularTree(layout.staticRoot());
        validateRequiredFiles(layout.staticRoot(), codeGenType);
    }

    private void validateRequiredFiles(Path directory, CodeGenTypeEnum codeGenType) throws IOException {
        for (String fileName : requiredFiles(codeGenType)) {
            Path requiredFile = directory.resolve(fileName).normalize();
            requireContained(directory, requiredFile, "required generated file");
            if (!operations.isRegularFileNoFollow(requiredFile)) {
                throw new IOException("Required generated file is missing: " + requiredFile);
            }
        }
    }

    private List<String> requiredFiles(CodeGenTypeEnum codeGenType) {
        return codeGenType.getRequiredStaticFiles();
    }

    private void ensureDeploymentRoot() throws IOException {
        operations.createDirectories(deploymentRoot);
        if (!operations.isDirectoryNoFollow(deploymentRoot)) {
            throw new IOException("Deployment root is not a regular directory: " + deploymentRoot);
        }
    }

    private Path uniqueHiddenSibling(Path target, String purpose) throws IOException {
        for (int attempt = 0; attempt < 100; attempt++) {
            Path candidate = deploymentRoot.resolve(
                    "." + target.getFileName() + "." + purpose + "-" + UUID.randomUUID()
            ).normalize();
            requireContained(deploymentRoot, candidate, purpose + " directory");
            if (!operations.existsNoFollow(candidate)) {
                return candidate;
            }
        }
        throw new IOException("Unable to allocate a " + purpose + " directory");
    }

    private void cleanupAfterFailure(Path path, Throwable failure) {
        try {
            operations.deleteTree(path);
        } catch (IOException | RuntimeException cleanupException) {
            failure.addSuppressed(cleanupException);
        }
    }

    private void cleanupBestEffort(Path path, String description) {
        if (path == null) {
            return;
        }
        try {
            operations.deleteTree(path);
        } catch (IOException | RuntimeException cleanupException) {
            log.warn("{} cleanup failed: {}", description, path, cleanupException);
        }
    }

    private static Path normalizeRoot(Path root, String description) {
        return Objects.requireNonNull(root, description + " must not be null")
                .toAbsolutePath()
                .normalize();
    }

    private static void requireNonOverlappingRoots(Path outputRoot, Path deploymentRoot) {
        if (outputRoot.startsWith(deploymentRoot) || deploymentRoot.startsWith(outputRoot)) {
            throw new IllegalArgumentException("Code output and deployment roots must not overlap");
        }
    }

    private static void requireDeployKey(String deployKey) {
        if (deployKey == null || !DEPLOY_KEY_PATTERN.matcher(deployKey).matches()) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "Deployment key must contain exactly six alphanumeric characters"
            );
        }
    }

    private static void requireContained(Path root, Path path, String description) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedPath = path.toAbsolutePath().normalize();
        if (normalizedPath.equals(normalizedRoot) || !normalizedPath.startsWith(normalizedRoot)) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    description + " escapes its configured root"
            );
        }
    }

    private static BusinessException operationFailure(String message, Throwable cause) {
        BusinessException exception = new BusinessException(ErrorCode.OPERATION_ERROR, message);
        exception.initCause(cause);
        return exception;
    }

    public static final class StagedDeployment implements AutoCloseable {

        private final AppDeploymentFileManager manager;
        private final Path staging;
        private boolean transferred;
        private boolean closed;

        private StagedDeployment(AppDeploymentFileManager manager, Path staging) {
            this.manager = manager;
            this.staging = staging;
        }

        public PublishedDeployment publishNew(String deployKey) {
            return publish(deployKey, false);
        }

        public PublishedDeployment publishReplacement(String deployKey) {
            return publish(deployKey, true);
        }

        private synchronized PublishedDeployment publish(String deployKey, boolean replaceExisting) {
            requireOpen();
            Path target = manager.resolveTarget(deployKey);
            try {
                DirectoryPublisher.PublishedDirectory published = replaceExisting
                        ? manager.directoryPublisher.publishReplacement(staging, target)
                        : manager.directoryPublisher.publishNew(staging, target);
                transferred = true;
                return new PublishedDeployment(published);
            } catch (IOException exception) {
                throw operationFailure("Failed to publish application files", exception);
            }
        }

        @Override
        public synchronized void close() {
            if (closed || transferred) {
                return;
            }
            closed = true;
            try {
                manager.operations.deleteTree(staging);
            } catch (IOException exception) {
                throw operationFailure("Failed to clean deployment staging directory", exception);
            }
        }

        private void requireOpen() {
            if (closed || transferred) {
                throw new IllegalStateException("Staged deployment is already consumed");
            }
        }
    }

    public static final class PublishedDeployment implements AutoCloseable {

        private final DirectoryPublisher.PublishedDirectory published;
        private boolean resolved;

        private PublishedDeployment(DirectoryPublisher.PublishedDirectory published) {
            this.published = published;
        }

        public synchronized void commit() {
            requireActive();
            published.commit();
            resolved = true;
        }

        public synchronized void rollback() {
            requireActive();
            try {
                published.rollback();
                resolved = true;
            } catch (IOException exception) {
                throw operationFailure("Failed to roll back application publication", exception);
            }
        }

        public synchronized void preserve() {
            requireActive();
            published.preserve();
            resolved = true;
        }

        @Override
        public synchronized void close() {
            if (!resolved) {
                rollback();
            }
        }

        private void requireActive() {
            if (resolved) {
                throw new IllegalStateException("Published deployment is already resolved");
            }
        }
    }

    public static final class Undeployment implements AutoCloseable {

        private final AppDeploymentFileManager manager;
        private final Path target;
        private final Path tombstone;
        private boolean resolved;

        private Undeployment(AppDeploymentFileManager manager, Path target, Path tombstone) {
            this.manager = manager;
            this.target = target;
            this.tombstone = tombstone;
        }

        public synchronized void commit() {
            requireActive();
            resolved = true;
            manager.cleanupBestEffort(tombstone, "Deployment tombstone");
        }

        public synchronized void rollback() {
            requireActive();
            if (tombstone == null) {
                resolved = true;
                return;
            }
            if (!manager.operations.existsNoFollow(tombstone)) {
                throw operationFailure(
                        "Failed to restore deployment because its tombstone is missing",
                        new IOException(tombstone.toString())
                );
            }
            if (manager.operations.existsNoFollow(target)) {
                throw operationFailure(
                        "Failed to restore deployment because its target already exists",
                        new IOException(target.toString())
                );
            }
            try {
                manager.operations.move(tombstone, target);
                resolved = true;
            } catch (IOException exception) {
                throw operationFailure("Failed to restore deployment", exception);
            }
        }

        @Override
        public synchronized void close() {
            if (!resolved) {
                rollback();
            }
        }

        private void requireActive() {
            if (resolved) {
                throw new IllegalStateException("Undeployment is already resolved");
            }
        }
    }
}
