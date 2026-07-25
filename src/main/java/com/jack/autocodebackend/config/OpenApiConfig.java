package com.jack.autocodebackend.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata shared by the documented application endpoints.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    public static final String SESSION_COOKIE_SCHEME = "sessionCookie";

    @Bean
    public OpenAPI appOpenApi(
            @Value("${server.servlet.session.cookie.name:JSESSIONID}") String sessionCookieName
    ) {
        SecurityScheme sessionCookie = new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.COOKIE)
                .name(sessionCookieName)
                .description("Authenticated server session cookie");
        return new OpenAPI()
                .info(new Info()
                        .title("Auto Code Backend API")
                        .version("v1"))
                .components(new Components()
                        .addSecuritySchemes(SESSION_COOKIE_SCHEME, sessionCookie));
    }
}
