package com.jack.autocodebackend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Objects;

@ConfigurationProperties("app.preview")
public final class AppPreviewProperties {

    private final String host;

    public AppPreviewProperties(
            @DefaultValue("http://localhost:9332") String host
    ) {
        this.host = normalizeHost(host);
    }

    public String getHost() {
        return host;
    }

    private static String normalizeHost(String host) {
        String normalizedHost = Objects.requireNonNull(host, "preview host must not be null").trim();
        while (normalizedHost.endsWith("/")) {
            normalizedHost = normalizedHost.substring(0, normalizedHost.length() - 1);
        }
        if (normalizedHost.isBlank()) {
            throw new IllegalArgumentException("preview host must not be blank");
        }
        return normalizedHost;
    }
}
