package com.jack.autocodebackend.core.vue;

import com.jack.autocodebackend.ai.model.VueProjectCodeResult;
import com.jack.autocodebackend.ai.model.VueProjectFile;
import com.jack.autocodebackend.config.AppVueProjectProperties;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public final class VueProjectSourceValidator {

    private static final Set<String> REQUIRED_FILES = Set.of(
            "src/main.js",
            "src/App.vue",
            "src/router/index.js"
    );
    private static final Set<String> PROTECTED_ROOT_FILES = Set.of(
            "index.html",
            "package.json",
            "package-lock.json",
            "npm-shrinkwrap.json",
            "yarn.lock",
            "pnpm-lock.yaml",
            "vite.config.js",
            "vite.config.mjs",
            "vite.config.ts"
    );
    private static final Set<String> RESERVED_SEGMENTS = Set.of(
            "dist", "node_modules", "target", "tmp", "temp", "staging", "backup"
    );
    private static final Pattern WINDOWS_DEVICE_NAME = Pattern.compile(
            "(?i)^(?:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?$"
    );
    private static final Set<String> SOURCE_EXTENSIONS = Set.of(
            "vue", "js", "mjs", "css", "json", "html", "txt", "svg"
    );
    private static final Pattern SAFE_SEGMENT =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Pattern WINDOWS_DRIVE = Pattern.compile("^[A-Za-z]:.*");
    private static final Pattern HASH_ROUTER =
            Pattern.compile("\\bcreateWebHashHistory\\s*\\(");
    private static final Pattern HISTORY_ROUTER =
            Pattern.compile("\\bcreateWebHistory\\s*\\(");

    private final AppVueProjectProperties properties;

    public VueProjectSourceValidator(AppVueProjectProperties properties) {
        this.properties = properties;
    }

    public ValidatedVueProject validate(VueProjectCodeResult result) {
        if (result == null || result.files() == null || result.files().isEmpty()) {
            throw new IllegalArgumentException("Vue project files must not be empty");
        }
        List<VueProjectFile> files = result.files();
        if (files.size() > properties.getModelMaxFiles()) {
            throw new IllegalArgumentException("Vue project contains too many model files");
        }
        if (files.size() + properties.getScaffoldFileCount()
                > properties.getCombinedMaxFiles()) {
            throw new IllegalArgumentException("Vue project contains too many combined files");
        }

        Set<String> paths = new HashSet<>();
        Set<String> exactPaths = new HashSet<>();
        long totalCharacters = 0;
        String routerContent = null;
        for (VueProjectFile file : files) {
            if (file == null) {
                throw new IllegalArgumentException("Vue project file must not be null");
            }
            String path = validatePath(file.path());
            String key = path.toLowerCase(Locale.ROOT);
            if (!paths.add(key)) {
                throw new IllegalArgumentException("Vue project paths collide by case: " + path);
            }
            exactPaths.add(path);
            String content = validateContent(path, file.content());
            totalCharacters += content.length();
            if (totalCharacters > properties.getSourceMaxChars()) {
                throw new IllegalArgumentException("Vue project source exceeds its character limit");
            }
            if (path.equals("src/router/index.js")) {
                routerContent = content;
            }
        }
        if (!exactPaths.containsAll(REQUIRED_FILES)) {
            throw new IllegalArgumentException("Vue project is missing a required source entry");
        }
        validateHashRouter(routerContent);
        return new ValidatedVueProject(files, Math.toIntExact(totalCharacters));
    }

    public String validatePath(String path) {
        if (path == null || path.isBlank() || !path.equals(path.strip())) {
            throw new IllegalArgumentException("Vue project path must not be blank or padded");
        }
        if (path.length() > properties.getPathMaxChars()) {
            throw new IllegalArgumentException("Vue project path is too long");
        }
        if (path.startsWith("/") || path.contains("\\")
                || WINDOWS_DRIVE.matcher(path).matches()) {
            throw new IllegalArgumentException("Vue project path must be relative POSIX syntax");
        }
        if (PROTECTED_ROOT_FILES.contains(path.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Vue project path is backend-owned: " + path);
        }
        String[] segments = path.split("/", -1);
        if (segments.length > properties.getPathMaxDepth()) {
            throw new IllegalArgumentException("Vue project path is too deep");
        }
        if (segments.length < 2
                || !(segments[0].equals("src") || segments[0].equals("public"))) {
            throw new IllegalArgumentException("Vue project path uses an unsupported root");
        }
        for (String segment : segments) {
            String normalized = segment.toLowerCase(Locale.ROOT);
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                    || segment.startsWith(".") || !SAFE_SEGMENT.matcher(segment).matches()
                    || segment.endsWith(".") || WINDOWS_DEVICE_NAME.matcher(segment).matches()
                    || RESERVED_SEGMENTS.contains(normalized)
                    || normalized.endsWith(".tmp") || normalized.endsWith(".temp")
                    || normalized.contains(".staging-") || normalized.contains(".backup-")) {
                throw new IllegalArgumentException("Vue project path contains an unsafe segment");
            }
        }
        String fileName = segments[segments.length - 1];
        int extensionSeparator = fileName.lastIndexOf('.');
        if (extensionSeparator <= 0
                || !SOURCE_EXTENSIONS.contains(
                        fileName.substring(extensionSeparator + 1).toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Vue project file extension is unsupported");
        }
        return path;
    }

    private String validateContent(String path, String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Vue project file is blank: " + path);
        }
        if (content.length() > properties.getFileMaxChars()) {
            throw new IllegalArgumentException("Vue project file is too large: " + path);
        }
        if (content.indexOf('\0') >= 0 || containsUnpairedSurrogate(content)) {
            throw new IllegalArgumentException("Vue project file is not valid Unicode text: " + path);
        }
        return content;
    }

    private void validateHashRouter(String routerContent) {
        if (routerContent == null
                || !HASH_ROUTER.matcher(routerContent).find()
                || HISTORY_ROUTER.matcher(routerContent).find()) {
            throw new IllegalArgumentException("Vue router must use createWebHashHistory()");
        }
    }

    private boolean containsUnpairedSurrogate(String content) {
        for (int index = 0; index < content.length(); index++) {
            char current = content.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= content.length()
                        || !Character.isLowSurrogate(content.charAt(index + 1))) {
                    return true;
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                return true;
            }
        }
        return false;
    }
}
