package com.jack.autocodebackend.infrastructure.redis;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RedisDependencyStartupRunnerTest {

    private final RedisDependencyProbe probe = mock(RedisDependencyProbe.class);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(RedisDependencyProbe.class, () -> probe)
            .withUserConfiguration(RedisDependencyStartupRunner.class);

    @Test
    void enabledByDefaultAndRunsRequiredProbe() throws Exception {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(RedisDependencyStartupRunner.class);
            context.getBean(RedisDependencyStartupRunner.class)
                    .run(new DefaultApplicationArguments());
            verify(probe).requireAvailableAtStartup();
        });
    }

    @Test
    void explicitTestOverrideDisablesRunner() {
        contextRunner.withPropertyValues("app.redis.startup-check-enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(RedisDependencyStartupRunner.class));
    }
}
