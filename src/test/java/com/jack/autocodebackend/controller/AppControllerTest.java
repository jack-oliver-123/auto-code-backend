package com.jack.autocodebackend.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jack.autocodebackend.aop.AuthInterceptor;
import com.jack.autocodebackend.config.JsonConfig;
import com.jack.autocodebackend.exception.BusinessException;
import com.jack.autocodebackend.exception.ErrorCode;
import com.jack.autocodebackend.model.domain.App;
import com.jack.autocodebackend.model.domain.User;
import com.jack.autocodebackend.model.dto.AppAddDTO;
import com.jack.autocodebackend.model.dto.AppAdminUpdateDTO;
import com.jack.autocodebackend.model.dto.AppChatRequestDTO;
import com.jack.autocodebackend.model.dto.AppNameQueryDTO;
import com.jack.autocodebackend.model.dto.AppQueryDTO;
import com.jack.autocodebackend.model.dto.AppUpdateDTO;
import com.jack.autocodebackend.model.vo.AppDeployVO;
import com.jack.autocodebackend.model.vo.AppDetailVO;
import com.jack.autocodebackend.model.vo.AppGenerationEvent;
import com.jack.autocodebackend.model.vo.AppPreviewVO;
import com.jack.autocodebackend.model.vo.AppVO;
import com.jack.autocodebackend.model.vo.PublicAppDetailVO;
import com.jack.autocodebackend.service.AppService;
import com.jack.autocodebackend.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Signal;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AppController.class)
@Import({AuthInterceptor.class, JsonConfig.class, AppControllerTest.AopTestConfiguration.class})
class AppControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppController appController;

    @MockitoBean
    private AppService appService;

    @MockitoBean
    private UserService userService;

    private User loginUser;

    @BeforeEach
    void allowAuthenticatedAndAdminRequests() {
        loginUser = createUser(1L, "admin");
        given(userService.getLoginUser(any(HttpServletRequest.class))).willReturn(loginUser);
    }

    @Test
    void addAppReturnsNewAppId() throws Exception {
        given(appService.createApp(any(AppAddDTO.class), same(loginUser))).willReturn(101L);

        mockMvc.perform(post("/app/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"initPrompt": "Create a portfolio"}
                                """))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value("101"))
                .andExpect(jsonPath("$.message").value("ok"));

        ArgumentCaptor<AppAddDTO> captor = ArgumentCaptor.forClass(AppAddDTO.class);
        verify(appService).createApp(captor.capture(), same(loginUser));
        assertThat(captor.getValue().getInitPrompt()).isEqualTo("Create a portfolio");
    }

    @Test
    void addAppRejectsNullRequestBody() throws Exception {
        mockMvc.perform(post("/app/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("null"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));

        verify(appService, never()).createApp(any(), any());
    }

    @Test
    void addAppReturnsParameterErrorForBlankPrompt() throws Exception {
        given(appService.createApp(any(AppAddDTO.class), same(loginUser)))
                .willThrow(new BusinessException(ErrorCode.PARAMS_ERROR,
                        "初始化 prompt 不能为空"));

        mockMvc.perform(post("/app/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"initPrompt": "   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void chatToGenCodeDelegatesParametersAndPreservesWhitespaceInSseData() throws Exception {
        AppPreviewVO preview = preview(
                "http://127.0.0.1:9332/preview/generated-token/",
                1_753_405_723_000L
        );
        given(appService.chatToGenCode(401L, " refine layout ", loginUser))
                .willReturn(Flux.just(
                        new AppGenerationEvent.Content("  <main>\n"),
                        new AppGenerationEvent.Content("tail  "),
                        new AppGenerationEvent.Completed(preview)
                ));

        MvcResult streamingResult = mockMvc.perform(post("/app/chat/gen/code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"appId": 401, "message": " refine layout "}
                                """)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult completedResult = mockMvc.perform(asyncDispatch(streamingResult))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn();
        String responseBody = completedResult.getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(responseBody)
                .contains("{\"d\":\"  <main>\\n\"}")
                .contains("{\"d\":\"tail  \"}")
                .contains("event:done")
                .contains("\"previewUrl\":\"http://127.0.0.1:9332/preview/generated-token/\"")
                .contains("\"expiresAt\":1753405723000")
                .containsOnlyOnce("event:done");
        assertThat(responseBody.indexOf("event:done"))
                .isGreaterThan(responseBody.indexOf("{\"d\":\"tail  \"}"));
        verify(appService).chatToGenCode(401L, " refine layout ", loginUser);
    }

    @Test
    void chatToGenCodeAllowsMissingMessageAndSendsDoneEvent() throws Exception {
        AppPreviewVO preview = preview(
                "http://127.0.0.1:9332/preview/initial-token/",
                1_753_405_724_000L
        );
        given(appService.chatToGenCode(eq(402L), isNull(), same(loginUser)))
                .willReturn(Flux.just(new AppGenerationEvent.Completed(preview)));

        MvcResult streamingResult = mockMvc.perform(post("/app/chat/gen/code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"appId": 402}
                                """)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult completedResult = mockMvc.perform(asyncDispatch(streamingResult))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(completedResult.getResponse().getContentAsString())
                .contains("event:done")
                .contains("\"previewUrl\":\"http://127.0.0.1:9332/preview/initial-token/\"")
                .contains("\"expiresAt\":1753405724000");
        verify(appService).chatToGenCode(eq(402L), isNull(), same(loginUser));
    }

    @Test
    void chatToGenCodeRejectsMissingOrNonPositiveAppId() throws Exception {
        mockMvc.perform(post("/app/chat/gen/code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/app/chat/gen/code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"appId": 0}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(appService);
    }

    @Test
    void chatToGenCodeDoesNotSendDoneWhenServiceStreamFails() throws Exception {
        IllegalStateException failure = new IllegalStateException("generation failed");
        given(appService.chatToGenCode(403L, "continue", loginUser))
                .willReturn(Flux.concat(
                        Flux.just(new AppGenerationEvent.Content("partial")),
                        Flux.error(failure)
                ));
        AppController targetController = AopTestUtils.getTargetObject(appController);
        AppChatRequestDTO request = new AppChatRequestDTO();
        request.setAppId(403L);
        request.setMessage("continue");

        List<Signal<ServerSentEvent<String>>> signals = targetController
                .chatToGenCode(request, new MockHttpServletRequest())
                .materialize()
                .collectList()
                .block();

        assertThat(signals).isNotNull();
        assertThat(signals)
                .filteredOn(Signal::isOnNext)
                .extracting(signal -> signal.get().event())
                .doesNotContain("done");
        assertThat(signals.getFirst().get().data()).isEqualTo("{\"d\":\"partial\"}");
        assertThat(signals.getLast().isOnError()).isTrue();
        assertThat(signals.getLast().getThrowable()).isSameAs(failure);
    }

    @Test
    void chatToGenCodeRejectsLegacyGetRoute() throws Exception {
        mockMvc.perform(get("/app/chat/gen/code")
                        .param("appId", "401")
                        .param("message", "legacy"))
                .andExpect(status().isMethodNotAllowed());

        verifyNoInteractions(appService);
    }

    @Test
    void createAppPreviewReturnsIsolatedPreviewMetadata() throws Exception {
        AppPreviewVO previewVO = new AppPreviewVO();
        previewVO.setPreviewUrl("http://127.0.0.1:9332/preview/owner-token/");
        previewVO.setExpiresAt(1_753_405_723_000L);
        given(appService.createAppPreview(451L, loginUser)).willReturn(previewVO);

        mockMvc.perform(post("/app/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"appId": 451}
                                """))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.previewUrl")
                        .value("http://127.0.0.1:9332/preview/owner-token/"))
                .andExpect(jsonPath("$.data.expiresAt").exists())
                .andExpect(jsonPath("$.data.deployKey").doesNotExist());

        verify(appService).createAppPreview(451L, loginUser);
    }

    @Test
    void createAppPreviewRejectsNullMissingAndNonPositiveAppId() throws Exception {
        String[] invalidBodies = {"null", "{}", "{\"appId\": 0}", "{\"appId\": -1}"};

        for (String invalidBody : invalidBodies) {
            mockMvc.perform(post("/app/preview")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invalidBody))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000));
        }

        verify(appService, never()).createAppPreview(any(), any());
    }

    @Test
    void createAppPreviewReturnsServiceOwnershipError() throws Exception {
        given(appService.createAppPreview(452L, loginUser))
                .willThrow(new BusinessException(ErrorCode.NO_AUTH_ERROR));

        mockMvc.perform(post("/app/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"appId": 452}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    void createAppPreviewRejectsAnonymousUser() throws Exception {
        given(userService.getLoginUser(any(HttpServletRequest.class)))
                .willThrow(new BusinessException(ErrorCode.NOT_LOGIN_ERROR));

        mockMvc.perform(post("/app/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"appId": 453}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));

        verify(appService, never()).createAppPreview(any(), any());
    }

    @Test
    void createAppPreviewRejectsTemporaryPasswordUser() throws Exception {
        given(userService.requiresPasswordChange(loginUser)).willReturn(true);

        mockMvc.perform(post("/app/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"appId": 454}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));

        verify(appService, never()).createAppPreview(any(), any());
    }

    @Test
    void deployAppReturnsSerializedDeploymentMetadata() throws Exception {
        AppDeployVO deployVO = new AppDeployVO();
        deployVO.setDeployKey("Ab3x9Q");
        deployVO.setDeployUrl("https://apps.example.com/Ab3x9Q/");
        deployVO.setDeployedTime(new Date(1_753_405_723_000L));
        given(appService.deployApp(501L, loginUser)).willReturn(deployVO);

        mockMvc.perform(post("/app/deploy").param("appId", "501"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.deployKey").value("Ab3x9Q"))
                .andExpect(jsonPath("$.data.deployUrl")
                        .value("https://apps.example.com/Ab3x9Q/"))
                .andExpect(jsonPath("$.data.deployedTime").exists())
                .andExpect(jsonPath("$.message").value("ok"));

        verify(appService).deployApp(501L, loginUser);
    }

    @Test
    void deployAppRejectsMissingOrNonPositiveAppId() throws Exception {
        mockMvc.perform(post("/app/deploy"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        mockMvc.perform(post("/app/deploy").param("appId", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        mockMvc.perform(post("/app/deploy").param("appId", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));

        verify(appService, never()).deployApp(any(), any());
    }

    @Test
    void deployAppReturnsServiceOwnershipError() throws Exception {
        given(appService.deployApp(502L, loginUser))
                .willThrow(new BusinessException(ErrorCode.NO_AUTH_ERROR));

        mockMvc.perform(post("/app/deploy").param("appId", "502"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40101))
                .andExpect(jsonPath("$.message").value("无权限"));
    }

    @Test
    void deployAppReturnsServiceOperationError() throws Exception {
        given(appService.deployApp(503L, loginUser))
                .willThrow(new BusinessException(ErrorCode.OPERATION_ERROR,
                        "应用尚未完成生成"));

        mockMvc.perform(post("/app/deploy").param("appId", "503"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(50001))
                .andExpect(jsonPath("$.message").value("应用尚未完成生成"));
    }

    @Test
    void allAuthenticatedUserRoutesRejectAnonymousUser() throws Exception {
        given(userService.getLoginUser(any(HttpServletRequest.class)))
                .willThrow(new BusinessException(ErrorCode.NOT_LOGIN_ERROR));

        mockMvc.perform(post("/app/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"initPrompt": "Create a portfolio"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100))
                .andExpect(jsonPath("$.message").value("未登录"));
        mockMvc.perform(post("/app/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id": 201, "appName": "Renamed app"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100))
                .andExpect(jsonPath("$.message").value("未登录"));
        mockMvc.perform(post("/app/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id": 301}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100))
                .andExpect(jsonPath("$.message").value("未登录"));
        mockMvc.perform(post("/app/my/list/page/vo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100))
                .andExpect(jsonPath("$.message").value("未登录"));

        mockMvc.perform(post("/app/chat/gen/code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"appId": 401}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
        mockMvc.perform(get("/app/get/vo").param("id", "401"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
        mockMvc.perform(post("/app/deploy").param("appId", "501"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100))
                .andExpect(jsonPath("$.message").value("未登录"));

        verifyNoInteractions(appService);
    }

    @Test
    void allAuthenticatedUserRoutesRejectTemporaryPasswordUser() throws Exception {
        given(userService.requiresPasswordChange(loginUser)).willReturn(true);

        mockMvc.perform(post("/app/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"initPrompt": "Create a portfolio"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301))
                .andExpect(jsonPath("$.message").value("请先修改初始密码"));
        mockMvc.perform(post("/app/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id": 201, "appName": "Renamed app"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301))
                .andExpect(jsonPath("$.message").value("请先修改初始密码"));
        mockMvc.perform(post("/app/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id": 301}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301))
                .andExpect(jsonPath("$.message").value("请先修改初始密码"));
        mockMvc.perform(post("/app/my/list/page/vo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301))
                .andExpect(jsonPath("$.message").value("请先修改初始密码"));

        mockMvc.perform(post("/app/chat/gen/code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"appId": 401}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
        mockMvc.perform(get("/app/get/vo").param("id", "401"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
        mockMvc.perform(post("/app/deploy").param("appId", "501"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301))
                .andExpect(jsonPath("$.message").value("请先修改初始密码"));

        verifyNoInteractions(appService);
    }

    @Test
    void updateAppDelegatesOnlyOrdinaryUserFields() throws Exception {
        given(appService.updateAppByUser(any(AppUpdateDTO.class), same(loginUser)))
                .willReturn(true);

        mockMvc.perform(post("/app/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "id": 201,
                                  "appName": "Renamed app",
                                  "cover": "ignored-cover",
                                  "priority": 99,
                                  "userId": 999,
                                  "initPrompt": "ignored prompt"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(true));

        ArgumentCaptor<AppUpdateDTO> captor = ArgumentCaptor.forClass(AppUpdateDTO.class);
        verify(appService).updateAppByUser(captor.capture(), same(loginUser));
        assertThat(captor.getValue().getId()).isEqualTo(201L);
        assertThat(captor.getValue().getAppName()).isEqualTo("Renamed app");
    }

    @Test
    void updateAppRejectsInvalidIdBeforeCallingService() throws Exception {
        mockMvc.perform(post("/app/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id": 0, "appName": "Renamed app"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));

        verify(appService, never()).updateAppByUser(any(), any());
    }

    @Test
    void updateAppReturnsNoAuthorityForForeignOwner() throws Exception {
        given(appService.updateAppByUser(any(AppUpdateDTO.class), same(loginUser)))
                .willThrow(new BusinessException(ErrorCode.NO_AUTH_ERROR));

        mockMvc.perform(post("/app/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id": 202, "appName": "Foreign app"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40101))
                .andExpect(jsonPath("$.message").value("无权限"));
    }

    @Test
    void deleteAppDelegatesOwnerAndId() throws Exception {
        given(appService.deleteAppByUser(301L, loginUser)).willReturn(true);

        mockMvc.perform(post("/app/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id": 301}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(true));

        verify(appService).deleteAppByUser(301L, loginUser);
    }

    @Test
    void deleteAppReturnsNotFoundForMissingApp() throws Exception {
        given(appService.deleteAppByUser(302L, loginUser))
                .willThrow(new BusinessException(ErrorCode.NOT_FOUND_ERROR));

        mockMvc.perform(post("/app/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id": 302}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void deleteAppReturnsNoAuthorityForForeignOwner() throws Exception {
        given(appService.deleteAppByUser(303L, loginUser))
                .willThrow(new BusinessException(ErrorCode.NO_AUTH_ERROR));

        mockMvc.perform(post("/app/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id": 303}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    void ownerDetailReturnsFullBusinessViewWithoutInternalFields() throws Exception {
        AppDetailVO detailVO = createDetailVO(401L, 7L);
        given(appService.getAppDetailVOByOwner(401L, loginUser)).willReturn(detailVO);

        mockMvc.perform(get("/app/get/vo").param("id", "401"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value("401"))
                .andExpect(jsonPath("$.data.appName").value("App 401"))
                .andExpect(jsonPath("$.data.initPrompt").value("Build application 401"))
                .andExpect(jsonPath("$.data.userId").value("7"))
                .andExpect(jsonPath("$.data.deployUrl")
                        .value("https://apps.example.com/deploy-401/"))
                .andExpect(jsonPath("$.data.isDelete").doesNotExist())
                .andExpect(jsonPath("$.data.editTime").doesNotExist());

        verify(appService).getAppDetailVOByOwner(401L, loginUser);
    }

    @Test
    void ownerDetailRejectsMissingMalformedAndNonPositiveIds() throws Exception {
        mockMvc.perform(get("/app/get/vo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        mockMvc.perform(get("/app/get/vo").param("id", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        mockMvc.perform(get("/app/get/vo").param("id", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));

        verify(appService, never()).getAppDetailVOByOwner(anyLong(), any());
    }

    @Test
    void ownerDetailReturnsNotFoundForMissingApp() throws Exception {
        given(appService.getAppDetailVOByOwner(402L, loginUser))
                .willThrow(new BusinessException(ErrorCode.NOT_FOUND_ERROR));

        mockMvc.perform(get("/app/get/vo").param("id", "402"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void ownerDetailReturnsNoAuthorityForForeignOwner() throws Exception {
        given(appService.getAppDetailVOByOwner(403L, loginUser))
                .willThrow(new BusinessException(ErrorCode.NO_AUTH_ERROR));

        mockMvc.perform(get("/app/get/vo").param("id", "403"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40101));
    }

    @Test
    void publicFeaturedDetailIsAnonymousAndSanitized() throws Exception {
        PublicAppDetailVO detailVO = createPublicAppDetailVO(404L);
        given(appService.getPublicAppDetailVO(404L)).willReturn(detailVO);

        mockMvc.perform(get("/app/good/get/vo").param("id", "404"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value("404"))
                .andExpect(jsonPath("$.data.appName").value("App 404"))
                .andExpect(jsonPath("$.data.deployUrl")
                        .value("https://apps.example.com/deploy-404/"))
                .andExpect(jsonPath("$.data.initPrompt").doesNotExist())
                .andExpect(jsonPath("$.data.userId").doesNotExist())
                .andExpect(jsonPath("$.data.deployKey").doesNotExist());

        verify(appService).getPublicAppDetailVO(404L);
        verify(userService, never()).getLoginUser(any(HttpServletRequest.class));
    }

    @Test
    void publicFeaturedDetailReturnsNotFoundForNonFeaturedOrMissingApp() throws Exception {
        given(appService.getPublicAppDetailVO(405L))
                .willThrow(new BusinessException(ErrorCode.NOT_FOUND_ERROR));

        mockMvc.perform(get("/app/good/get/vo").param("id", "405"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void publicFeaturedDetailRejectsMissingMalformedAndNonPositiveIds() throws Exception {
        mockMvc.perform(get("/app/good/get/vo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        mockMvc.perform(get("/app/good/get/vo").param("id", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        mockMvc.perform(get("/app/good/get/vo").param("id", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));

        verify(appService, never()).getPublicAppDetailVO(anyLong());
    }

    @Test
    @SuppressWarnings("unchecked")
    void myListUsesDefaultPaginationAndCurrentOwner() throws Exception {
        QueryWrapper<App> queryWrapper = new QueryWrapper<>();
        Page<App> appPage = new Page<>(1, 10, 0);
        appPage.setRecords(List.of());
        Page<AppVO> appVOPage = new Page<>(1, 10, 0);
        appVOPage.setRecords(List.of());
        given(appService.getMyAppQueryWrapper(any(AppNameQueryDTO.class), eq(loginUser.getId())))
                .willReturn(queryWrapper);
        given(appService.page(any(Page.class), same(queryWrapper))).willReturn(appPage);
        given(appService.getAppVOPage(appPage)).willReturn(appVOPage);

        mockMvc.perform(post("/app/my/list/page/vo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current").value("1"))
                .andExpect(jsonPath("$.data.size").value("10"))
                .andExpect(jsonPath("$.data.records").isEmpty());

        ArgumentCaptor<AppNameQueryDTO> queryCaptor =
                ArgumentCaptor.forClass(AppNameQueryDTO.class);
        verify(appService).getMyAppQueryWrapper(
                queryCaptor.capture(), eq(loginUser.getId()));
        assertThat(queryCaptor.getValue().getAppName()).isNull();
        ArgumentCaptor<Page<App>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        verify(appService).page(pageCaptor.capture(), same(queryWrapper));
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(1);
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(10);
    }

    @Test
    @SuppressWarnings("unchecked")
    void myListSupportsNameFilterAndTwentyRecordBoundary() throws Exception {
        App app = createApp(501L);
        AppVO appVO = createAppVO(501L, loginUser.getId());
        QueryWrapper<App> queryWrapper = new QueryWrapper<>();
        Page<App> appPage = new Page<>(2, 20, 21);
        appPage.setRecords(List.of(app));
        Page<AppVO> appVOPage = new Page<>(2, 20, 21);
        appVOPage.setRecords(List.of(appVO));
        given(appService.getMyAppQueryWrapper(any(AppNameQueryDTO.class), eq(loginUser.getId())))
                .willReturn(queryWrapper);
        given(appService.page(any(Page.class), same(queryWrapper))).willReturn(appPage);
        given(appService.getAppVOPage(appPage)).willReturn(appVOPage);

        mockMvc.perform(post("/app/my/list/page/vo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pageNum": 2, "pageSize": 20, "appName": "portfolio"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current").value("2"))
                .andExpect(jsonPath("$.data.size").value("20"))
                .andExpect(jsonPath("$.data.records[0].id").value("501"))
                .andExpect(jsonPath("$.data.records[0].appName").value("App 501"))
                .andExpect(jsonPath("$.data.records[0].initPrompt").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].isDelete").doesNotExist());

        ArgumentCaptor<AppNameQueryDTO> queryCaptor =
                ArgumentCaptor.forClass(AppNameQueryDTO.class);
        verify(appService).getMyAppQueryWrapper(
                queryCaptor.capture(), eq(loginUser.getId()));
        assertThat(queryCaptor.getValue().getAppName()).isEqualTo("portfolio");
    }

    @Test
    void myListRejectsPageSizeAboveTwenty() throws Exception {
        mockMvc.perform(post("/app/my/list/page/vo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pageNum": 1, "pageSize": 21}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));

        verifyNoInteractions(appService);
    }

    @Test
    void myListRejectsNonPositivePagination() throws Exception {
        mockMvc.perform(post("/app/my/list/page/vo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pageNum": 0, "pageSize": 10}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        mockMvc.perform(post("/app/my/list/page/vo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pageNum": 1, "pageSize": 0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));

        verifyNoInteractions(appService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void goodListUsesDefaultPagination() throws Exception {
        QueryWrapper<App> queryWrapper = new QueryWrapper<>();
        Page<App> appPage = new Page<>(1, 10, 0);
        appPage.setRecords(List.of());
        Page<AppVO> appVOPage = new Page<>(1, 10, 0);
        appVOPage.setRecords(List.of());
        given(appService.getGoodAppQueryWrapper(any(AppNameQueryDTO.class)))
                .willReturn(queryWrapper);
        given(appService.page(any(Page.class), same(queryWrapper))).willReturn(appPage);
        given(appService.getAppVOPage(appPage)).willReturn(appVOPage);

        mockMvc.perform(post("/app/good/list/page/vo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current").value("1"))
                .andExpect(jsonPath("$.data.size").value("10"))
                .andExpect(jsonPath("$.data.records").isEmpty());

        ArgumentCaptor<AppNameQueryDTO> queryCaptor =
                ArgumentCaptor.forClass(AppNameQueryDTO.class);
        verify(appService).getGoodAppQueryWrapper(queryCaptor.capture());
        assertThat(queryCaptor.getValue().getPageNum()).isEqualTo(1);
        assertThat(queryCaptor.getValue().getPageSize()).isEqualTo(10);
        ArgumentCaptor<Page<App>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        verify(appService).page(pageCaptor.capture(), same(queryWrapper));
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(1);
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(10);
        verify(userService, never()).getLoginUser(any(HttpServletRequest.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void goodListIsPublicAndSupportsNameFilter() throws Exception {
        App app = createApp(601L);
        AppVO appVO = createAppVO(601L, 9L);
        QueryWrapper<App> queryWrapper = new QueryWrapper<>();
        Page<App> appPage = new Page<>(1, 20, 1);
        appPage.setRecords(List.of(app));
        Page<AppVO> appVOPage = new Page<>(1, 20, 1);
        appVOPage.setRecords(List.of(appVO));
        given(appService.getGoodAppQueryWrapper(any(AppNameQueryDTO.class)))
                .willReturn(queryWrapper);
        given(appService.page(any(Page.class), same(queryWrapper))).willReturn(appPage);
        given(appService.getAppVOPage(appPage)).willReturn(appVOPage);

        mockMvc.perform(post("/app/good/list/page/vo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pageNum": 1, "pageSize": 20, "appName": "featured"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].id").value("601"))
                .andExpect(jsonPath("$.data.records[0].priority").value(99))
                .andExpect(jsonPath("$.data.records[0].initPrompt").doesNotExist());

        ArgumentCaptor<AppNameQueryDTO> queryCaptor =
                ArgumentCaptor.forClass(AppNameQueryDTO.class);
        verify(appService).getGoodAppQueryWrapper(queryCaptor.capture());
        assertThat(queryCaptor.getValue().getAppName()).isEqualTo("featured");
        verify(userService, never()).getLoginUser(any(HttpServletRequest.class));
    }

    @Test
    void goodListRejectsInvalidPagination() throws Exception {
        mockMvc.perform(post("/app/good/list/page/vo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pageNum": 1, "pageSize": 21}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        mockMvc.perform(post("/app/good/list/page/vo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pageNum": -1, "pageSize": 10}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        mockMvc.perform(post("/app/good/list/page/vo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pageNum": 1, "pageSize": 0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));

        verifyNoInteractions(appService);
    }

    @Test
    void adminDeleteDelegatesAnyAppId() throws Exception {
        given(appService.deleteAppByAdmin(701L)).willReturn(true);

        mockMvc.perform(post("/app/admin/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id": 701}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

        verify(appService).deleteAppByAdmin(701L);
    }

    @Test
    void adminDeleteReturnsNotFoundForMissingApp() throws Exception {
        given(appService.deleteAppByAdmin(702L))
                .willThrow(new BusinessException(ErrorCode.NOT_FOUND_ERROR));

        mockMvc.perform(post("/app/admin/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id": 702}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void adminUpdateDelegatesOnlyApprovedFields() throws Exception {
        given(appService.updateAppByAdmin(any(AppAdminUpdateDTO.class))).willReturn(true);

        mockMvc.perform(post("/app/admin/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "id": 801,
                                  "appName": "Featured app",
                                  "cover": "https://example.com/cover.png",
                                  "priority": 99,
                                  "initPrompt": "ignored prompt",
                                  "userId": 999
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

        ArgumentCaptor<AppAdminUpdateDTO> captor =
                ArgumentCaptor.forClass(AppAdminUpdateDTO.class);
        verify(appService).updateAppByAdmin(captor.capture());
        AppAdminUpdateDTO request = captor.getValue();
        assertThat(request.getId()).isEqualTo(801L);
        assertThat(request.getAppName()).isEqualTo("Featured app");
        assertThat(request.getCover()).isEqualTo("https://example.com/cover.png");
        assertThat(request.getPriority()).isEqualTo(99);
    }

    @Test
    void adminUpdateReturnsParameterErrorWhenNoApprovedFieldIsProvided() throws Exception {
        given(appService.updateAppByAdmin(any(AppAdminUpdateDTO.class)))
                .willThrow(new BusinessException(ErrorCode.PARAMS_ERROR,
                        "至少需要提供一个更新字段"));

        mockMvc.perform(post("/app/admin/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id": 802}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void adminDetailReturnsDetailView() throws Exception {
        App app = createApp(901L);
        AppDetailVO detailVO = createDetailVO(901L, 11L);
        given(appService.getById(901L)).willReturn(app);
        given(appService.getAppDetailVO(app)).willReturn(detailVO);

        mockMvc.perform(get("/app/admin/get/vo").param("id", "901"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("901"))
                .andExpect(jsonPath("$.data.initPrompt").value("Build application 901"))
                .andExpect(jsonPath("$.data.isDelete").doesNotExist());
    }

    @Test
    void adminDetailReturnsNotFoundForMissingApp() throws Exception {
        given(appService.getById(902L)).willReturn(null);

        mockMvc.perform(get("/app/admin/get/vo").param("id", "902"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));

        verify(appService, never()).getAppDetailVO(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void adminListDelegatesEverySupportedFilter() throws Exception {
        QueryWrapper<App> queryWrapper = new QueryWrapper<>();
        Page<App> appPage = new Page<>(2, 5, 7);
        appPage.setRecords(List.of(createApp(1001L)));
        Page<AppVO> appVOPage = new Page<>(2, 5, 7);
        appVOPage.setRecords(List.of(createAppVO(1001L, 13L)));
        given(appService.getQueryWrapper(any(AppQueryDTO.class))).willReturn(queryWrapper);
        given(appService.page(any(Page.class), same(queryWrapper))).willReturn(appPage);
        given(appService.getAppVOPage(appPage)).willReturn(appVOPage);

        mockMvc.perform(post("/app/admin/list/page/vo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "pageNum": 2,
                                  "pageSize": 5,
                                  "id": 1001,
                                  "appName": "dashboard",
                                  "cover": "cover.png",
                                  "initPrompt": "build",
                                  "codeGenType": "html",
                                  "deployKey": "deploy-key",
                                  "priority": 99,
                                  "userId": 13
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value("7"))
                .andExpect(jsonPath("$.data.records[0].id").value("1001"))
                .andExpect(jsonPath("$.data.records[0].initPrompt").doesNotExist());

        ArgumentCaptor<AppQueryDTO> queryCaptor = ArgumentCaptor.forClass(AppQueryDTO.class);
        verify(appService).getQueryWrapper(queryCaptor.capture());
        AppQueryDTO query = queryCaptor.getValue();
        assertThat(query.getId()).isEqualTo(1001L);
        assertThat(query.getAppName()).isEqualTo("dashboard");
        assertThat(query.getCover()).isEqualTo("cover.png");
        assertThat(query.getInitPrompt()).isEqualTo("build");
        assertThat(query.getCodeGenType()).isEqualTo("html");
        assertThat(query.getDeployKey()).isEqualTo("deploy-key");
        assertThat(query.getPriority()).isEqualTo(99);
        assertThat(query.getUserId()).isEqualTo(13L);
    }

    @Test
    void everyAdminRouteRejectsOrdinaryUsers() throws Exception {
        User ordinaryUser = createUser(2L, "user");
        given(userService.getLoginUser(any(HttpServletRequest.class))).willReturn(ordinaryUser);

        mockMvc.perform(post("/app/admin/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id": 1}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40101));
        mockMvc.perform(post("/app/admin/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id": 1, "appName": "Updated"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40101));
        mockMvc.perform(get("/app/admin/get/vo").param("id", "1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40101));
        mockMvc.perform(post("/app/admin/list/page/vo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pageNum": 1, "pageSize": 10}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40101));

        verifyNoInteractions(appService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void adminListRetainsPageSizeAboveGlobalLimit() throws Exception {
        QueryWrapper<App> queryWrapper = new QueryWrapper<>();
        Page<App> appPage = new Page<>(3, 101, 202);
        appPage.setRecords(List.of());
        Page<AppVO> appVOPage = new Page<>(3, 101, 202);
        appVOPage.setRecords(List.of());
        given(appService.getQueryWrapper(any(AppQueryDTO.class))).willReturn(queryWrapper);
        given(appService.page(any(Page.class), same(queryWrapper))).willReturn(appPage);
        given(appService.getAppVOPage(appPage)).willReturn(appVOPage);

        mockMvc.perform(post("/app/admin/list/page/vo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pageNum": 3, "pageSize": 101}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current").value("3"))
                .andExpect(jsonPath("$.data.size").value("101"));

        ArgumentCaptor<Page<App>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        verify(appService).page(pageCaptor.capture(), same(queryWrapper));
        Page<App> submittedPage = pageCaptor.getValue();
        assertThat(submittedPage.getCurrent()).isEqualTo(3);
        assertThat(submittedPage.getSize()).isEqualTo(101);
        assertThat(submittedPage.maxLimit()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void adminListRejectsNonPositivePagination() throws Exception {
        mockMvc.perform(post("/app/admin/list/page/vo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pageNum": 0, "pageSize": 101}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        mockMvc.perform(post("/app/admin/list/page/vo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pageNum": 1, "pageSize": -1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));

        verifyNoInteractions(appService);
    }

    private static User createUser(Long id, String role) {
        User user = new User();
        user.setId(id);
        user.setUserAccount(role + "Account");
        user.setUserName(role + " user");
        user.setUserRole(role);
        return user;
    }

    private static App createApp(Long id) {
        App app = new App();
        app.setId(id);
        return app;
    }

    private static AppVO createAppVO(Long id, Long userId) {
        AppVO appVO = new AppVO();
        appVO.setId(id);
        appVO.setAppName("App " + id);
        appVO.setCover("cover-" + id + ".png");
        appVO.setCodeGenType("html");
        appVO.setDeployKey("deploy-" + id);
        appVO.setDeployUrl("https://apps.example.com/deploy-" + id + "/");
        appVO.setPriority(99);
        appVO.setUserId(userId);
        return appVO;
    }

    private static AppDetailVO createDetailVO(Long id, Long userId) {
        AppDetailVO appDetailVO = new AppDetailVO();
        appDetailVO.setId(id);
        appDetailVO.setAppName("App " + id);
        appDetailVO.setCover("cover-" + id + ".png");
        appDetailVO.setInitPrompt("Build application " + id);
        appDetailVO.setCodeGenType("html");
        appDetailVO.setDeployKey("deploy-" + id);
        appDetailVO.setDeployUrl("https://apps.example.com/deploy-" + id + "/");
        appDetailVO.setPriority(99);
        appDetailVO.setUserId(userId);
        return appDetailVO;
    }

    private static PublicAppDetailVO createPublicAppDetailVO(Long id) {
        PublicAppDetailVO appDetailVO = new PublicAppDetailVO();
        appDetailVO.setId(id);
        appDetailVO.setAppName("App " + id);
        appDetailVO.setCover("cover-" + id + ".png");
        appDetailVO.setCodeGenType("html");
        appDetailVO.setDeployUrl("https://apps.example.com/deploy-" + id + "/");
        appDetailVO.setDeployedTime(new Date());
        return appDetailVO;
    }

    private static AppPreviewVO preview(String previewUrl, long expiresAt) {
        AppPreviewVO preview = new AppPreviewVO();
        preview.setPreviewUrl(previewUrl);
        preview.setExpiresAt(expiresAt);
        return preview;
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class AopTestConfiguration {
    }
}
