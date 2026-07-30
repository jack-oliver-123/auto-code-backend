package com.jack.autocodebackend.infrastructure.redis;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "app.redis",
        name = "startup-check-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class RedisDependencyStartupRunner implements ApplicationRunner {

    private final RedisDependencyProbe redisDependencyProbe;

    public RedisDependencyStartupRunner(RedisDependencyProbe redisDependencyProbe) {
        this.redisDependencyProbe = redisDependencyProbe;
    }

    @Override
    public void run(ApplicationArguments args) {
        redisDependencyProbe.requireAvailableAtStartup();
    }
}
