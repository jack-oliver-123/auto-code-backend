package com.jack.autocodebackend.config;

import com.jack.autocodebackend.constant.AppConstant;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

@ConfigurationProperties("app.deployment")
public final class AppDeploymentProperties {

    private static final Pattern DEPLOY_KEY_PATTERN = Pattern.compile("[A-Za-z0-9]{6}");

    private final Path rootDir;

    private final String host;

    public AppDeploymentProperties(
            @DefaultValue("tmp/code_deploy") Path rootDir,
            @DefaultValue("http://localhost:9332") String host
    ) {
        this.rootDir = normalizeRoot(rootDir);
        this.host = normalizeHost(host);
        validateRootDoesNotOverlapCodeOutput(this.rootDir);
    }

    public Path getRootDir() {
        return rootDir;
    }

    public String getHost() {
        return host;
    }

    public String buildDeployUrl(String deployKey) {
        if (deployKey == null || !DEPLOY_KEY_PATTERN.matcher(deployKey).matches()) {
            throw new IllegalArgumentException("deployKey must be six alphanumeric characters");
        }
        return host + "/" + deployKey + "/";
    }

    private static Path normalizeRoot(Path rootDir) {
        return Objects.requireNonNull(rootDir, "deployment root directory must not be null")
                .toAbsolutePath()
                .normalize();
    }

    private static String normalizeHost(String host) {
        String normalizedHost = Objects.requireNonNull(host, "deployment host must not be null").trim();
        while (normalizedHost.endsWith("/")) {
            normalizedHost = normalizedHost.substring(0, normalizedHost.length() - 1);
        }
        if (normalizedHost.isBlank()) {
            throw new IllegalArgumentException("deployment host must not be blank");
        }
        return normalizedHost;
    }

    private static void validateRootDoesNotOverlapCodeOutput(Path deploymentRoot) {
        Path codeOutputRoot = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR)
                .toAbsolutePath()
                .normalize();
        if (deploymentRoot.startsWith(codeOutputRoot) || codeOutputRoot.startsWith(deploymentRoot)) {
            throw new IllegalArgumentException("deployment root must not overlap the code output root");
        }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AppDeploymentProperties.class, AppDeploymentLocalServerProperties.class})
class AppDeploymentPropertiesConfiguration {
}
