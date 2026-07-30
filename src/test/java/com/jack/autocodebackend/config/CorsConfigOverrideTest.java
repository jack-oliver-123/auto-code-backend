package com.jack.autocodebackend.config;

import com.jack.autocodebackend.controller.HealthController;
import com.jack.autocodebackend.core.vue.VueBuilderDependencyProbe;
import com.jack.autocodebackend.infrastructure.redis.RedisDependencyProbe;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HealthController.class)
@Import(CorsConfig.class)
@TestPropertySource(properties =
        "app.cors.allowed-origins=https://app.example.com")
class CorsConfigOverrideTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RedisDependencyProbe redisDependencyProbe;

    @MockitoBean
    private VueBuilderDependencyProbe vueBuilderDependencyProbe;

    @Test
    void explicitDeploymentListAllowsOnlyConfiguredOrigin() throws Exception {
        mockMvc.perform(options("/health/check")
                        .header("Origin", "https://app.example.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Access-Control-Allow-Origin", "https://app.example.com"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));

        mockMvc.perform(options("/health/check")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
