package com.jack.autocodebackend.core.vue;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Component
public class BoundedProcessExecutor {

    public ProcessResult execute(
            List<String> command,
            Duration timeout,
            int retainedOutputBytes
    ) throws IOException, InterruptedException, ProcessTimeoutException {
        Objects.requireNonNull(command, "command must not be null");
        if (command.isEmpty() || timeout == null || timeout.isNegative()
                || timeout.isZero() || retainedOutputBytes <= 0) {
            throw new IllegalArgumentException("process execution limits are invalid");
        }
        ProcessBuilder processBuilder = new ProcessBuilder(List.copyOf(command));
        processBuilder.redirectErrorStream(true);
        processBuilder.environment().clear();
        Process process = processBuilder.start();
        OutputCollector collector = new OutputCollector(
                process.getInputStream(), retainedOutputBytes);
        Thread reader = Thread.ofVirtual()
                .name("vue-builder-output-reader")
                .start(collector);
        boolean completed = false;
        try {
            completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                terminate(process);
                throw new ProcessTimeoutException();
            }
            reader.join(Duration.ofSeconds(5));
            return new ProcessResult(
                    process.exitValue(),
                    collector.retainedBytes(),
                    sanitize(collector.text())
            );
        } catch (InterruptedException exception) {
            terminate(process);
            reader.interrupt();
            throw exception;
        } finally {
            if (!completed && process.isAlive()) {
                terminate(process);
            }
        }
    }

    private void terminate(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private String sanitize(String output) {
        StringBuilder sanitized = new StringBuilder(output.length());
        output.codePoints().forEach(codePoint -> {
            if (codePoint == '\n' || codePoint == '\r' || codePoint == '\t'
                    || !Character.isISOControl(codePoint)) {
                sanitized.appendCodePoint(codePoint);
            }
        });
        return sanitized.toString();
    }

    public record ProcessResult(int exitCode, int retainedBytes, String sanitizedOutput) {
    }

    public static final class ProcessTimeoutException extends Exception {

        public ProcessTimeoutException() {
            super("process timed out");
        }
    }

    private static final class OutputCollector implements Runnable {

        private final InputStream input;
        private final int limit;
        private final ByteArrayOutputStream retained;

        private OutputCollector(InputStream input, int limit) {
            this.input = input;
            this.limit = limit;
            this.retained = new ByteArrayOutputStream(Math.min(limit, 8192));
        }

        @Override
        public void run() {
            byte[] buffer = new byte[8192];
            try (input) {
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    synchronized (this) {
                        int remaining = limit - retained.size();
                        if (remaining > 0) {
                            retained.write(buffer, 0, Math.min(remaining, count));
                        }
                    }
                }
            } catch (IOException ignored) {
                // Process termination commonly closes the pipe while the reader is draining it.
            }
        }

        private synchronized int retainedBytes() {
            return retained.size();
        }

        private synchronized String text() {
            return retained.toString(StandardCharsets.UTF_8);
        }
    }
}
