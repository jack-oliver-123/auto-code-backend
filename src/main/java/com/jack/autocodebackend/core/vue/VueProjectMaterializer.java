package com.jack.autocodebackend.core.vue;

import com.jack.autocodebackend.ai.model.VueProjectFile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public final class VueProjectMaterializer {

    private final VueProjectScaffold scaffold;

    public VueProjectMaterializer(VueProjectScaffold scaffold) {
        this.scaffold = scaffold;
    }

    public void materialize(ValidatedVueProject project, Path stagingRoot) throws IOException {
        Path root = requireRegularRoot(stagingRoot);
        LinkedHashMap<String, String> completeFiles = new LinkedHashMap<>(scaffold.files());
        for (VueProjectFile file : project.files()) {
            if (completeFiles.putIfAbsent(file.path(), file.content()) != null) {
                throw new IOException("Vue project collides with the trusted scaffold");
            }
        }
        for (Map.Entry<String, String> file : completeFiles.entrySet()) {
            writeContainedFile(root, file.getKey(), file.getValue());
        }
        validateRegularTree(root, completeFiles.size());
    }

    private Path requireRegularRoot(Path stagingRoot) throws IOException {
        Path root = stagingRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(root)) {
            throw new IOException("Vue staging root is not a regular directory");
        }
        return root;
    }

    private void writeContainedFile(Path root, String relativePath, String content)
            throws IOException {
        Path target = root.resolve(relativePath).normalize();
        if (target.equals(root) || !target.startsWith(root)) {
            throw new IOException("Vue project path escapes staging");
        }
        createContainedParents(root, target.getParent());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Vue project target already exists: " + relativePath);
        }
        Files.writeString(
                target,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        );
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(target)) {
            throw new IOException("Vue project target is not a regular file");
        }
    }

    private void createContainedParents(Path root, Path parent) throws IOException {
        Path relative = root.relativize(parent);
        Path current = root;
        for (Path segment : relative) {
            current = current.resolve(segment).normalize();
            if (!current.startsWith(root)) {
                throw new IOException("Vue project parent escapes staging");
            }
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(current)) {
                    throw new IOException("Vue project parent is unsafe");
                }
            } else {
                Files.createDirectory(current);
            }
        }
    }

    private void validateRegularTree(Path root, int expectedFiles) throws IOException {
        int[] fileCount = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(
                    Path directory,
                    BasicFileAttributes attributes
            ) throws IOException {
                if (!directory.startsWith(root)
                        || attributes.isSymbolicLink()
                        || !attributes.isDirectory()) {
                    throw new IOException("Vue staging contains an unsafe directory");
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                if (!file.startsWith(root)
                        || attributes.isSymbolicLink()
                        || !attributes.isRegularFile()) {
                    throw new IOException("Vue staging contains an unsafe file");
                }
                fileCount[0]++;
                return FileVisitResult.CONTINUE;
            }
        });
        if (fileCount[0] != expectedFiles) {
            throw new IOException("Vue staging contains unexpected files");
        }
    }
}
