package com.jack.autocodebackend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Objects;

@ConfigurationProperties("app.deployment.local-server")
public final class AppDeploymentLocalServerProperties {

    private final boolean enabled;

    private final String bindAddress;

    private final int port;

    public AppDeploymentLocalServerProperties(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("127.0.0.1") String bindAddress,
            @DefaultValue("9332") int port
    ) {
        this.enabled = enabled;
        this.bindAddress = normalizeBindAddress(bindAddress);
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("deployment local server port must be between 0 and 65535");
        }
        this.port = port;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getBindAddress() {
        return bindAddress;
    }

    public int getPort() {
        return port;
    }

    private static String normalizeBindAddress(String bindAddress) {
        String normalizedAddress = Objects.requireNonNull(
                bindAddress,
                "deployment local server bind address must not be null"
        ).trim();
        if (normalizedAddress.isBlank()) {
            throw new IllegalArgumentException("deployment local server bind address must not be blank");
        }
        return normalizedAddress;
    }
}
