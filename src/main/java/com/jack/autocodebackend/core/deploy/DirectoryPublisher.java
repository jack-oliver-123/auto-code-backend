package com.jack.autocodebackend.core.deploy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/**
 * Publishes a staged directory by moving sibling paths on the same filesystem.
 */
public final class DirectoryPublisher {

    private static final Logger log = LoggerFactory.getLogger(DirectoryPublisher.class);

    private final NioFileTreeOperations operations;

    public DirectoryPublisher() {
        this(new NioFileTreeOperations());
    }

    DirectoryPublisher(NioFileTreeOperations operations) {
        this.operations = Objects.requireNonNull(operations);
    }

    public PublishedDirectory publishNew(Path stagingDirectory, Path targetDirectory) throws IOException {
        return publish(stagingDirectory, targetDirectory, false);
    }

    public PublishedDirectory publishReplacement(Path stagingDirectory, Path targetDirectory)
            throws IOException {
        return publish(stagingDirectory, targetDirectory, true);
    }

    private PublishedDirectory publish(
            Path stagingDirectory,
            Path targetDirectory,
            boolean replaceExisting
    ) throws IOException {
        Path staging = normalize(stagingDirectory);
        Path target = normalize(targetDirectory);
        requireSiblingPaths(staging, target);
        if (!operations.isDirectoryNoFollow(staging)) {
            throw new IOException("Staging directory is missing: " + staging);
        }

        if (!replaceExisting) {
            if (operations.existsNoFollow(target)) {
                throw new FileAlreadyExistsException(target.toString());
            }
            // A plain move has specified no-replace semantics if the target appears after the check.
            operations.moveWithoutReplacing(staging, target);
            return new PublishedDirectory(operations, target, null);
        }

        if (!operations.existsNoFollow(target)) {
            operations.move(staging, target);
            return new PublishedDirectory(operations, target, null);
        }

        Path backup = uniqueSibling(target, "backup");
        operations.move(target, backup);
        try {
            operations.move(staging, target);
        } catch (IOException publishException) {
            restoreAfterPublishFailure(target, backup, publishException);
            throw publishException;
        }
        return new PublishedDirectory(operations, target, backup);
    }

    private void restoreAfterPublishFailure(Path target, Path backup, IOException publishException) {
        try {
            if (operations.existsNoFollow(target)) {
                operations.deleteTree(target);
            }
            if (operations.existsNoFollow(backup)) {
                operations.move(backup, target);
            }
        } catch (IOException | RuntimeException rollbackException) {
            publishException.addSuppressed(rollbackException);
        }
    }

    private Path uniqueSibling(Path target, String purpose) throws IOException {
        for (int attempt = 0; attempt < 100; attempt++) {
            Path candidate = target.resolveSibling(
                    "." + target.getFileName() + "." + purpose + "-" + UUID.randomUUID()
            );
            if (!operations.existsNoFollow(candidate)) {
                return candidate;
            }
        }
        throw new IOException("Unable to allocate a temporary sibling for " + target);
    }

    private static Path normalize(Path path) {
        return Objects.requireNonNull(path).toAbsolutePath().normalize();
    }

    private static void requireSiblingPaths(Path staging, Path target) throws IOException {
        if (staging.equals(target)
                || staging.getParent() == null
                || !staging.getParent().equals(target.getParent())) {
            throw new IOException("Staging and target must be different sibling paths");
        }
    }

    public static final class PublishedDirectory implements AutoCloseable {

        private enum State {
            ACTIVE,
            COMMITTED,
            ROLLED_BACK,
            PRESERVED
        }

        private final NioFileTreeOperations operations;
        private final Path target;
        private final Path backup;
        private State state = State.ACTIVE;

        private PublishedDirectory(
                NioFileTreeOperations operations,
                Path target,
                Path backup
        ) {
            this.operations = operations;
            this.target = target;
            this.backup = backup;
        }

        public synchronized void commit() {
            requireActive();
            state = State.COMMITTED;
            if (backup == null) {
                return;
            }
            try {
                operations.deleteTree(backup);
            } catch (IOException | RuntimeException cleanupException) {
                log.warn("Published directory backup cleanup failed: {}", backup, cleanupException);
            }
        }

        public synchronized void rollback() throws IOException {
            requireActive();
            if (backup == null) {
                if (operations.existsNoFollow(target)) {
                    operations.deleteTree(target);
                }
                state = State.ROLLED_BACK;
                return;
            }
            if (!operations.existsNoFollow(backup)) {
                throw new IOException("Published directory backup is missing: " + backup);
            }
            if (operations.existsNoFollow(target)) {
                operations.deleteTree(target);
            }
            operations.move(backup, target);
            state = State.ROLLED_BACK;
        }

        public synchronized void preserve() {
            requireActive();
            state = State.PRESERVED;
        }

        @Override
        public synchronized void close() throws IOException {
            if (state == State.ACTIVE) {
                rollback();
            }
        }

        private void requireActive() {
            if (state != State.ACTIVE) {
                throw new IllegalStateException("Directory publication is already resolved");
            }
        }
    }
}
