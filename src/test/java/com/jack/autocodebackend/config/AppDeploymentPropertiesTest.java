package com.jack.autocodebackend.config;

import com.jack.autocodebackend.constant.AppConstant;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppDeploymentPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AppDeploymentPropertiesConfiguration.class);

    @Test
    void bindsOverridesAndNormalizesDeploymentConfiguration() {
        contextRunner
                .withPropertyValues(
                        "app.deployment.root-dir=build/deployment-test",
                        "app.deployment.host=https://static.example.com///",
                        "app.preview.host=https://preview.example.com///",
                        "app.deployment.local-server.enabled=false",
                        "app.deployment.local-server.bind-address=0.0.0.0",
                        "app.deployment.local-server.port=9443"
                )
                .run(context -> {
                    assertEquals(1, context.getBeanNamesForType(AppDeploymentProperties.class).length);
                    AppDeploymentProperties properties = context.getBean(AppDeploymentProperties.class);
                    assertEquals(
                            Path.of("build/deployment-test").toAbsolutePath().normalize(),
                            properties.getRootDir()
                    );
                    assertEquals("https://static.example.com", properties.getHost());
                    assertEquals(
                            "https://static.example.com/aB3x9Q/",
                            properties.buildDeployUrl("aB3x9Q")
                    );

                    AppPreviewProperties preview = context.getBean(AppPreviewProperties.class);
                    assertEquals("https://preview.example.com", preview.getHost());

                    AppDeploymentLocalServerProperties localServer = context.getBean(
                            AppDeploymentLocalServerProperties.class
                    );
                    assertEquals(false, localServer.isEnabled());
                    assertEquals("0.0.0.0", localServer.getBindAddress());
                    assertEquals(9443, localServer.getPort());
                });
    }

    @Test
    void bindsDevelopmentDefaultsForTheIsolatedLocalServer() {
        contextRunner.run(context -> {
            AppDeploymentProperties deployment = context.getBean(AppDeploymentProperties.class);
            AppPreviewProperties preview = context.getBean(AppPreviewProperties.class);
            AppDeploymentLocalServerProperties localServer = context.getBean(
                    AppDeploymentLocalServerProperties.class
            );

            assertEquals("http://localhost:9332", deployment.getHost());
            assertEquals("http://localhost:9332", preview.getHost());
            assertEquals(true, localServer.isEnabled());
            assertEquals("127.0.0.1", localServer.getBindAddress());
            assertEquals(9332, localServer.getPort());
        });
    }

    @Test
    void rejectsEqualOrNestedDeploymentAndOutputRoots() {
        Path outputRoot = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR).toAbsolutePath().normalize();

        assertThrows(
                IllegalArgumentException.class,
                () -> new AppDeploymentProperties(outputRoot, "http://localhost")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new AppDeploymentProperties(outputRoot.resolve("deploy"), "http://localhost")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new AppDeploymentProperties(outputRoot.getParent(), "http://localhost")
        );
    }

    @Test
    void rejectsBlankHostAndMalformedDeployKey() {
        Path deploymentRoot = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR)
                .toAbsolutePath()
                .normalize()
                .resolveSibling("code_deploy");

        assertThrows(
                IllegalArgumentException.class,
                () -> new AppDeploymentProperties(deploymentRoot, "///")
        );
        assertThrows(IllegalArgumentException.class, () -> new AppPreviewProperties("///"));

        AppDeploymentProperties properties = new AppDeploymentProperties(
                deploymentRoot,
                "http://localhost/"
        );
        assertThrows(IllegalArgumentException.class, () -> properties.buildDeployUrl("../bad"));
    }

    @Test
    void rejectsInvalidLocalServerConfiguration() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AppDeploymentLocalServerProperties(true, " ", 9332)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new AppDeploymentLocalServerProperties(true, "127.0.0.1", -1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new AppDeploymentLocalServerProperties(true, "127.0.0.1", 65536)
        );
    }
}
