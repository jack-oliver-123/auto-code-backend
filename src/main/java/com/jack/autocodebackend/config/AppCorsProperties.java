package com.jack.autocodebackend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@ConfigurationProperties("app.cors")
public final class AppCorsProperties {

    private final List<String> allowedOrigins;

    public AppCorsProperties(
            @DefaultValue({"http://localhost:5173", "http://localhost:5174"})
            List<String> allowedOrigins
    ) {
        this.allowedOrigins = normalizeAllowedOrigins(allowedOrigins);
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    private static List<String> normalizeAllowedOrigins(List<String> values) {
        Objects.requireNonNull(values, "allowedOrigins must not be null");
        Set<String> uniqueOrigins = new LinkedHashSet<>();
        for (String value : values) {
            uniqueOrigins.add(validateOrigin(value));
        }
        if (uniqueOrigins.isEmpty()) {
            throw new IllegalArgumentException("allowedOrigins must not be empty");
        }
        return List.copyOf(new ArrayList<>(uniqueOrigins));
    }

    private static String validateOrigin(String value) {
        String origin = Objects.requireNonNull(
                value, "allowedOrigins must not contain null").trim();
        if (origin.isEmpty()) {
            throw new IllegalArgumentException("allowedOrigins must not contain blanks");
        }
        if ("null".equalsIgnoreCase(origin) || origin.indexOf('*') >= 0) {
            throw new IllegalArgumentException(
                    "allowedOrigins must contain exact HTTP or HTTPS origins");
        }

        URI uri;
        try {
            uri = new URI(origin);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException(
                    "allowedOrigins must contain valid absolute origins");
        }

        String scheme = uri.getScheme();
        String normalizedScheme = scheme == null ? "" : scheme.toLowerCase(Locale.ROOT);
        if (!("http".equals(normalizedScheme) || "https".equals(normalizedScheme))
                || uri.isOpaque()
                || uri.getHost() == null
                || uri.getHost().isBlank()
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "allowedOrigins must contain valid absolute origins");
        }

        String path = uri.getRawPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            throw new IllegalArgumentException(
                    "allowedOrigins must not contain paths");
        }
        int port = uri.getPort();
        if (port == 0 || port > 65535) {
            throw new IllegalArgumentException(
                    "allowedOrigins must contain valid ports");
        }

        return "/".equals(path) ? origin.substring(0, origin.length() - 1) : origin;
    }
}
