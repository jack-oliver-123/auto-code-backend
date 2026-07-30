package com.jack.autocodebackend.core.vue;

import com.jack.autocodebackend.config.AppVueProjectProperties;
import com.jack.autocodebackend.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VueProjectSourceContextLoaderTest {

    private static final long APP_ID = 9_223_372_036_854_775L;

    @TempDir
    Path outputRoot;

    @Test
    void loadsOnlyDeterministicallyOrderedModelSourceForLongId() throws Exception {
        AppVueProjectProperties properties = AppVueProjectProperties.defaults();
        VueProjectScaffold scaffold = new VueProjectScaffold();
        Path project = createCompleteProject(scaffold, APP_ID);
        write(project.resolve("public/data.json"), "{\"value\":1}");
        write(project.resolve("dist/assets/secret.js"), "compiled-secret");
        VueProjectSourceContextLoader loader = loader(properties, scaffold);

        VueProjectSourceSnapshot snapshot = loader.load(APP_ID);

        assertThat(snapshot.files()).extracting("path").containsExactly(
                "public/data.json",
                "src/App.vue",
                "src/main.js",
                "src/router/index.js");
        assertThat(snapshot.files()).extracting("content")
                .doesNotContain("compiled-secret", scaffold.files().get("package.json"));
        assertThat(snapshot.totalCharacters()).isEqualTo(
                snapshot.files().stream().mapToInt(file -> file.content().length()).sum());
    }

    @Test
    void rejectsMissingIncompleteTamperedAndUnexpectedStableTrees() throws Exception {
        AppVueProjectProperties properties = AppVueProjectProperties.defaults();
        VueProjectScaffold scaffold = new VueProjectScaffold();
        VueProjectSourceContextLoader loader = loader(properties, scaffold);

        assertThatThrownBy(() -> loader.load(APP_ID))
                .isInstanceOf(BusinessException.class).hasMessageContaining("unavailable");

        Path project = createCompleteProject(scaffold, APP_ID);
        Files.delete(project.resolve("src/App.vue"));
        assertThatThrownBy(() -> loader.load(APP_ID)).isInstanceOf(BusinessException.class);

        write(project.resolve("src/App.vue"), "<template/>\n");
        write(project.resolve("package.json"), "{\"tampered\":true}");
        assertThatThrownBy(() -> loader.load(APP_ID)).isInstanceOf(BusinessException.class);

        write(project.resolve("package.json"), scaffold.files().get("package.json"));
        write(project.resolve("build.log"), "unexpected");
        assertThatThrownBy(() -> loader.load(APP_ID)).isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsUnsafeSourceEntriesAndContextOverflowBeforeAiUse() throws Exception {
        VueProjectScaffold scaffold = new VueProjectScaffold();
        Path project = createCompleteProject(scaffold, APP_ID);
        write(project.resolve("src/evil.exe"), "binary-like");
        VueProjectSourceContextLoader defaultLoader = loader(
                AppVueProjectProperties.defaults(), scaffold);

        assertThatThrownBy(() -> defaultLoader.load(APP_ID))
                .isInstanceOf(BusinessException.class).hasMessageContaining("unsafe");

        Files.delete(project.resolve("src/evil.exe"));
        AppVueProjectProperties tightContext = properties(300, 300);
        assertThatThrownBy(() -> loader(tightContext, scaffold).load(APP_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("unavailable")
                .hasRootCauseMessage("Stable Vue source exceeds its context limit");
    }

    private VueProjectSourceContextLoader loader(
            AppVueProjectProperties properties,
            VueProjectScaffold scaffold
    ) {
        return new VueProjectSourceContextLoader(
                outputRoot,
                properties,
                new VueProjectSourceValidator(properties),
                scaffold,
                new VueDistValidator(properties));
    }

    private Path createCompleteProject(VueProjectScaffold scaffold, long appId)
            throws IOException {
        Path project = Files.createDirectories(outputRoot.resolve("vue_project_" + appId));
        for (var entry : scaffold.files().entrySet()) {
            write(project.resolve(entry.getKey()), entry.getValue());
        }
        write(project.resolve("src/main.js"), "import { createApp } from 'vue'\n");
        write(project.resolve("src/App.vue"), "<template><main>应用</main></template>\n");
        write(project.resolve("src/router/index.js"),
                "import { createWebHashHistory } from 'vue-router'\n"
                        + "export default createWebHashHistory()\n");
        write(project.resolve("dist/index.html"),
                "<script type=\"module\" src=\"./assets/app.js\"></script>");
        write(project.resolve("dist/assets/app.js"), "compiled");
        return project;
    }

    private void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private AppVueProjectProperties properties(int sourceChars, int contextChars) {
        return new AppVueProjectProperties(
                1, 1_000, 24, 4, 28, 200, sourceChars, contextChars,
                180, 8, 200, 20_971_520L, Duration.ofSeconds(2),
                Duration.ofMillis(50), 1, "docker", "builder:test", "1",
                "64m", 32, "16m", 1024);
    }
}
