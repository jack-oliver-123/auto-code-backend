package com.jack.autocodebackend.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiConfigTest {

    @Test
    void sessionAuthenticationIsDocumentedAsTheConfiguredCookie() {
        OpenAPI openAPI = new OpenApiConfig().appOpenApi("AUTO_CODE_SESSION");

        SecurityScheme scheme = openAPI.getComponents()
                .getSecuritySchemes()
                .get(OpenApiConfig.SESSION_COOKIE_SCHEME);
        assertThat(scheme).isNotNull();
        assertThat(scheme.getType()).isEqualTo(SecurityScheme.Type.APIKEY);
        assertThat(scheme.getIn()).isEqualTo(SecurityScheme.In.COOKIE);
        assertThat(scheme.getName()).isEqualTo("AUTO_CODE_SESSION");
    }
}
