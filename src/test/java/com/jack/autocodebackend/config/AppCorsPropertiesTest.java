package com.jack.autocodebackend.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AppCorsPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void bindsExactDevelopmentDefaults() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AppCorsProperties.class).getAllowedOrigins())
                    .containsExactly(
                            "http://localhost:5173",
                            "http://localhost:5174");
        });
    }

    @Test
    void trimsDeduplicatesAndPreservesConfiguredOrder() {
        contextRunner.withPropertyValues(
                        "app.cors.allowed-origins= http://localhost:5174,"
                                + " https://console.example.com ,http://localhost:5174 ")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AppCorsProperties.class)
                            .getAllowedOrigins()).containsExactly(
                                    "http://localhost:5174",
                                    "https://console.example.com");
                });
    }

    @Test
    void explicitListCompletelyReplacesDevelopmentDefaults() {
        contextRunner.withPropertyValues(
                        "app.cors.allowed-origins=https://app.example.com")
                .run(context -> assertThat(context.getBean(AppCorsProperties.class)
                        .getAllowedOrigins()).containsExactly("https://app.example.com"));
    }

    @Test
    void normalizesAnOptionalRootSlash() {
        AppCorsProperties properties = new AppCorsProperties(
                List.of("https://app.example.com/"));

        assertThat(properties.getAllowedOrigins())
                .containsExactly("https://app.example.com");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            " , ",
            "*",
            "https://*.example.com",
            "null",
            "ftp://example.com",
            "//example.com",
            "https://user@example.com",
            "https://example.com/path",
            "https://example.com?query=1",
            "https://example.com#fragment",
            "https://example.com:0",
            "https://example.com:65536",
            "https://example.com:invalid",
            "https://",
            "http://exa mple.com"
    })
    void rejectsUnsafeOrMalformedOrigins(String value) {
        contextRunner.withPropertyValues("app.cors.allowed-origins=" + value)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppCorsProperties.class)
    static class PropertiesConfiguration {
    }
}
