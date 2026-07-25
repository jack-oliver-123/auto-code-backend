package com.jack.autocodebackend.core.deploy;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

class NioFileTreeOperations {

    boolean existsNoFollow(Path path) {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    boolean isDirectoryNoFollow(Path path) {
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
    }

    boolean isRegularFileNoFollow(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }

    void createDirectories(Path directory) throws IOException {
        Files.createDirectories(directory);
    }

    Path createTempDirectory(Path parent, String prefix) throws IOException {
        return Files.createTempDirectory(parent, prefix);
    }

    void validateRegularTree(Path root) throws IOException {
        if (!isDirectoryNoFollow(root)) {
            throw new IOException("Directory does not exist or is not a regular directory: " + root);
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
                    throw unsupportedEntry(directory);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
                    throw unsupportedEntry(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                throw exception;
            }
        });
    }

    void copyRegularTree(Path source, Path destination) throws IOException {
        Path normalizedSource = source.toAbsolutePath().normalize();
        Path normalizedDestination = destination.toAbsolutePath().normalize();
        Files.walkFileTree(normalizedSource, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
                    throw unsupportedEntry(directory);
                }
                Path targetDirectory = resolveCopyTarget(
                        normalizedSource,
                        normalizedDestination,
                        directory
                );
                Files.createDirectories(targetDirectory);
                if (!Files.isDirectory(targetDirectory, LinkOption.NOFOLLOW_LINKS)) {
                    throw unsupportedEntry(targetDirectory);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
                    throw unsupportedEntry(file);
                }
                Path targetFile = resolveCopyTarget(normalizedSource, normalizedDestination, file);
                Files.copy(
                        file,
                        targetFile,
                        LinkOption.NOFOLLOW_LINKS,
                        StandardCopyOption.COPY_ATTRIBUTES
                );
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                throw exception;
            }
        });
    }

    void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    void moveWithoutReplacing(Path source, Path target) throws IOException {
        Files.move(source, target);
    }

    void deleteTree(Path root) throws IOException {
        if (!existsNoFollow(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                throw exception;
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

    private static Path resolveCopyTarget(Path source, Path destination, Path entry) throws IOException {
        Path target = destination.resolve(source.relativize(entry)).normalize();
        if (!target.startsWith(destination)) {
            throw new IOException("Copy target escapes the staging directory: " + target);
        }
        return target;
    }

    private static IOException unsupportedEntry(Path path) {
        return new IOException("Symbolic links and non-regular entries are not allowed: " + path);
    }
}
