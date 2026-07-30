package com.jack.autocodebackend.core.vue;

import com.jack.autocodebackend.ai.model.VueProjectCodeResult;
import com.jack.autocodebackend.ai.model.VueProjectFile;
import com.jack.autocodebackend.ai.model.enums.CodeGenTypeEnum;
import com.jack.autocodebackend.config.AppVueProjectProperties;
import com.jack.autocodebackend.constant.AppConstant;
import com.jack.autocodebackend.core.deploy.GeneratedArtifactLayoutResolver;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public final class VueProjectSourceContextLoader {

    private static final int CONTEXT_ENTRY_OVERHEAD = 48;

    private final Path outputRoot;
    private final AppVueProjectProperties properties;
    private final VueProjectSourceValidator sourceValidator;
    private final VueProjectScaffold scaffold;
    private final VueDistValidator distValidator;

    @Autowired
    public VueProjectSourceContextLoader(
            AppVueProjectProperties properties,
            VueProjectSourceValidator sourceValidator,
            VueProjectScaffold scaffold,
            VueDistValidator distValidator
    ) {
        this(
                Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR),
                properties,
                sourceValidator,
                scaffold,
                distValidator
        );
    }

    VueProjectSourceContextLoader(
            Path outputRoot,
            AppVueProjectProperties properties,
            VueProjectSourceValidator sourceValidator,
            VueProjectScaffold scaffold,
            VueDistValidator distValidator
    ) {
        this.outputRoot = outputRoot.toAbsolutePath().normalize();
        this.properties = properties;
        this.sourceValidator = sourceValidator;
        this.scaffold = scaffold;
        this.distValidator = distValidator;
    }

    public VueProjectSourceSnapshot load(long appId) {
        if (appId <= 0) {
            throw new IllegalArgumentException("appId must be positive");
        }
        Path projectRoot = GeneratedArtifactLayoutResolver.resolve(
                outputRoot, CodeGenTypeEnum.VUE_PROJECT, appId).projectRoot();
        try {
            return loadValidated(projectRoot);
        } catch (IOException | IllegalArgumentException exception) {
            BusinessException failure = new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "Current Vue project source is unavailable or unsafe"
            );
            failure.initCause(exception);
            throw failure;
        }
    }

    private VueProjectSourceSnapshot loadValidated(Path projectRoot) throws IOException {
        if (!Files.isDirectory(projectRoot, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(projectRoot)) {
            throw new IOException("Stable Vue project directory is missing or unsafe");
        }
        distValidator.validateProjectDist(projectRoot);
        List<VueProjectFile> sourceFiles = new ArrayList<>();
        Set<String> seenScaffoldFiles = new HashSet<>();
        Files.walkFileTree(projectRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(
                    Path directory,
                    BasicFileAttributes attributes
            ) throws IOException {
                requireRegularEntry(projectRoot, directory, attributes, true);
                if (directory.equals(projectRoot)) {
                    return FileVisitResult.CONTINUE;
                }
                String relative = relativePath(projectRoot, directory);
                rejectHiddenOrTemporary(relative);
                String rootSegment = relative.split("/", 2)[0];
                if (rootSegment.equals("dist")) {
                    return relative.equals("dist")
                            ? FileVisitResult.SKIP_SUBTREE
                            : FileVisitResult.CONTINUE;
                }
                if (!rootSegment.equals("src") && !rootSegment.equals("public")) {
                    throw new IOException("Stable Vue project contains an unexpected directory");
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                requireRegularEntry(projectRoot, file, attributes, false);
                String relative = relativePath(projectRoot, file);
                rejectHiddenOrTemporary(relative);
                if (scaffold.files().containsKey(relative)) {
                    String actual = Files.readString(file, StandardCharsets.UTF_8);
                    if (!actual.equals(scaffold.files().get(relative))) {
                        throw new IOException("Stable Vue scaffold has changed");
                    }
                    seenScaffoldFiles.add(relative);
                    return FileVisitResult.CONTINUE;
                }
                if (relative.startsWith("src/") || relative.startsWith("public/")) {
                    sourceFiles.add(new VueProjectFile(
                            relative,
                            Files.readString(file, StandardCharsets.UTF_8)
                    ));
                    return FileVisitResult.CONTINUE;
                }
                throw new IOException("Stable Vue project contains an unexpected file");
            }
        });
        if (!seenScaffoldFiles.equals(scaffold.files().keySet())) {
            throw new IOException("Stable Vue scaffold is incomplete");
        }
        sourceFiles.sort(java.util.Comparator.comparing(VueProjectFile::path));
        ValidatedVueProject validated = sourceValidator.validate(
                new VueProjectCodeResult(sourceFiles, null));
        long contextCharacters = 0;
        for (VueProjectFile file : validated.files()) {
            contextCharacters += (long) file.path().length()
                    + file.content().length()
                    + CONTEXT_ENTRY_OVERHEAD;
            if (contextCharacters > properties.getSourceContextMaxChars()) {
                throw new IOException("Stable Vue source exceeds its context limit");
            }
        }
        return new VueProjectSourceSnapshot(validated.files(), validated.totalCharacters());
    }

    private void requireRegularEntry(
            Path root,
            Path entry,
            BasicFileAttributes attributes,
            boolean directory
    ) throws IOException {
        if (!entry.startsWith(root) || attributes.isSymbolicLink()
                || (directory ? !attributes.isDirectory() : !attributes.isRegularFile())) {
            throw new IOException("Stable Vue project contains a non-regular entry");
        }
    }

    private void rejectHiddenOrTemporary(String relative) throws IOException {
        for (String segment : relative.split("/")) {
            String normalized = segment.toLowerCase(java.util.Locale.ROOT);
            if (normalized.startsWith(".") || normalized.endsWith(".tmp")
                    || normalized.endsWith(".temp") || normalized.contains(".staging-")
                    || normalized.contains(".backup-")) {
                throw new IOException("Stable Vue project contains a hidden or temporary entry");
            }
        }
    }

    private String relativePath(Path root, Path entry) {
        return root.relativize(entry).toString().replace('\\', '/');
    }
}
