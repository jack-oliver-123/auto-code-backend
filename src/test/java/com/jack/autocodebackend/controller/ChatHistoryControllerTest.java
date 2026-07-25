package com.jack.autocodebackend.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jack.autocodebackend.aop.AuthInterceptor;
import com.jack.autocodebackend.config.JsonConfig;
import com.jack.autocodebackend.exception.BusinessException;
import com.jack.autocodebackend.exception.ErrorCode;
import com.jack.autocodebackend.model.domain.User;
import com.jack.autocodebackend.model.dto.ChatHistoryAdminQueryDTO;
import com.jack.autocodebackend.model.dto.ChatHistoryCursorQueryDTO;
import com.jack.autocodebackend.model.vo.ChatHistoryCursorPageVO;
import com.jack.autocodebackend.model.vo.ChatHistoryVO;
import com.jack.autocodebackend.service.ChatHistoryService;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatHistoryController.class)
@Import({AuthInterceptor.class, JsonConfig.class,
        ChatHistoryControllerTest.AopTestConfiguration.class})
class ChatHistoryControllerTest {

    private static final long APP_ID = 2001L;

    private static final long OWNER_ID = 1001L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatHistoryService chatHistoryService;

    @MockitoBean
    private UserService userService;

    private User loginUser;

    @BeforeEach
    void allowAuthenticatedRequests() {
        loginUser = user(OWNER_ID, "user");
        given(userService.getLoginUser(any(HttpServletRequest.class))).willReturn(loginUser);
    }

    @Test
    void ownerCursorEndpointReturnsChronologicalPageAndDelegatesLoginUser() throws Exception {
        ChatHistoryCursorPageVO result = new ChatHistoryCursorPageVO();
        result.setRecords(List.of(history(91L, "first", "user"),
                history(92L, "answer", "ai")));
        result.setHasMore(true);
        result.setNextCursor(91L);
        given(chatHistoryService.listAppHistory(
                any(ChatHistoryCursorQueryDTO.class), same(loginUser))).willReturn(result);

        mockMvc.perform(post("/chatHistory/list/page/vo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"appId": 2001, "beforeId": 101, "pageSize": 10}
                                """))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.records[0].id").value("91"))
                .andExpect(jsonPath("$.data.records[0].message").value("first"))
                .andExpect(jsonPath("$.data.records[0].messageType").value("user"))
                .andExpect(jsonPath("$.data.records[1].messageType").value("ai"))
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.data.nextCursor").value("91"))
                .andExpect(jsonPath("$.data.records[0].updateTime").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].isDelete").doesNotExist());

        ArgumentCaptor<ChatHistoryCursorQueryDTO> captor =
                ArgumentCaptor.forClass(ChatHistoryCursorQueryDTO.class);
        verify(chatHistoryService).listAppHistory(captor.capture(), same(loginUser));
        assertThat(captor.getValue().getAppId()).isEqualTo(APP_ID);
        assertThat(captor.getValue().getBeforeId()).isEqualTo(101L);
        assertThat(captor.getValue().getPageSize()).isEqualTo(10);
    }

    @Test
    void cursorEndpointMapsInvalidForeignAndMissingRequests() throws Exception {
        given(chatHistoryService.listAppHistory(
                any(ChatHistoryCursorQueryDTO.class), same(loginUser)))
                .willThrow(new BusinessException(ErrorCode.PARAMS_ERROR))
                .willThrow(new BusinessException(ErrorCode.NO_AUTH_ERROR))
                .willThrow(new BusinessException(ErrorCode.NOT_FOUND_ERROR));

        performCursorRequest().andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        performCursorRequest().andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40101));
        performCursorRequest().andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void cursorEndpointRejectsNullBodyBeforeCallingService() throws Exception {
        mockMvc.perform(post("/chatHistory/list/page/vo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("null"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));

        verifyNoInteractions(chatHistoryService);
    }

    @Test
    void historyEndpointsRejectAnonymousAndInitialPasswordUsers() throws Exception {
        given(userService.getLoginUser(any(HttpServletRequest.class)))
                .willThrow(new BusinessException(ErrorCode.NOT_LOGIN_ERROR));

        performCursorRequest().andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
        verifyNoInteractions(chatHistoryService);

        org.mockito.Mockito.reset(userService);
        given(userService.getLoginUser(any(HttpServletRequest.class))).willReturn(loginUser);
        given(userService.requiresPasswordChange(loginUser)).willReturn(true);

        performCursorRequest().andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
        verifyNoInteractions(chatHistoryService);
    }

    @Test
    void administratorEndpointDelegatesFiltersAndReturnsNewestFirstPage() throws Exception {
        User admin = user(9L, "admin");
        given(userService.getLoginUser(any(HttpServletRequest.class))).willReturn(admin);
        Page<ChatHistoryVO> page = new Page<>(2, 5, 11);
        page.setRecords(List.of(history(500L, "latest", "ai")));
        given(chatHistoryService.listAllHistoryByAdmin(
                any(ChatHistoryAdminQueryDTO.class))).willReturn(page);

        mockMvc.perform(post("/chatHistory/admin/list/page/vo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "pageNum": 2,
                                  "pageSize": 5,
                                  "appId": 2001,
                                  "userId": 1001,
                                  "messageType": "ai"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current").value("2"))
                .andExpect(jsonPath("$.data.size").value("5"))
                .andExpect(jsonPath("$.data.total").value("11"))
                .andExpect(jsonPath("$.data.records[0].id").value("500"))
                .andExpect(jsonPath("$.data.records[0].messageType").value("ai"))
                .andExpect(jsonPath("$.data.records[0].isDelete").doesNotExist());

        ArgumentCaptor<ChatHistoryAdminQueryDTO> captor =
                ArgumentCaptor.forClass(ChatHistoryAdminQueryDTO.class);
        verify(chatHistoryService).listAllHistoryByAdmin(captor.capture());
        ChatHistoryAdminQueryDTO query = captor.getValue();
        assertThat(query.getPageNum()).isEqualTo(2);
        assertThat(query.getPageSize()).isEqualTo(5);
        assertThat(query.getAppId()).isEqualTo(APP_ID);
        assertThat(query.getUserId()).isEqualTo(OWNER_ID);
        assertThat(query.getMessageType()).isEqualTo("ai");
    }

    @Test
    void administratorEndpointRejectsOrdinaryAndInitialPasswordAdministrators() throws Exception {
        mockMvc.perform(post("/chatHistory/admin/list/page/vo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pageNum\":1,\"pageSize\":10}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40101));
        verifyNoInteractions(chatHistoryService);

        User admin = user(9L, "admin");
        given(userService.getLoginUser(any(HttpServletRequest.class))).willReturn(admin);
        given(userService.requiresPasswordChange(admin)).willReturn(true);

        mockMvc.perform(post("/chatHistory/admin/list/page/vo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pageNum\":1,\"pageSize\":10}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
        verifyNoInteractions(chatHistoryService);
    }

    @Test
    void administratorEndpointRejectsNullBodyAndServiceValidationErrors() throws Exception {
        User admin = user(9L, "admin");
        given(userService.getLoginUser(any(HttpServletRequest.class))).willReturn(admin);

        mockMvc.perform(post("/chatHistory/admin/list/page/vo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("null"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));

        given(chatHistoryService.listAllHistoryByAdmin(
                any(ChatHistoryAdminQueryDTO.class)))
                .willThrow(new BusinessException(ErrorCode.PARAMS_ERROR));
        mockMvc.perform(post("/chatHistory/admin/list/page/vo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pageNum\":1,\"pageSize\":101}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    private org.springframework.test.web.servlet.ResultActions performCursorRequest()
            throws Exception {
        return mockMvc.perform(post("/chatHistory/list/page/vo")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"appId\":2001,\"pageSize\":10}"));
    }

    private static ChatHistoryVO history(Long id, String message, String messageType) {
        ChatHistoryVO history = new ChatHistoryVO();
        history.setId(id);
        history.setMessage(message);
        history.setMessageType(messageType);
        history.setAppId(APP_ID);
        history.setUserId(OWNER_ID);
        history.setCreateTime(new Date(1_700_000_000_000L + id));
        return history;
    }

    private static User user(Long id, String role) {
        User user = new User();
        user.setId(id);
        user.setUserRole(role);
        return user;
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class AopTestConfiguration {
    }
}
