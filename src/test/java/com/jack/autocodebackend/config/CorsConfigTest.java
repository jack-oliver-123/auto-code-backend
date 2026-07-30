package com.jack.autocodebackend.config;

import com.jack.autocodebackend.controller.HealthController;
import com.jack.autocodebackend.core.vue.VueBuilderDependencyProbe;
import com.jack.autocodebackend.infrastructure.redis.RedisDependencyProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HealthController.class)
@Import(CorsConfig.class)
class CorsConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RedisDependencyProbe redisDependencyProbe;

    @MockitoBean
    private VueBuilderDependencyProbe vueBuilderDependencyProbe;

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:5173", "http://localhost:5174"})
    void preflightAllowsBothDevelopmentOriginsWithCredentials(String origin)
            throws Exception {
        mockMvc.perform(options("/health/check")
                        .header("Origin", origin)
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", origin))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void actualRequestAllowsFallbackDevelopmentOriginWithCredentials() throws Exception {
        mockMvc.perform(get("/health/check")
                        .header("Origin", "http://localhost:5174"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Access-Control-Allow-Origin", "http://localhost:5174"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void preflightRejectsUnconfiguredOrigin() throws Exception {
        mockMvc.perform(options("/health/check")
                        .header("Origin", "https://attacker.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void rejectsSimpleRequestFromSandboxedGeneratedContent() throws Exception {
        mockMvc.perform(get("/health/check").header("Origin", "null"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
