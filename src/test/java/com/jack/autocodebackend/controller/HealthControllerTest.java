package com.jack.autocodebackend.controller;

import com.jack.autocodebackend.core.vue.VueBuilderDependencyProbe;
import com.jack.autocodebackend.exception.ErrorCode;
import com.jack.autocodebackend.infrastructure.redis.RedisDependencyProbe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.session.MapSession;
import org.springframework.session.SessionRepository;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HealthControllerTest {

    private final RedisDependencyProbe redisProbe = mock(RedisDependencyProbe.class);

    private final VueBuilderDependencyProbe vueBuilderProbe =
            mock(VueBuilderDependencyProbe.class);

    @SuppressWarnings("unchecked")
    private final SessionRepository<MapSession> sessionRepository =
            mock(SessionRepository.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SessionRepositoryFilter<MapSession> sessionFilter =
                new SessionRepositoryFilter<>(sessionRepository);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new HealthController(redisProbe, vueBuilderProbe))
                .addFilters(sessionFilter)
                .build();
    }

    @Test
    void livenessDoesNotDependOnExternalServicesOrCreateSession() throws Exception {
        mockMvc.perform(get("/health/live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("ok"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        verifyNoInteractions(redisProbe, vueBuilderProbe);
        verifyNoInteractions(sessionRepository);
    }

    @Test
    void readinessReportsRedisFailureWithoutProbingBuilder() throws Exception {
        given(redisProbe.checkReadiness()).willReturn(false);

        mockMvc.perform(get("/health/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code")
                        .value(ErrorCode.DEPENDENCY_UNAVAILABLE.getCode()))
                .andExpect(jsonPath("$.message")
                        .value(ErrorCode.DEPENDENCY_UNAVAILABLE.getMessage()))
                .andExpect(jsonPath("$.data").value("redis"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        verifyNoInteractions(vueBuilderProbe);
        verifyNoInteractions(sessionRepository);
    }

    @Test
    void readinessReportsBuilderFailure() throws Exception {
        given(redisProbe.checkReadiness()).willReturn(true);
        given(vueBuilderProbe.checkReadiness()).willReturn(false);

        mockMvc.perform(get("/health/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code")
                        .value(ErrorCode.DEPENDENCY_UNAVAILABLE.getCode()))
                .andExpect(jsonPath("$.message")
                        .value(ErrorCode.DEPENDENCY_UNAVAILABLE.getMessage()))
                .andExpect(jsonPath("$.data").value("vue-builder"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        verify(redisProbe).checkReadiness();
        verify(vueBuilderProbe).checkReadiness();
        verifyNoInteractions(sessionRepository);
    }

    @Test
    void readinessRecoversWhenBothDependenciesRecover() throws Exception {
        given(redisProbe.checkReadiness()).willReturn(true);
        given(vueBuilderProbe.checkReadiness()).willReturn(false, true);

        mockMvc.perform(get("/health/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data").value("vue-builder"));

        mockMvc.perform(get("/health/ready"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("ready"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        verifyNoInteractions(sessionRepository);
    }
}
