package com.jack.autocodebackend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

@ConfigurationProperties("app.vue-project")
public final class AppVueProjectProperties {

    public static final int SUPPORTED_PROTOCOL_VERSION = 1;
    public static final int CANONICAL_SCAFFOLD_FILE_COUNT = 4;
    public static final int ABSOLUTE_MAX_PROJECT_FILES = 29;

    private static final Pattern DOCKER_SIZE_PATTERN =
            Pattern.compile("[1-9]\\d*(?:[kKmMgG])?");
    private static final Duration MAX_READINESS_PROBE_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration MAX_READINESS_CACHE_TTL = Duration.ofMinutes(1);
    private static final int MAX_READINESS_DIAGNOSTIC_BYTES = 4096;

    private final int protocolVersion;
    private final int responseMaxChars;
    private final int modelMaxFiles;
    private final int scaffoldFileCount;
    private final int combinedMaxFiles;
    private final int fileMaxChars;
    private final int sourceMaxChars;
    private final int sourceContextMaxChars;
    private final int pathMaxChars;
    private final int pathMaxDepth;
    private final int distMaxFiles;
    private final long distMaxBytes;
    private final Duration buildTimeout;
    private final Duration buildAcquireTimeout;
    private final int buildConcurrency;
    private final String runtimeExecutable;
    private final String builderImage;
    private final String cpuLimit;
    private final String memoryLimit;
    private final int pidsLimit;
    private final String tmpfsSize;
    private final int diagnosticMaxBytes;
    private final boolean readinessRequired;
    private final Duration readinessProbeTimeout;
    private final Duration readinessCacheTtl;
    private final int readinessDiagnosticMaxBytes;

    @ConstructorBinding
    public AppVueProjectProperties(
            @DefaultValue("1") int protocolVersion,
            @DefaultValue("600000") int responseMaxChars,
            @DefaultValue("24") int modelMaxFiles,
            @DefaultValue("4") int scaffoldFileCount,
            @DefaultValue("28") int combinedMaxFiles,
            @DefaultValue("100000") int fileMaxChars,
            @DefaultValue("500000") int sourceMaxChars,
            @DefaultValue("500000") int sourceContextMaxChars,
            @DefaultValue("180") int pathMaxChars,
            @DefaultValue("8") int pathMaxDepth,
            @DefaultValue("200") int distMaxFiles,
            @DefaultValue("20971520") long distMaxBytes,
            @DefaultValue("120s") Duration buildTimeout,
            @DefaultValue("5s") Duration buildAcquireTimeout,
            @DefaultValue("2") int buildConcurrency,
            @DefaultValue("docker") String runtimeExecutable,
            @DefaultValue("auto-code-vue-builder:1.0.0") String builderImage,
            @DefaultValue("1.0") String cpuLimit,
            @DefaultValue("512m") String memoryLimit,
            @DefaultValue("128") int pidsLimit,
            @DefaultValue("64m") String tmpfsSize,
            @DefaultValue("16384") int diagnosticMaxBytes,
            @DefaultValue("true") boolean readinessRequired,
            @DefaultValue("2s") Duration readinessProbeTimeout,
            @DefaultValue("5s") Duration readinessCacheTtl,
            @DefaultValue("1024") int readinessDiagnosticMaxBytes
    ) {
        if (protocolVersion != SUPPORTED_PROTOCOL_VERSION) {
            throw new IllegalArgumentException("protocolVersion must be 1");
        }
        this.protocolVersion = protocolVersion;
        this.responseMaxChars = requirePositive(responseMaxChars, "responseMaxChars");
        this.modelMaxFiles = requirePositive(modelMaxFiles, "modelMaxFiles");
        this.scaffoldFileCount = requirePositive(scaffoldFileCount, "scaffoldFileCount");
        this.combinedMaxFiles = requirePositive(combinedMaxFiles, "combinedMaxFiles");
        this.fileMaxChars = requirePositive(fileMaxChars, "fileMaxChars");
        this.sourceMaxChars = requirePositive(sourceMaxChars, "sourceMaxChars");
        this.sourceContextMaxChars = requirePositive(
                sourceContextMaxChars, "sourceContextMaxChars");
        this.pathMaxChars = requirePositive(pathMaxChars, "pathMaxChars");
        this.pathMaxDepth = requirePositive(pathMaxDepth, "pathMaxDepth");
        this.distMaxFiles = requirePositive(distMaxFiles, "distMaxFiles");
        this.distMaxBytes = requirePositive(distMaxBytes, "distMaxBytes");
        this.buildTimeout = requirePositive(buildTimeout, "buildTimeout");
        this.buildAcquireTimeout = requirePositive(
                buildAcquireTimeout, "buildAcquireTimeout");
        this.buildConcurrency = requirePositive(buildConcurrency, "buildConcurrency");
        this.runtimeExecutable = requireNonBlank(runtimeExecutable, "runtimeExecutable");
        this.builderImage = requireNonBlank(builderImage, "builderImage");
        this.cpuLimit = requirePositiveDecimal(cpuLimit, "cpuLimit");
        this.memoryLimit = requireDockerSize(memoryLimit, "memoryLimit");
        this.pidsLimit = requirePositive(pidsLimit, "pidsLimit");
        this.tmpfsSize = requireDockerSize(tmpfsSize, "tmpfsSize");
        this.diagnosticMaxBytes = requirePositive(
                diagnosticMaxBytes, "diagnosticMaxBytes");
        this.readinessRequired = readinessRequired;
        this.readinessProbeTimeout = requireAtMost(
                readinessProbeTimeout,
                MAX_READINESS_PROBE_TIMEOUT,
                "readinessProbeTimeout"
        );
        this.readinessCacheTtl = requireAtMost(
                readinessCacheTtl,
                MAX_READINESS_CACHE_TTL,
                "readinessCacheTtl"
        );
        this.readinessDiagnosticMaxBytes = requireAtMost(
                requirePositive(readinessDiagnosticMaxBytes,
                        "readinessDiagnosticMaxBytes"),
                MAX_READINESS_DIAGNOSTIC_BYTES,
                "readinessDiagnosticMaxBytes"
        );
        validateCoherentLimits();
    }

    public AppVueProjectProperties(
            int protocolVersion,
            int responseMaxChars,
            int modelMaxFiles,
            int scaffoldFileCount,
            int combinedMaxFiles,
            int fileMaxChars,
            int sourceMaxChars,
            int sourceContextMaxChars,
            int pathMaxChars,
            int pathMaxDepth,
            int distMaxFiles,
            long distMaxBytes,
            Duration buildTimeout,
            Duration buildAcquireTimeout,
            int buildConcurrency,
            String runtimeExecutable,
            String builderImage,
            String cpuLimit,
            String memoryLimit,
            int pidsLimit,
            String tmpfsSize,
            int diagnosticMaxBytes
    ) {
        this(
                protocolVersion, responseMaxChars, modelMaxFiles, scaffoldFileCount,
                combinedMaxFiles, fileMaxChars, sourceMaxChars, sourceContextMaxChars,
                pathMaxChars, pathMaxDepth, distMaxFiles, distMaxBytes, buildTimeout,
                buildAcquireTimeout, buildConcurrency, runtimeExecutable, builderImage,
                cpuLimit, memoryLimit, pidsLimit, tmpfsSize, diagnosticMaxBytes,
                true, Duration.ofSeconds(2), Duration.ofSeconds(5), 1024
        );
    }

    public static AppVueProjectProperties defaults() {
        return new AppVueProjectProperties(
                1, 600000, 24, 4, 28, 100000, 500000, 500000,
                180, 8, 200, 20971520L, Duration.ofSeconds(120),
                Duration.ofSeconds(5), 2, "docker", "auto-code-vue-builder:1.0.0",
                "1.0", "512m", 128, "64m", 16384
        );
    }

    private void validateCoherentLimits() {
        if (scaffoldFileCount != CANONICAL_SCAFFOLD_FILE_COUNT) {
            throw new IllegalArgumentException("scaffoldFileCount must be 4");
        }
        if (combinedMaxFiles > ABSOLUTE_MAX_PROJECT_FILES) {
            throw new IllegalArgumentException("combinedMaxFiles must be fewer than 30");
        }
        if (modelMaxFiles < 3 || modelMaxFiles + scaffoldFileCount > combinedMaxFiles) {
            throw new IllegalArgumentException(
                    "modelMaxFiles and scaffoldFileCount exceed combinedMaxFiles");
        }
        if (sourceMaxChars < fileMaxChars) {
            throw new IllegalArgumentException(
                    "sourceMaxChars must be at least fileMaxChars");
        }
        if (sourceContextMaxChars < sourceMaxChars) {
            throw new IllegalArgumentException(
                    "sourceContextMaxChars must be at least sourceMaxChars");
        }
        if (responseMaxChars <= sourceMaxChars) {
            throw new IllegalArgumentException(
                    "responseMaxChars must exceed sourceMaxChars");
        }
        if (pathMaxDepth < 3) {
            throw new IllegalArgumentException("pathMaxDepth must be at least 3");
        }
    }

    public int getProtocolVersion() {
        return protocolVersion;
    }

    public int getResponseMaxChars() {
        return responseMaxChars;
    }

    public int getModelMaxFiles() {
        return modelMaxFiles;
    }

    public int getScaffoldFileCount() {
        return scaffoldFileCount;
    }

    public int getCombinedMaxFiles() {
        return combinedMaxFiles;
    }

    public int getFileMaxChars() {
        return fileMaxChars;
    }

    public int getSourceMaxChars() {
        return sourceMaxChars;
    }

    public int getSourceContextMaxChars() {
        return sourceContextMaxChars;
    }

    public int getPathMaxChars() {
        return pathMaxChars;
    }

    public int getPathMaxDepth() {
        return pathMaxDepth;
    }

    public int getDistMaxFiles() {
        return distMaxFiles;
    }

    public long getDistMaxBytes() {
        return distMaxBytes;
    }

    public Duration getBuildTimeout() {
        return buildTimeout;
    }

    public Duration getBuildAcquireTimeout() {
        return buildAcquireTimeout;
    }

    public int getBuildConcurrency() {
        return buildConcurrency;
    }

    public String getRuntimeExecutable() {
        return runtimeExecutable;
    }

    public String getBuilderImage() {
        return builderImage;
    }

    public String getCpuLimit() {
        return cpuLimit;
    }

    public String getMemoryLimit() {
        return memoryLimit;
    }

    public int getPidsLimit() {
        return pidsLimit;
    }

    public String getTmpfsSize() {
        return tmpfsSize;
    }

    public int getDiagnosticMaxBytes() {
        return diagnosticMaxBytes;
    }

    public boolean isReadinessRequired() {
        return readinessRequired;
    }

    public Duration getReadinessProbeTimeout() {
        return readinessProbeTimeout;
    }

    public Duration getReadinessCacheTtl() {
        return readinessCacheTtl;
    }

    public int getReadinessDiagnosticMaxBytes() {
        return readinessDiagnosticMaxBytes;
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static long requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative() || value.toMillis() <= 0) {
            throw new IllegalArgumentException(name + " must be at least one millisecond");
        }
        return value;
    }

    private static Duration requireAtMost(Duration value, Duration maximum, String name) {
        Duration positive = requirePositive(value, name);
        if (positive.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(name + " exceeds the maximum");
        }
        return positive;
    }

    private static int requireAtMost(int value, int maximum, String name) {
        if (value > maximum) {
            throw new IllegalArgumentException(name + " exceeds the maximum");
        }
        return value;
    }

    private static String requireNonBlank(String value, String name) {
        String normalized = Objects.requireNonNull(value, name + " must not be null").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String requirePositiveDecimal(String value, String name) {
        String normalized = requireNonBlank(value, name);
        try {
            if (new BigDecimal(normalized).compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException(name + " must be positive");
            }
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be a positive decimal", exception);
        }
        return normalized;
    }

    private static String requireDockerSize(String value, String name) {
        String normalized = requireNonBlank(value, name);
        if (!DOCKER_SIZE_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " must be a positive Docker size");
        }
        return normalized;
    }
}
