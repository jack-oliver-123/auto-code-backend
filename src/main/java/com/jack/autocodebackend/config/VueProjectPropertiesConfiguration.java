package com.jack.autocodebackend.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AppVueProjectProperties.class)
class VueProjectPropertiesConfiguration {
}
