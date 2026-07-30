package com.jack.autocodebackend.core.saver;

import com.jack.autocodebackend.ai.model.HtmlCodeResult;
import com.jack.autocodebackend.ai.model.VueProjectCodeResult;
import com.jack.autocodebackend.ai.model.VueProjectFile;
import com.jack.autocodebackend.ai.model.enums.CodeGenTypeEnum;
import com.jack.autocodebackend.config.AppVueProjectProperties;
import com.jack.autocodebackend.core.deploy.DirectoryPublisher;
import com.jack.autocodebackend.core.vue.ValidatedVueProject;
import com.jack.autocodebackend.core.vue.VueDistValidator;
import com.jack.autocodebackend.core.vue.VueProjectBuilder;
import com.jack.autocodebackend.core.vue.VueProjectMaterializer;
import com.jack.autocodebackend.core.vue.VueProjectScaffold;
import com.jack.autocodebackend.core.vue.VueProjectSourceValidator;
import com.jack.autocodebackend.exception.BusinessException;
import com.jack.autocodebackend.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VueProjectCodeFileSaverTest {

    private static final long LONG_APP_ID = 9_223_372_036_854_775L;

    @TempDir
    Path tempDirectory;

    @Test
    void firstPublicationKeepsCompleteSourceAndDistWithoutDependencies() throws Exception {
        FixtureBuilder builder = new FixtureBuilder();
        VueProjectCodeFileSaver saver = saver(builder);

        try (CodeFilePublication publication = saver.publish(
                project("first", "src/components/Old.vue"), LONG_APP_ID)) {
            Path stable = publication.directory().toPath();
            assertThat(stable.getFileName().toString())
                    .isEqualTo("vue_project_" + LONG_APP_ID);
            assertThat(stable.resolve("src/components/Old.vue")).hasContent("first");
            assertThat(stable.resolve("dist/index.html")).exists();
            assertThat(stable.resolve("dist/assets/app.js")).hasContent("first");
            assertThat(stable.resolve("node_modules")).doesNotExist();
            publication.commit();
        }

        assertOnlyStableDirectory(LONG_APP_ID);
    }

    @Test
    void repeatPublicationCanDeleteSourceAndRollbackOrCommitAtomically() throws Exception {
        FixtureBuilder builder = new FixtureBuilder();
        VueProjectCodeFileSaver saver = saver(builder);
        publishAndCommit(saver, project("old", "src/components/Old.vue"), LONG_APP_ID);
        Path stable = stable(LONG_APP_ID);

        try (CodeFilePublication replacement = saver.publish(project("new", null), LONG_APP_ID)) {
            assertThat(stable.resolve("src/components/Old.vue")).doesNotExist();
            assertThat(stable.resolve("dist/assets/app.js")).hasContent("new");
            replacement.rollback();
        }
        assertThat(stable.resolve("src/components/Old.vue")).hasContent("old");
        assertThat(stable.resolve("dist/assets/app.js")).hasContent("old");

        publishAndCommit(saver, project("new", null), LONG_APP_ID);
        assertThat(stable.resolve("src/components/Old.vue")).doesNotExist();
        assertThat(stable.resolve("dist/assets/app.js")).hasContent("new");
        assertOnlyStableDirectory(LONG_APP_ID);
    }

    @Test
    void buildAndOutputFailuresPreservePreviousVersionAndCleanPartialTrees() throws Exception {
        FixtureBuilder builder = new FixtureBuilder();
        VueProjectCodeFileSaver saver = saver(builder);
        publishAndCommit(saver, project("stable", null), LONG_APP_ID);

        for (FixtureMode mode : List.of(
                FixtureMode.FAIL,
                FixtureMode.MISSING_INDEX,
                FixtureMode.UNSUPPORTED_DIST_FILE,
                FixtureMode.MUTATE_SOURCE,
                FixtureMode.UNEXPECTED_ROOT_FILE)) {
            builder.mode = mode;
            assertThatThrownBy(() -> saver.publish(project("replacement", null), LONG_APP_ID))
                    .as(mode.name())
                    .isInstanceOf(BusinessException.class);
            assertThat(stable(LONG_APP_ID).resolve("dist/assets/app.js"))
                    .hasContent("stable");
            assertOnlyStableDirectory(LONG_APP_ID);
        }
    }

    @Test
    void invalidSourceFailsBeforeWritingOrCallingBuilder() throws Exception {
        FixtureBuilder builder = new FixtureBuilder();
        VueProjectCodeFileSaver saver = saver(builder);
        VueProjectCodeResult unsafe = new VueProjectCodeResult(List.of(
                new VueProjectFile("../package.json", "{}")), null);

        assertThatThrownBy(() -> saver.publish(unsafe, LONG_APP_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("source is invalid");
        assertThat(builder.invocations).isZero();
        assertThat(tempDirectory).isEmptyDirectory();
        assertThatThrownBy(() -> saver.publish(project("x", null), 0L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void registryRejectsDuplicatesMissingSaversAndMismatchedResults() {
        VueProjectCodeFileSaver vueSaver = saver(new FixtureBuilder());
        assertThatThrownBy(() -> new CodeFileSaverRegistry(List.of(vueSaver, vueSaver)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Duplicate");

        CodeFileSaverRegistry registry = new CodeFileSaverRegistry(List.of(vueSaver));
        assertThatThrownBy(() -> registry.publish(
                new HtmlCodeResult(), CodeGenTypeEnum.VUE_PROJECT, LONG_APP_ID))
                .isInstanceOf(BusinessException.class).hasMessageContaining("does not match");
        assertThatThrownBy(() -> registry.publish(
                project("x", null), CodeGenTypeEnum.HTML, LONG_APP_ID))
                .isInstanceOf(BusinessException.class).hasMessageContaining("No code saver");
    }

    private VueProjectCodeFileSaver saver(FixtureBuilder builder) {
        AppVueProjectProperties properties = AppVueProjectProperties.defaults();
        VueProjectScaffold scaffold = new VueProjectScaffold();
        return new VueProjectCodeFileSaver(
                new VueProjectSourceValidator(properties),
                new VueProjectMaterializer(scaffold),
                scaffold,
                builder,
                new VueDistValidator(properties),
                new DirectoryPublisher(),
                tempDirectory
        );
    }

    private void publishAndCommit(
            VueProjectCodeFileSaver saver,
            VueProjectCodeResult project,
            long appId
    ) throws Exception {
        try (CodeFilePublication publication = saver.publish(project, appId)) {
            publication.commit();
        }
    }

    private VueProjectCodeResult project(String version, String optionalPath) {
        List<VueProjectFile> files = new ArrayList<>(List.of(
                new VueProjectFile("src/main.js", "import { createApp } from 'vue'\n"),
                new VueProjectFile("src/App.vue",
                        "<template><main>" + version + "</main></template>\n"),
                new VueProjectFile("src/router/index.js",
                        "import { createWebHashHistory } from 'vue-router'\n"
                                + "export default createWebHashHistory()\n")
        ));
        if (optionalPath != null) {
            files.add(new VueProjectFile(optionalPath, version));
        }
        return new VueProjectCodeResult(files, version);
    }

    private Path stable(long appId) {
        return tempDirectory.resolve("vue_project_" + appId);
    }

    private void assertOnlyStableDirectory(long appId) throws IOException {
        try (var paths = Files.list(tempDirectory)) {
            assertThat(paths.map(path -> path.getFileName().toString()))
                    .containsExactly("vue_project_" + appId);
        }
    }

    private enum FixtureMode {
        SUCCESS,
        FAIL,
        MISSING_INDEX,
        UNSUPPORTED_DIST_FILE,
        MUTATE_SOURCE,
        UNEXPECTED_ROOT_FILE
    }

    private static final class FixtureBuilder implements VueProjectBuilder {

        private FixtureMode mode = FixtureMode.SUCCESS;
        private int invocations;

        @Override
        public VueBuildResult build(long appId, Path projectDirectory) {
            invocations++;
            try {
                Files.createDirectories(projectDirectory.resolve("node_modules/vue"));
                Files.writeString(projectDirectory.resolve("node_modules/vue/index.js"), "transient");
                Path dist = Files.createDirectories(projectDirectory.resolve("dist/assets"));
                String appSource = Files.readString(projectDirectory.resolve("src/App.vue"));
                String version = appSource.contains("replacement") ? "replacement"
                        : appSource.contains("stable") ? "stable"
                        : appSource.contains("first") ? "first"
                        : appSource.contains("new") ? "new" : "old";
                Files.writeString(dist.resolve("app.js"), version);
                if (mode != FixtureMode.MISSING_INDEX) {
                    Files.writeString(projectDirectory.resolve("dist/index.html"),
                            "<script type=\"module\" src=\"./assets/app.js\"></script>");
                }
                if (mode == FixtureMode.UNSUPPORTED_DIST_FILE) {
                    Files.writeString(projectDirectory.resolve("dist/run.exe"), "no");
                }
                if (mode == FixtureMode.MUTATE_SOURCE) {
                    Files.writeString(projectDirectory.resolve("src/App.vue"), "mutated");
                }
                if (mode == FixtureMode.UNEXPECTED_ROOT_FILE) {
                    Files.writeString(projectDirectory.resolve("builder.log"), "unexpected");
                }
                if (mode == FixtureMode.FAIL) {
                    throw new BusinessException(ErrorCode.OPERATION_ERROR, "fixture build failed");
                }
                return new VueBuildResult(Duration.ZERO, 0);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }
}
