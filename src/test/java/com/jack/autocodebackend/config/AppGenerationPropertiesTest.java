package com.jack.autocodebackend.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AppGenerationPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void bindsFiniteCoherentDefaults() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            AppGenerationProperties properties =
                    context.getBean(AppGenerationProperties.class);
            assertThat(properties.getHeartbeatInterval()).isEqualTo(Duration.ofSeconds(15));
            assertThat(properties.getProviderTimeout()).isEqualTo(Duration.ofMinutes(5));
            assertThat(properties.getCompleteAttemptTimeout()).isEqualTo(Duration.ofMinutes(8));
            assertThat(properties.getServletAsyncTimeout()).isEqualTo(Duration.ofMinutes(9));
            assertThat(properties.getStaleAttemptAge()).isEqualTo(Duration.ofMinutes(12));
            properties.validateAgainstVueBuildTimeout(Duration.ofMinutes(2));
        });
    }

    @Test
    void bindsEnvironmentStyleOverrides() {
        contextRunner.withPropertyValues(
                "app.generation.heartbeat-interval=10s",
                "app.generation.provider-timeout=4m",
                "app.generation.complete-attempt-timeout=7m",
                "app.generation.servlet-async-timeout=8m",
                "app.generation.stale-attempt-age=10m"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            AppGenerationProperties properties =
                    context.getBean(AppGenerationProperties.class);
            assertThat(properties.getProviderTimeout()).isEqualTo(Duration.ofMinutes(4));
            assertThat(properties.getCompleteAttemptTimeout()).isEqualTo(Duration.ofMinutes(7));
        });
    }

    @Test
    void rejectsNonPositiveAndInvalidOrdering() {
        assertBindingFailure("app.generation.provider-timeout=0s");
        assertBindingFailure(
                "app.generation.heartbeat-interval=5m",
                "app.generation.provider-timeout=5m");
        assertBindingFailure(
                "app.generation.provider-timeout=8m",
                "app.generation.complete-attempt-timeout=8m");
        assertBindingFailure(
                "app.generation.complete-attempt-timeout=9m",
                "app.generation.servlet-async-timeout=9m");
        assertBindingFailure(
                "app.generation.servlet-async-timeout=12m",
                "app.generation.stale-attempt-age=12m");
    }

    @Test
    void rejectsInsufficientProviderBuildAndFinalizationAllowance() {
        AppGenerationProperties properties = new AppGenerationProperties(
                Duration.ofMinutes(5),
                Duration.ofMinutes(7),
                Duration.ofMinutes(8),
                Duration.ofSeconds(10),
                Duration.ofMinutes(10)
        );

        assertThatThrownBy(() -> properties.validateAgainstVueBuildTimeout(
                Duration.ofMinutes(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finalization allowance");
    }

    @Test
    void trackedConfigurationWiresOneProviderLimitAndMvcHierarchy() throws Exception {
        String yaml = Files.readString(
                Path.of("src", "main", "resources", "application.yaml"),
                StandardCharsets.UTF_8);

        assertThat(yaml).contains(
                "provider-timeout: ${APP_GENERATION_PROVIDER_TIMEOUT:5m}",
                "complete-attempt-timeout: ${APP_GENERATION_COMPLETE_ATTEMPT_TIMEOUT:8m}",
                "servlet-async-timeout: ${APP_GENERATION_SERVLET_ASYNC_TIMEOUT:9m}",
                "heartbeat-interval: ${APP_GENERATION_HEARTBEAT_INTERVAL:15s}",
                "stale-attempt-age: ${APP_GENERATION_STALE_ATTEMPT_AGE:12m}");
        assertThat(yaml.split(Pattern.quote(
                "timeout: ${APP_GENERATION_PROVIDER_TIMEOUT:5m}"), -1))
                .hasSize(4);
    }

    @Test
    void mvcAsyncTimeoutUsesTheTypedGenerationLimit() {
        AppGenerationProperties properties = AppGenerationProperties.defaults();
        AppVueProjectProperties vueProperties = mock(AppVueProjectProperties.class);
        AsyncSupportConfigurer configurer = mock(AsyncSupportConfigurer.class);
        given(vueProperties.getBuildTimeout()).willReturn(Duration.ofMinutes(2));

        AppGenerationConfiguration configuration =
                new AppGenerationConfiguration(properties, vueProperties);
        configuration.configureAsyncSupport(configurer);

        verify(configurer).setDefaultTimeout(Duration.ofMinutes(9).toMillis());
    }

    private void assertBindingFailure(String... properties) {
        contextRunner.withPropertyValues(properties).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppGenerationProperties.class)
    static class PropertiesConfiguration {
    }
}
