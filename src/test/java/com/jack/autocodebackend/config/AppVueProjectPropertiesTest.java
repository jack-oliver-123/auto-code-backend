package com.jack.autocodebackend.config;

import com.jack.autocodebackend.core.vue.VueProjectScaffold;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AppVueProjectPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void bindsSecureDocumentedDefaults() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            AppVueProjectProperties properties = context.getBean(AppVueProjectProperties.class);
            assertThat(properties.getProtocolVersion()).isEqualTo(1);
            assertThat(properties.getCombinedMaxFiles()).isEqualTo(28).isLessThan(30);
            assertThat(properties.getScaffoldFileCount()).isEqualTo(4);
            assertThat(properties.getModelMaxFiles()).isEqualTo(24);
            assertThat(properties.getBuildTimeout()).isEqualTo(Duration.ofSeconds(120));
            assertThat(properties.getBuildConcurrency()).isEqualTo(2);
            assertThat(properties.getRuntimeExecutable()).isEqualTo("docker");
            assertThat(properties.getBuilderImage()).isEqualTo(
                    "auto-code-vue-builder:1.0.0");
            assertThat(properties.isReadinessRequired()).isTrue();
            assertThat(properties.getReadinessProbeTimeout())
                    .isEqualTo(Duration.ofSeconds(2));
            assertThat(properties.getReadinessCacheTtl())
                    .isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.getReadinessDiagnosticMaxBytes()).isEqualTo(1024);
        });
    }

    @Test
    void bindsReadinessOverrides() {
        contextRunner.withPropertyValues(
                        "app.vue-project.readiness-required=false",
                        "app.vue-project.readiness-probe-timeout=3s",
                        "app.vue-project.readiness-cache-ttl=10s",
                        "app.vue-project.readiness-diagnostic-max-bytes=512")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AppVueProjectProperties properties =
                            context.getBean(AppVueProjectProperties.class);
                    assertThat(properties.isReadinessRequired()).isFalse();
                    assertThat(properties.getReadinessProbeTimeout())
                            .isEqualTo(Duration.ofSeconds(3));
                    assertThat(properties.getReadinessCacheTtl())
                            .isEqualTo(Duration.ofSeconds(10));
                    assertThat(properties.getReadinessDiagnosticMaxBytes())
                            .isEqualTo(512);
                });
    }

    @Test
    void rejectsInvalidAndIncoherentLimitsAtStartup() {
        assertBindingFailure("app.vue-project.protocol-version=2");
        assertBindingFailure("app.vue-project.response-max-chars=0");
        assertBindingFailure("app.vue-project.model-max-files=2");
        assertBindingFailure("app.vue-project.scaffold-file-count=3");
        assertBindingFailure("app.vue-project.combined-max-files=30");
        assertBindingFailure("app.vue-project.model-max-files=25");
        assertBindingFailure("app.vue-project.file-max-chars=101",
                "app.vue-project.source-max-chars=100");
        assertBindingFailure("app.vue-project.source-max-chars=101",
                "app.vue-project.source-context-max-chars=100");
        assertBindingFailure("app.vue-project.response-max-chars=500000");
        assertBindingFailure("app.vue-project.path-max-depth=2");
        assertBindingFailure("app.vue-project.build-timeout=0s");
        assertBindingFailure("app.vue-project.build-concurrency=0");
        assertBindingFailure("app.vue-project.builder-image= ");
        assertBindingFailure("app.vue-project.cpu-limit=0");
        assertBindingFailure("app.vue-project.memory-limit=invalid");
        assertBindingFailure("app.vue-project.diagnostic-max-bytes=0");
        assertBindingFailure("app.vue-project.readiness-probe-timeout=0s");
        assertBindingFailure("app.vue-project.readiness-probe-timeout=11s");
        assertBindingFailure("app.vue-project.readiness-cache-ttl=0s");
        assertBindingFailure("app.vue-project.readiness-cache-ttl=61s");
        assertBindingFailure("app.vue-project.readiness-diagnostic-max-bytes=0");
        assertBindingFailure("app.vue-project.readiness-diagnostic-max-bytes=4097");
    }

    @Test
    void trustedScaffoldPinsCompatibleDependenciesAndRelativeBase() throws Exception {
        VueProjectScaffold scaffold = new VueProjectScaffold();

        assertThat(scaffold.files()).containsOnlyKeys(
                "index.html", "package.json", "package-lock.json", "vite.config.js");
        assertThat(scaffold.files().get("package.json")).contains(
                "\"vue\": \"3.3.4\"",
                "\"vue-router\": \"4.2.4\"",
                "\"vite\": \"4.4.5\"",
                "\"@vitejs/plugin-vue\": \"4.2.3\"")
                .doesNotContain("preinstall", "postinstall");
        assertThat(scaffold.files().get("package-lock.json")).contains(
                "\"lockfileVersion\": 3",
                "node_modules/vue",
                "node_modules/vue-router");
        assertThat(scaffold.files().get("vite.config.js")).contains(
                "base: './'",
                "fileURLToPath(new URL('./src', import.meta.url))")
                .doesNotContain("port:");

        String yaml = Files.readString(
                Path.of("src", "main", "resources", "application.yaml"),
                StandardCharsets.UTF_8);
        assertThat(yaml).contains(
                "builder-image: ${VUE_PROJECT_BUILDER_IMAGE:auto-code-vue-builder:1.0.0}",
                "runtime-executable: ${VUE_PROJECT_RUNTIME_EXECUTABLE:docker}",
                "build-concurrency: ${VUE_PROJECT_BUILD_CONCURRENCY:2}",
                "readiness-required: ${VUE_PROJECT_READINESS_REQUIRED:true}",
                "readiness-probe-timeout: ${VUE_PROJECT_READINESS_PROBE_TIMEOUT:2s}",
                "readiness-cache-ttl: ${VUE_PROJECT_READINESS_CACHE_TTL:5s}",
                "readiness-diagnostic-max-bytes: ${VUE_PROJECT_READINESS_DIAGNOSTIC_MAX_BYTES:1024}");
    }

    private void assertBindingFailure(String... properties) {
        contextRunner.withPropertyValues(properties).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(
                    IllegalArgumentException.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppVueProjectProperties.class)
    static class PropertiesConfiguration {
    }
}
