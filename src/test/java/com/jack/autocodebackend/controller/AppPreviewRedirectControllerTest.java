package com.jack.autocodebackend.controller;

import com.jack.autocodebackend.aop.AuthInterceptor;
import com.jack.autocodebackend.exception.BusinessException;
import com.jack.autocodebackend.exception.ErrorCode;
import com.jack.autocodebackend.model.domain.User;
import com.jack.autocodebackend.model.vo.AppPreviewVO;
import com.jack.autocodebackend.service.AppService;
import com.jack.autocodebackend.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AppPreviewRedirectController.class)
@Import({AuthInterceptor.class, AppPreviewRedirectControllerTest.AopTestConfiguration.class})
class AppPreviewRedirectControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AppService appService;

    @MockitoBean
    private UserService userService;

    private User loginUser;

    @BeforeEach
    void allowAuthenticatedRequests() {
        loginUser = new User();
        loginUser.setId(11L);
        loginUser.setUserAccount("owner");
        loginUser.setUserRole("user");
        given(userService.getLoginUser(any(HttpServletRequest.class))).willReturn(loginUser);
    }

    @Test
    void redirectsSupportedLegacyDirectoriesWithOrWithoutTrailingSlash() throws Exception {
        AppPreviewVO preview = preview("http://127.0.0.1:9332/preview/token-501/");
        AppPreviewVO secondPreview = preview("http://127.0.0.1:9332/preview/token-502/");
        given(appService.createAppPreview(501L, loginUser)).willReturn(preview);
        given(appService.createAppPreview(502L, loginUser)).willReturn(secondPreview);
        AppPreviewVO vuePreview = preview("http://127.0.0.1:9332/preview/token-vue/");
        given(appService.createAppPreview(Long.MAX_VALUE, loginUser)).willReturn(vuePreview);

        mockMvc.perform(get("/static/html_501"))
                .andExpect(status().isTemporaryRedirect())
                .andExpect(header().string(HttpHeaders.LOCATION, preview.getPreviewUrl()))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(content().string(""));
        mockMvc.perform(get("/static/html_501/"))
                .andExpect(status().isTemporaryRedirect())
                .andExpect(header().string(HttpHeaders.LOCATION, preview.getPreviewUrl()))
                .andExpect(content().string(""));
        mockMvc.perform(get("/static/multi_file_502"))
                .andExpect(status().isTemporaryRedirect())
                .andExpect(header().string(HttpHeaders.LOCATION, secondPreview.getPreviewUrl()));
        mockMvc.perform(get("/static/multi_file_502/"))
                .andExpect(status().isTemporaryRedirect())
                .andExpect(header().string(HttpHeaders.LOCATION, secondPreview.getPreviewUrl()));
        mockMvc.perform(get("/static/vue_project_" + Long.MAX_VALUE))
                .andExpect(status().isTemporaryRedirect())
                .andExpect(header().string(HttpHeaders.LOCATION, vuePreview.getPreviewUrl()));

        verify(appService, times(2)).createAppPreview(501L, loginUser);
        verify(appService, times(2)).createAppPreview(502L, loginUser);
        verify(appService).createAppPreview(Long.MAX_VALUE, loginUser);
    }

    @Test
    void rejectsMalformedOrOverflowingDirectoryNamesBeforePreviewIssuance() throws Exception {
        String[] invalidDirectories = {
                "html_0",
                "html_01",
                "html_-1",
                "HTML_1",
                "multi-file_1",
                "multi_file_9223372036854775808",
                "vue_project_01",
                "vue_project_9223372036854775808",
                "html_1.txt"
        };

        for (String invalidDirectory : invalidDirectories) {
            mockMvc.perform(get("/static/{directory}", invalidDirectory))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000));
        }

        verify(appService, never()).createAppPreview(any(), any());
    }

    @Test
    void returnsAuthenticationAndOwnershipErrorsWithoutRedirecting() throws Exception {
        given(userService.getLoginUser(any(HttpServletRequest.class)))
                .willThrow(new BusinessException(ErrorCode.NOT_LOGIN_ERROR));

        mockMvc.perform(get("/static/html_503/"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100))
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION));
        verify(appService, never()).createAppPreview(any(), any());

        given(userService.getLoginUser(any(HttpServletRequest.class))).willReturn(loginUser);
        given(appService.createAppPreview(503L, loginUser))
                .willThrow(new BusinessException(ErrorCode.NO_AUTH_ERROR));

        mockMvc.perform(get("/static/html_503/"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40101))
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION));
    }

    @Test
    void rejectsTemporaryPasswordUserWithoutIssuingPreview() throws Exception {
        given(userService.requiresPasswordChange(loginUser)).willReturn(true);

        mockMvc.perform(get("/static/html_504/"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301))
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION));

        verify(appService, never()).createAppPreview(any(), any());
    }

    private static AppPreviewVO preview(String previewUrl) {
        AppPreviewVO preview = new AppPreviewVO();
        preview.setPreviewUrl(previewUrl);
        preview.setExpiresAt(1_753_405_723_000L);
        return preview;
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class AopTestConfiguration {
    }
}
