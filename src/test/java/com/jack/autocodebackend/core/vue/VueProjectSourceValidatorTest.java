package com.jack.autocodebackend.core.vue;

import com.jack.autocodebackend.ai.model.VueProjectCodeResult;
import com.jack.autocodebackend.ai.model.VueProjectFile;
import com.jack.autocodebackend.config.AppVueProjectProperties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VueProjectSourceValidatorTest {

    @TempDir
    Path tempDirectory;

    private final AppVueProjectProperties properties = AppVueProjectProperties.defaults();
    private final VueProjectSourceValidator validator = new VueProjectSourceValidator(properties);

    @Test
    void validatesCompleteNestedSnapshotAndHashRouter() {
        VueProjectCodeResult result = validResult(new VueProjectFile(
                "src/components/NavBar.vue", "<template><nav>导航</nav></template>\n"));

        ValidatedVueProject validated = validator.validate(result);

        assertThat(validated.files()).hasSize(4);
        assertThat(validated.totalCharacters()).isEqualTo(
                result.files().stream().mapToInt(file -> file.content().length()).sum());
    }

    @Test
    void rejectsMissingEntriesBrowserHistoryAndInvalidUnicode() {
        assertThatThrownBy(() -> validator.validate(new VueProjectCodeResult(List.of(
                file("src/main.js", "import './App.vue'"),
                file("src/App.vue", "<template/>")), null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("required");
        assertThatThrownBy(() -> validator.validate(validResultWithRouter(
                "createWebHistory()")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("HashHistory");
        assertThatThrownBy(() -> validator.validate(validResult(
                file("src/bad.txt", "bad\uD800"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unicode");
    }

    @Test
    void rejectsUnsafeWindowsProtectedAndTemporaryPaths() {
        List<String> invalidPaths = List.of(
                "", " index.html", "index.html", "/src/x.js", "C:/src/x.js",
                "src\\x.js", "src/../x.js", "src/./x.js", "src//x.js",
                "src/.hidden.js", "src/file.tmp", "src/a.staging-1.js",
                "src/node_modules/x.js", "src/x.exe", "src/CON.js", "public/NUL.txt",
                "src/name./x.js", "other/x.js"
        );

        for (String path : invalidPaths) {
            assertThatThrownBy(() -> validator.validatePath(path))
                    .as(path)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> validator.validatePath(
                "src/a/b/c/d/e/f/g/h.js"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("deep");
        assertThatThrownBy(() -> validator.validatePath(
                "src/" + "a".repeat(180) + ".js"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("long");
    }

    @Test
    void rejectsCaseCollisionsAndEveryConfiguredSourceBound() {
        assertThatThrownBy(() -> validator.validate(validResult(
                file("src/app.vue", "<template>collision</template>"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("case");

        List<VueProjectFile> tooMany = new ArrayList<>(validResult().files());
        for (int index = 0; index < 22; index++) {
            tooMany.add(file("src/components/C" + index + ".vue", "<template/>"));
        }
        assertThatThrownBy(() -> validator.validate(new VueProjectCodeResult(tooMany, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("too many");

        assertThatThrownBy(() -> validator.validate(validResult(file(
                "src/large.txt", "x".repeat(properties.getFileMaxChars() + 1)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("too large");

        AppVueProjectProperties smallTotal = properties(
                24, 28, 100, 100, 100, 180, 8, 200, 20_971_520L);
        assertThatThrownBy(() -> new VueProjectSourceValidator(smallTotal)
                .validate(validResult()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("character");
    }

    @Test
    void materializesTrustedScaffoldAndSourceAsContainedUtf8Files() throws Exception {
        VueProjectScaffold scaffold = new VueProjectScaffold();
        VueProjectMaterializer materializer = new VueProjectMaterializer(scaffold);
        ValidatedVueProject project = validator.validate(validResult(
                file("public/data/info.json", "{\"标题\":\"示例\"}\n")));
        Path staging = Files.createDirectory(tempDirectory.resolve("staging"));

        materializer.materialize(project, staging);

        assertThat(Files.readString(staging.resolve("public/data/info.json")))
                .isEqualTo("{\"标题\":\"示例\"}\n");
        assertThat(Files.readString(staging.resolve("vite.config.js")))
                .isEqualTo(scaffold.files().get("vite.config.js"));
        try (var files = Files.walk(staging)) {
            assertThat(files.filter(Files::isRegularFile)).hasSize(8);
        }
    }

    @Test
    void materializationRefusesExistingFilesAndSymbolicLinkParents() throws Exception {
        VueProjectMaterializer materializer = new VueProjectMaterializer(
                new VueProjectScaffold());
        ValidatedVueProject project = validator.validate(validResult());
        Path occupied = Files.createDirectory(tempDirectory.resolve("occupied"));
        Files.writeString(occupied.resolve("index.html"), "do not overwrite");

        assertThatThrownBy(() -> materializer.materialize(project, occupied))
                .isInstanceOf(IOException.class);
        assertThat(Files.readString(occupied.resolve("index.html")))
                .isEqualTo("do not overwrite");

        Path linked = Files.createDirectory(tempDirectory.resolve("linked"));
        Path external = Files.createDirectory(tempDirectory.resolve("external"));
        try {
            Files.createSymbolicLink(linked.resolve("src"), external);
        } catch (IOException | UnsupportedOperationException exception) {
            Assumptions.abort("Symbolic links are unavailable: " + exception.getMessage());
        }
        assertThatThrownBy(() -> materializer.materialize(project, linked))
                .isInstanceOf(IOException.class).hasMessageContaining("unsafe");
        assertThat(external).isEmptyDirectory();
    }

    static VueProjectCodeResult validResult(VueProjectFile... additionalFiles) {
        List<VueProjectFile> files = new ArrayList<>(List.of(
                file("src/main.js", "import { createApp } from 'vue'\n"),
                file("src/App.vue", "<template><main>应用</main></template>\n"),
                file("src/router/index.js", "import { createWebHashHistory } from 'vue-router'\n"
                        + "export default createWebHashHistory()\n")
        ));
        files.addAll(List.of(additionalFiles));
        return new VueProjectCodeResult(files, null);
    }

    static VueProjectCodeResult validResultWithRouter(String router) {
        return new VueProjectCodeResult(List.of(
                file("src/main.js", "import { createApp } from 'vue'\n"),
                file("src/App.vue", "<template/>\n"),
                file("src/router/index.js", router)), null);
    }

    static VueProjectFile file(String path, String content) {
        return new VueProjectFile(path, content);
    }

    private static AppVueProjectProperties properties(
            int modelFiles,
            int combinedFiles,
            int fileChars,
            int sourceChars,
            int contextChars,
            int pathChars,
            int pathDepth,
            int distFiles,
            long distBytes
    ) {
        return new AppVueProjectProperties(
                1, Math.max(sourceChars + 1, 600), modelFiles, 4, combinedFiles,
                fileChars, sourceChars, contextChars, pathChars, pathDepth,
                distFiles, distBytes, Duration.ofSeconds(2), Duration.ofMillis(50),
                1, "docker", "builder:test", "1", "64m", 32, "16m", 1024);
    }
}
