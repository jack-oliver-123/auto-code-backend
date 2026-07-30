package com.jack.autocodebackend.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties({
        AppGenerationProperties.class,
        AppVueProjectProperties.class
})
public class AppGenerationConfiguration implements WebMvcConfigurer {

    private final AppGenerationProperties generationProperties;

    public AppGenerationConfiguration(
            AppGenerationProperties generationProperties,
            AppVueProjectProperties vueProjectProperties
    ) {
        this.generationProperties = generationProperties;
        generationProperties.validateAgainstVueBuildTimeout(
                vueProjectProperties.getBuildTimeout());
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setDefaultTimeout(
                generationProperties.getServletAsyncTimeout().toMillis());
    }
}
