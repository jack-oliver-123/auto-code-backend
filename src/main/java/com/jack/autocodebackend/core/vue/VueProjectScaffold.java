package com.jack.autocodebackend.core.vue;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public final class VueProjectScaffold {

    public static final String RESOURCE_ROOT = "vue-scaffold/";

    private static final Map<String, String> RESOURCES = Map.of(
            "index.html", RESOURCE_ROOT + "index.html",
            "package.json", RESOURCE_ROOT + "package.json",
            "package-lock.json", RESOURCE_ROOT + "package-lock.json",
            "vite.config.js", RESOURCE_ROOT + "vite.config.js"
    );

    private final Map<String, String> files;

    public VueProjectScaffold() {
        LinkedHashMap<String, String> loaded = new LinkedHashMap<>();
        for (String path : java.util.List.of(
                "index.html", "package.json", "package-lock.json", "vite.config.js")) {
            loaded.put(path, readRequiredResource(RESOURCES.get(path)));
        }
        this.files = Map.copyOf(loaded);
    }

    public Map<String, String> files() {
        return files;
    }

    private String readRequiredResource(String resourcePath) {
        ClassPathResource resource = new ClassPathResource(resourcePath);
        try (InputStream input = resource.getInputStream()) {
            String content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            if (content.isBlank()) {
                throw new IllegalStateException("Vue scaffold resource is blank: " + resourcePath);
            }
            return content;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Vue scaffold resource is unavailable: " + resourcePath, exception);
        }
    }
}
