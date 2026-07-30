package com.jack.autocodebackend.core.vue;

import com.jack.autocodebackend.config.AppVueProjectProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Set;

@Component
public final class VueDistValidator {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "html", "css", "js", "mjs", "json", "txt", "xml", "svg", "png",
            "jpg", "jpeg", "gif", "webp", "avif", "ico", "woff", "woff2",
            "ttf", "otf", "eot", "wasm", "pdf", "mp3", "mp4", "webm", "map"
    );

    private final AppVueProjectProperties properties;

    public VueDistValidator(AppVueProjectProperties properties) {
        this.properties = properties;
    }

    public DistSummary validateProjectDist(Path projectRoot) throws IOException {
        Path root = projectRoot.toAbsolutePath().normalize();
        Path dist = root.resolve("dist").normalize();
        if (!dist.startsWith(root)
                || !Files.isDirectory(dist, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(dist)) {
            throw new IOException("Vue dist directory is missing or unsafe");
        }
        Path index = dist.resolve("index.html").normalize();
        if (!index.startsWith(dist)
                || !Files.isRegularFile(index, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(index)) {
            throw new IOException("Vue dist/index.html is missing or unsafe");
        }

        long[] totalBytes = {0};
        int[] fileCount = {0};
        Files.walkFileTree(dist, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(
                    Path directory,
                    BasicFileAttributes attributes
            ) throws IOException {
                requireSafeEntry(dist, directory, attributes, true);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                requireSafeEntry(dist, file, attributes, false);
                requireAllowedExtension(file);
                fileCount[0]++;
                totalBytes[0] = Math.addExact(totalBytes[0], attributes.size());
                if (fileCount[0] > properties.getDistMaxFiles()
                        || totalBytes[0] > properties.getDistMaxBytes()) {
                    throw new IOException("Vue dist exceeds its configured limits");
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return new DistSummary(dist, fileCount[0], totalBytes[0]);
    }

    private void requireSafeEntry(
            Path root,
            Path entry,
            BasicFileAttributes attributes,
            boolean directory
    ) throws IOException {
        if (!entry.startsWith(root)
                || attributes.isSymbolicLink()
                || (directory ? !attributes.isDirectory() : !attributes.isRegularFile())) {
            throw new IOException("Vue dist contains a non-regular entry");
        }
        Path relative = root.relativize(entry);
        for (Path segment : relative) {
            String value = segment.toString().toLowerCase(Locale.ROOT);
            if (value.startsWith(".") || value.endsWith(".tmp")
                    || value.endsWith(".temp") || value.contains(".staging-")
                    || value.contains(".backup-")) {
                throw new IOException("Vue dist contains a hidden or temporary entry");
            }
        }
    }

    private void requireAllowedExtension(Path file) throws IOException {
        String name = file.getFileName().toString();
        int separator = name.lastIndexOf('.');
        if (separator <= 0
                || !ALLOWED_EXTENSIONS.contains(
                        name.substring(separator + 1).toLowerCase(Locale.ROOT))) {
            throw new IOException("Vue dist contains an unsupported file");
        }
    }

    public record DistSummary(Path directory, int fileCount, long totalBytes) {
    }
}
