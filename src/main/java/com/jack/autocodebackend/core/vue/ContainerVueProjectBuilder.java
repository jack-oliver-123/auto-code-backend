package com.jack.autocodebackend.core.vue;

import com.jack.autocodebackend.config.AppVueProjectProperties;
import com.jack.autocodebackend.exception.BusinessException;
import com.jack.autocodebackend.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Component
public final class ContainerVueProjectBuilder implements VueProjectBuilder {

    private static final Logger log = LoggerFactory.getLogger(ContainerVueProjectBuilder.class);
    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(10);

    private final AppVueProjectProperties properties;
    private final BoundedProcessExecutor processExecutor;
    private final Semaphore buildPermits;

    @Autowired
    public ContainerVueProjectBuilder(
            AppVueProjectProperties properties,
            BoundedProcessExecutor processExecutor
    ) {
        this(properties, processExecutor, new Semaphore(properties.getBuildConcurrency(), true));
    }

    ContainerVueProjectBuilder(
            AppVueProjectProperties properties,
            BoundedProcessExecutor processExecutor,
            Semaphore buildPermits
    ) {
        this.properties = properties;
        this.processExecutor = processExecutor;
        this.buildPermits = buildPermits;
    }

    @Override
    public VueBuildResult build(long appId, Path projectDirectory) {
        if (appId <= 0) {
            throw new IllegalArgumentException("appId must be positive");
        }
        Path project = requireProjectDirectory(projectDirectory);
        acquirePermit(appId);
        String containerName = buildContainerName(appId);
        Instant startedAt = Instant.now();
        try {
            BoundedProcessExecutor.ProcessResult result = processExecutor.execute(
                    buildRunCommand(containerName, project),
                    properties.getBuildTimeout(),
                    properties.getDiagnosticMaxBytes()
            );
            if (result.exitCode() != 0) {
                log.warn("Vue project build failed for app {}: nonzero exit ({} diagnostic bytes retained)",
                        appId, result.retainedBytes());
                cleanupContainer(containerName);
                throw buildFailure("Vue project build exited unsuccessfully", null);
            }
            Duration duration = Duration.between(startedAt, Instant.now());
            log.info("Vue project build succeeded for app {} in {} ms",
                    appId, duration.toMillis());
            return new VueBuildResult(duration, result.retainedBytes());
        } catch (BoundedProcessExecutor.ProcessTimeoutException exception) {
            log.warn("Vue project build failed for app {}: timeout", appId);
            cleanupContainer(containerName);
            throw buildFailure("Vue project build timed out", exception);
        } catch (InterruptedException exception) {
            log.warn("Vue project build failed for app {}: interruption", appId);
            cleanupContainer(containerName);
            Thread.currentThread().interrupt();
            throw buildFailure("Vue project build was interrupted", exception);
        } catch (IOException exception) {
            log.warn("Vue project build failed for app {}: runtime unavailable", appId);
            cleanupContainer(containerName);
            throw buildFailure("Vue project builder runtime is unavailable", exception);
        } finally {
            buildPermits.release();
        }
    }

    List<String> buildRunCommand(String containerName, Path projectDirectory) {
        List<String> command = new ArrayList<>();
        command.add(properties.getRuntimeExecutable());
        command.addAll(List.of(
                "run", "--rm",
                "--name", containerName,
                "--network", "none",
                "--read-only",
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges",
                "--pids-limit", Integer.toString(properties.getPidsLimit()),
                "--cpus", properties.getCpuLimit(),
                "--memory", properties.getMemoryLimit(),
                "--tmpfs", "/tmp:rw,noexec,nosuid,size=" + properties.getTmpfsSize(),
                "--user", "10001:10001",
                "--env", "HOME=/tmp",
                "--env", "NODE_ENV=production",
                "--mount", "type=bind,source=" + projectDirectory + ",target=/workspace",
                "--workdir", "/workspace",
                properties.getBuilderImage()
        ));
        return List.copyOf(command);
    }

    private Path requireProjectDirectory(Path projectDirectory) {
        Path project = projectDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(project, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(project)) {
            throw new IllegalArgumentException("projectDirectory must be a regular directory");
        }
        return project;
    }

    private void acquirePermit(long appId) {
        try {
            boolean acquired = buildPermits.tryAcquire(
                    properties.getBuildAcquireTimeout().toMillis(),
                    TimeUnit.MILLISECONDS
            );
            if (!acquired) {
                log.warn("Vue project build rejected for app {}: capacity exhausted", appId);
                throw buildFailure("Vue project build capacity is exhausted", null);
            }
        } catch (InterruptedException exception) {
            log.warn("Vue project build rejected for app {}: interrupted while waiting", appId);
            Thread.currentThread().interrupt();
            throw buildFailure("Vue project build was interrupted", exception);
        }
    }

    private String buildContainerName(long appId) {
        return ("auto-code-vue-" + appId + "-" + UUID.randomUUID())
                .toLowerCase(Locale.ROOT);
    }

    private void cleanupContainer(String containerName) {
        try {
            processExecutor.execute(
                    List.of(properties.getRuntimeExecutable(), "rm", "--force", containerName),
                    CLEANUP_TIMEOUT,
                    1024
            );
        } catch (IOException | InterruptedException
                 | BoundedProcessExecutor.ProcessTimeoutException cleanupFailure) {
            if (cleanupFailure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Vue builder container cleanup failed for {}", containerName);
        }
    }

    private BusinessException buildFailure(String message, Throwable cause) {
        BusinessException exception = new BusinessException(ErrorCode.OPERATION_ERROR, message);
        if (cause != null) {
            exception.initCause(cause);
        }
        return exception;
    }
}
