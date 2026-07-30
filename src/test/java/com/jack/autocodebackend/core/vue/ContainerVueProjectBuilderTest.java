package com.jack.autocodebackend.core.vue;

import com.jack.autocodebackend.config.AppVueProjectProperties;
import com.jack.autocodebackend.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ContainerVueProjectBuilderTest {

    @TempDir
    Path tempDirectory;

    @Test
    void buildsWithArgumentListAndHardenedContainerBoundary() throws Exception {
        BoundedProcessExecutor executor = mock(BoundedProcessExecutor.class);
        AppVueProjectProperties properties = properties(Duration.ofMillis(50), 1, 256);
        given(executor.execute(any(), eq(properties.getBuildTimeout()), eq(256)))
                .willReturn(new BoundedProcessExecutor.ProcessResult(0, 12, "ok"));
        ContainerVueProjectBuilder builder = new ContainerVueProjectBuilder(
                properties, executor);
        Path project = Files.createDirectory(tempDirectory.resolve("project with spaces"));

        VueProjectBuilder.VueBuildResult result = builder.build(9_223_372_036_854_775L, project);

        assertThat(result.diagnosticBytes()).isEqualTo(12);
        ArgumentCaptor<List<String>> commandCaptor = ArgumentCaptor.forClass(List.class);
        verify(executor).execute(commandCaptor.capture(), eq(properties.getBuildTimeout()), eq(256));
        List<String> command = commandCaptor.getValue();
        assertThat(command).containsSubsequence(
                "docker", "run", "--rm", "--name");
        assertThat(command).contains(
                "--network", "none", "--read-only", "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges", "--user", "10001:10001",
                "--env", "HOME=/tmp", "NODE_ENV=production",
                "--workdir", "/workspace", "builder:test");
        assertThat(command).anyMatch(value -> value.equals(
                "type=bind,source=" + project.toAbsolutePath().normalize()
                        + ",target=/workspace"));
        assertThat(command).doesNotContain("npm", "install", "sh", "bash", "--privileged");
        assertThat(command.stream().filter("--env"::equals)).hasSize(2);
    }

    @Test
    void usesUniqueNamesAndCleansOnlyExactFailedContainer() throws Exception {
        BoundedProcessExecutor executor = mock(BoundedProcessExecutor.class);
        AppVueProjectProperties properties = properties(Duration.ofMillis(50), 1, 128);
        given(executor.execute(any(), any(Duration.class), anyInt()))
                .willReturn(new BoundedProcessExecutor.ProcessResult(1, 128, "secret source"))
                .willReturn(new BoundedProcessExecutor.ProcessResult(0, 0, ""));
        ContainerVueProjectBuilder builder = new ContainerVueProjectBuilder(properties, executor);
        Path project = Files.createDirectory(tempDirectory.resolve("failed"));

        assertThatThrownBy(() -> builder.build(42L, project))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("unsuccessfully")
                .hasMessageNotContaining("secret source");

        ArgumentCaptor<List<String>> commands = ArgumentCaptor.forClass(List.class);
        verify(executor, times(2)).execute(commands.capture(), any(Duration.class), anyInt());
        List<String> run = commands.getAllValues().getFirst();
        String containerName = run.get(run.indexOf("--name") + 1);
        assertThat(commands.getAllValues().getLast())
                .containsExactly("docker", "rm", "--force", containerName);
    }

    @Test
    void mapsRuntimeTimeoutAndInterruptionAndAlwaysReleasesPermit() throws Exception {
        assertFailureThenPermitReuse(
                new IOException("runtime missing"), "runtime is unavailable", false);
        assertFailureThenPermitReuse(
                new BoundedProcessExecutor.ProcessTimeoutException(), "timed out", false);
        assertFailureThenPermitReuse(
                new InterruptedException("cancelled"), "interrupted", true);
    }

    @Test
    void rejectsSaturatedCapacityWithoutStartingAProcess() throws Exception {
        BoundedProcessExecutor executor = mock(BoundedProcessExecutor.class);
        AppVueProjectProperties properties = properties(Duration.ofMillis(1), 1, 128);
        ContainerVueProjectBuilder builder = new ContainerVueProjectBuilder(
                properties, executor, new Semaphore(0, true));
        Path project = Files.createDirectory(tempDirectory.resolve("saturated"));

        assertThatThrownBy(() -> builder.build(9L, project))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("capacity");
        verifyNoInteractions(executor);
    }

    @Test
    void boundedExecutorCapsAndSanitizesOutputAndTimesOut() throws Exception {
        BoundedProcessExecutor executor = new BoundedProcessExecutor();
        String javaExecutable = Path.of(
                System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win")
                        ? "java.exe" : "java").toString();
        String classpath = Path.of("target", "test-classes").toAbsolutePath().toString();
        List<String> outputCommand = List.of(
                javaExecutable, "-cp", classpath,
                ProcessFixture.class.getName(), "output");

        BoundedProcessExecutor.ProcessResult output = executor.execute(
                outputCommand, Duration.ofSeconds(5), 12);

        assertThat(output.exitCode()).isZero();
        assertThat(output.retainedBytes()).isEqualTo(12);
        assertThat(output.sanitizedOutput()).doesNotContain("\u0001");
        assertThatThrownBy(() -> executor.execute(
                List.of(javaExecutable, "-cp", classpath,
                        ProcessFixture.class.getName(), "sleep"),
                Duration.ofMillis(20), 128))
                .isInstanceOf(BoundedProcessExecutor.ProcessTimeoutException.class);
    }

    @Test
    void readinessProbeReportsMissingRuntime() throws Exception {
        BoundedProcessExecutor executor = mock(BoundedProcessExecutor.class);
        AppVueProjectProperties properties = readinessProperties(true);
        given(executor.execute(any(), any(Duration.class), anyInt()))
                .willThrow(new IOException("runtime path"));
        VueBuilderDependencyProbe probe = new VueBuilderDependencyProbe(
                properties, executor, () -> 0L);

        assertThat(probe.checkReadiness()).isFalse();
    }

    @Test
    void readinessProbeReportsDaemonFailureWithoutExposingOutput() throws Exception {
        BoundedProcessExecutor executor = mock(BoundedProcessExecutor.class);
        AppVueProjectProperties properties = readinessProperties(true);
        given(executor.execute(any(), any(Duration.class), anyInt()))
                .willReturn(new BoundedProcessExecutor.ProcessResult(
                        125, 64, "daemon endpoint and environment details"));
        VueBuilderDependencyProbe probe = new VueBuilderDependencyProbe(
                properties, executor, () -> 0L);

        assertThat(probe.checkReadiness()).isFalse();
    }

    @Test
    void readinessProbeCachesMissingImageAndRecoversAfterTtl() throws Exception {
        BoundedProcessExecutor executor = mock(BoundedProcessExecutor.class);
        AppVueProjectProperties properties = readinessProperties(true);
        AtomicLong nanoTime = new AtomicLong();
        given(executor.execute(any(), any(Duration.class), anyInt()))
                .willAnswer(invocation -> {
                    nanoTime.set(Duration.ofSeconds(1).toNanos());
                    return new BoundedProcessExecutor.ProcessResult(
                            1, 64, "local path from missing image error");
                })
                .willReturn(new BoundedProcessExecutor.ProcessResult(0, 64, "sha256:id"));
        VueBuilderDependencyProbe probe = new VueBuilderDependencyProbe(
                properties, executor, nanoTime::get);

        assertThat(probe.checkReadiness()).isFalse();
        assertThat(probe.checkReadiness()).isFalse();
        nanoTime.addAndGet(properties.getReadinessCacheTtl().toNanos() - 1);
        assertThat(probe.checkReadiness()).isFalse();
        verify(executor).execute(any(), any(Duration.class), anyInt());

        nanoTime.incrementAndGet();
        assertThat(probe.checkReadiness()).isTrue();
        verify(executor, times(2)).execute(any(), any(Duration.class), anyInt());
    }

    @Test
    void readinessProbeUsesOnlyBoundedLocalImageInspection() throws Exception {
        BoundedProcessExecutor executor = mock(BoundedProcessExecutor.class);
        AppVueProjectProperties properties = readinessProperties(true);
        given(executor.execute(any(), eq(properties.getReadinessProbeTimeout()),
                eq(properties.getReadinessDiagnosticMaxBytes())))
                .willReturn(new BoundedProcessExecutor.ProcessResult(0, 9, "sha256:id"));
        VueBuilderDependencyProbe probe = new VueBuilderDependencyProbe(
                properties, executor, () -> 0L);

        assertThat(probe.checkReadiness()).isTrue();

        ArgumentCaptor<List<String>> commandCaptor = ArgumentCaptor.forClass(List.class);
        verify(executor).execute(
                commandCaptor.capture(),
                eq(properties.getReadinessProbeTimeout()),
                eq(properties.getReadinessDiagnosticMaxBytes())
        );
        assertThat(commandCaptor.getValue()).containsExactly(
                "docker", "image", "inspect", "--format", "{{.Id}}", "builder:test");
        assertThat(commandCaptor.getValue()).doesNotContain("pull", "run", "build");
    }

    @Test
    void readinessProbeReportsTimeout() throws Exception {
        BoundedProcessExecutor executor = mock(BoundedProcessExecutor.class);
        AppVueProjectProperties properties = readinessProperties(true);
        given(executor.execute(any(), any(Duration.class), anyInt()))
                .willThrow(new BoundedProcessExecutor.ProcessTimeoutException());
        VueBuilderDependencyProbe probe = new VueBuilderDependencyProbe(
                properties, executor, () -> 0L);

        assertThat(probe.checkReadiness()).isFalse();
    }

    @Test
    void readinessProbeCanBeExplicitlyDisabled() {
        BoundedProcessExecutor executor = mock(BoundedProcessExecutor.class);
        VueBuilderDependencyProbe probe = new VueBuilderDependencyProbe(
                readinessProperties(false), executor, () -> 0L);

        assertThat(probe.checkReadiness()).isTrue();
        verifyNoInteractions(executor);
    }

    private void assertFailureThenPermitReuse(
            Exception firstFailure,
            String expectedMessage,
            boolean expectInterrupted
    ) throws Exception {
        BoundedProcessExecutor executor = mock(BoundedProcessExecutor.class);
        AppVueProjectProperties properties = properties(Duration.ofMillis(20), 1, 128);
        given(executor.execute(any(), any(Duration.class), anyInt()))
                .willThrow(firstFailure)
                .willReturn(new BoundedProcessExecutor.ProcessResult(0, 0, ""))
                .willReturn(new BoundedProcessExecutor.ProcessResult(0, 1, "ok"));
        ContainerVueProjectBuilder builder = new ContainerVueProjectBuilder(properties, executor);
        Path project = Files.createDirectory(
                tempDirectory.resolve("failure-" + firstFailure.getClass().getSimpleName()));

        try {
            assertThatThrownBy(() -> builder.build(51L, project))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(expectedMessage);
            assertThat(Thread.currentThread().isInterrupted()).isEqualTo(expectInterrupted);
        } finally {
            Thread.interrupted();
        }

        assertThat(builder.build(51L, project).diagnosticBytes()).isEqualTo(1);
    }

    private static AppVueProjectProperties properties(
            Duration acquireTimeout,
            int concurrency,
            int diagnosticBytes
    ) {
        return new AppVueProjectProperties(
                1, 600_000, 24, 4, 28, 100_000, 500_000, 500_000,
                180, 8, 200, 20_971_520L, Duration.ofSeconds(2), acquireTimeout,
                concurrency, "docker", "builder:test", "1", "64m", 32,
                "16m", diagnosticBytes);
    }

    private static AppVueProjectProperties readinessProperties(boolean required) {
        return new AppVueProjectProperties(
                1, 600_000, 24, 4, 28, 100_000, 500_000, 500_000,
                180, 8, 200, 20_971_520L, Duration.ofSeconds(2),
                Duration.ofMillis(50), 1, "docker", "builder:test", "1", "64m",
                32, "16m", 256, required, Duration.ofMillis(200),
                Duration.ofMillis(500), 64);
    }

    public static final class ProcessFixture {

        private ProcessFixture() {
        }

        public static void main(String[] args) throws Exception {
            if (args.length > 0 && args[0].equals("sleep")) {
                Thread.sleep(10_000);
                return;
            }
            System.out.print("abc\u0001defghijklmnopqrstuvwxyz");
        }
    }
}
