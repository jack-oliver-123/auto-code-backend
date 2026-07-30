package com.jack.autocodebackend.core.vue;

import com.jack.autocodebackend.config.AppVueProjectProperties;
import com.jack.autocodebackend.controller.HealthController;
import com.jack.autocodebackend.infrastructure.redis.RedisDependencyProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static com.jack.autocodebackend.core.vue.VueProjectSourceValidatorTest.validResult;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfSystemProperty(named = "vue.builder.smoke.enabled", matches = "true")
class VueProjectBuilderIT {

    @TempDir
    Path temporaryDirectory;

    @Test
    void trustedImageBuildsPinnedMinimalFixtureWithoutAi() throws Exception {
        String image = System.getProperty(
                "vue.builder.image", "auto-code-vue-builder:1.0.0");
        AppVueProjectProperties properties = properties(image);
        assertThat(new VueBuilderDependencyProbe(
                properties, new BoundedProcessExecutor()).checkReadiness()).isTrue();
        VueProjectScaffold scaffold = new VueProjectScaffold();
        VueProjectSourceValidator sourceValidator =
                new VueProjectSourceValidator(properties);
        Path project = Files.createDirectory(temporaryDirectory.resolve("project"));
        new VueProjectMaterializer(scaffold).materialize(
                sourceValidator.validate(validResult()), project);

        VueProjectBuilder.VueBuildResult result = new ContainerVueProjectBuilder(
                properties, new BoundedProcessExecutor()).build(Long.MAX_VALUE, project);
        VueDistValidator.DistSummary dist =
                new VueDistValidator(properties).validateProjectDist(project);

        assertThat(result.duration()).isGreaterThanOrEqualTo(Duration.ZERO);
        assertThat(dist.fileCount()).isPositive();
        assertThat(dist.totalBytes()).isPositive();
        assertThat(dist.directory().resolve("index.html")).isRegularFile();
    }

    @Test
    void readinessRejectsMissingImageAndAcceptsTrustedImage() throws Exception {
        String missingImage = "auto-code-vue-builder:missing-" + UUID.randomUUID();
        String trustedImage = System.getProperty(
                "vue.builder.image", "auto-code-vue-builder:1.0.0");
        RedisDependencyProbe redisProbe = mock(RedisDependencyProbe.class);
        given(redisProbe.checkReadiness()).willReturn(true);

        MockMvc missingImageHealth = MockMvcBuilders.standaloneSetup(
                new HealthController(
                        redisProbe,
                        new VueBuilderDependencyProbe(
                                properties(missingImage), new BoundedProcessExecutor())
                )).build();
        missingImageHealth.perform(get("/health/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data").value("vue-builder"));

        MockMvc trustedImageHealth = MockMvcBuilders.standaloneSetup(
                new HealthController(
                        redisProbe,
                        new VueBuilderDependencyProbe(
                                properties(trustedImage), new BoundedProcessExecutor())
                )).build();
        trustedImageHealth.perform(get("/health/ready"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("ready"));
    }

    private static AppVueProjectProperties properties(String image) {
        return new AppVueProjectProperties(
                1, 600_000, 24, 4, 28, 100_000, 500_000, 500_000,
                180, 8, 200, 20_971_520L, Duration.ofSeconds(120),
                Duration.ofSeconds(5), 1, "docker", image, "1", "512m",
                128, "64m", 16_384);
    }
}
