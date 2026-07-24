package com.jack.autocodebackend.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jack.autocodebackend.aop.AuthInterceptor;
import com.jack.autocodebackend.config.JsonConfig;
import com.jack.autocodebackend.model.domain.User;
import com.jack.autocodebackend.model.dto.UserQueryDTO;
import com.jack.autocodebackend.model.vo.UserLoginVO;
import com.jack.autocodebackend.model.vo.UserVO;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@Import({AuthInterceptor.class, JsonConfig.class, UserControllerTest.AopTestConfiguration.class})
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @BeforeEach
    void allowAdminRequests() {
        User admin = createUser(1L, "adminAccount", "管理员", "admin");
        given(userService.getLoginUser(any(HttpServletRequest.class))).willReturn(admin);
    }

    @Test
    void userRegisterReturnsNewUserId() throws Exception {
        given(userService.userRegister("testAccount", "12345678", "12345678"))
                .willReturn(101L);

        mockMvc.perform(post("/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userAccount": "testAccount",
                                  "userPassword": "12345678",
                                  "checkPassword": "12345678"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value("101"))
                .andExpect(jsonPath("$.message").value("ok"));

        verify(userService).userRegister("testAccount", "12345678", "12345678");
    }

    @Test
    void userRegisterTreatsNullJsonAsUnreadableBody() throws Exception {
        mockMvc.perform(post("/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("null"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(50000))
                .andExpect(jsonPath("$.message").value("系统错误"));

        verify(userService, never()).userRegister(any(), any(), any());
    }

    @Test
    void userLoginReturnsLoginUser() throws Exception {
        UserLoginVO loginUser = createLoginUserVO(201L, "testAccount", "测试用户", "user");
        given(userService.userLogin(eq("testAccount"), eq("12345678"), any(HttpServletRequest.class)))
                .willReturn(loginUser);

        mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userAccount": "testAccount",
                                  "userPassword": "12345678"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value("201"))
                .andExpect(jsonPath("$.data.userAccount").value("testAccount"))
                .andExpect(jsonPath("$.data.userName").value("测试用户"));

        verify(userService).userLogin(eq("testAccount"), eq("12345678"), any(HttpServletRequest.class));
    }

    @Test
    void getLoginUserReturnsCurrentUser() throws Exception {
        User currentUser = createUser(202L, "currentAccount", "当前用户", "user");
        UserLoginVO loginUserVO = createLoginUserVO(202L, "currentAccount", "当前用户", "user");
        given(userService.getLoginUser(any(HttpServletRequest.class))).willReturn(currentUser);
        given(userService.getLoginUserVO(currentUser)).willReturn(loginUserVO);

        mockMvc.perform(get("/user/get/login"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value("202"))
                .andExpect(jsonPath("$.data.userRole").value("user"));

        verify(userService).getLoginUser(any(HttpServletRequest.class));
        verify(userService).getLoginUserVO(currentUser);
    }

    @Test
    void userLogoutReturnsServiceResult() throws Exception {
        given(userService.userLogout(any(HttpServletRequest.class))).willReturn(true);

        mockMvc.perform(post("/user/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(true));

        verify(userService).userLogout(any(HttpServletRequest.class));
    }

    @Test
    void addUserUsesEncryptedDefaultPassword() throws Exception {
        given(userService.getEncryptPassword("12345678")).willReturn("encrypted-password");
        given(userService.save(any(User.class))).willAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(301L);
            return true;
        });

        mockMvc.perform(post("/user/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userName": "新增用户",
                                  "userAccount": "addedAccount",
                                  "userProfile": "测试简介",
                                  "userRole": "user"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value("301"));

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userService).save(userCaptor.capture());
        User savedUser = userCaptor.getValue();
        assertThat(savedUser.getUserAccount()).isEqualTo("addedAccount");
        assertThat(savedUser.getUserName()).isEqualTo("新增用户");
        assertThat(savedUser.getUserPassword()).isEqualTo("encrypted-password");
    }

    @Test
    void getUserByIdReturnsUserForAdmin() throws Exception {
        User user = createUser(401L, "queriedAccount", "查询用户", "user");
        given(userService.getById(401L)).willReturn(user);

        mockMvc.perform(get("/user/get").param("id", "401"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value("401"))
                .andExpect(jsonPath("$.data.userAccount").value("queriedAccount"));

        verify(userService).getById(401L);
    }

    @Test
    void getUserByIdReturnsNotFoundWhenUserDoesNotExist() throws Exception {
        given(userService.getById(999L)).willReturn(null);

        mockMvc.perform(get("/user/get").param("id", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40400))
                .andExpect(jsonPath("$.message").value("请求数据不存在"));
    }

    @Test
    void getUserVOByIdReturnsSanitizedUser() throws Exception {
        User user = createUser(402L, "voAccount", "VO 用户", "user");
        user.setUserPassword("must-not-be-returned");
        UserVO userVO = createUserVO(402L, "voAccount", "VO 用户", "user");
        given(userService.getById(402L)).willReturn(user);
        given(userService.getUserVO(user)).willReturn(userVO);

        mockMvc.perform(get("/user/get/vo").param("id", "402"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value("402"))
                .andExpect(jsonPath("$.data.userName").value("VO 用户"))
                .andExpect(jsonPath("$.data.userPassword").doesNotExist());

        verify(userService).getUserVO(user);
    }

    @Test
    void deleteUserReturnsDeleteResult() throws Exception {
        given(userService.removeById(501L)).willReturn(true);

        mockMvc.perform(post("/user/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id": 501}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(true));

        verify(userService).removeById(501L);
    }

    @Test
    void updateUserCopiesRequestFields() throws Exception {
        given(userService.updateById(any(User.class))).willReturn(true);

        mockMvc.perform(post("/user/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "id": 601,
                                  "userName": "更新用户",
                                  "userProfile": "更新后的简介",
                                  "userRole": "admin"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(true));

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userService).updateById(userCaptor.capture());
        User updatedUser = userCaptor.getValue();
        assertThat(updatedUser.getId()).isEqualTo(601L);
        assertThat(updatedUser.getUserName()).isEqualTo("更新用户");
        assertThat(updatedUser.getUserProfile()).isEqualTo("更新后的简介");
        assertThat(updatedUser.getUserRole()).isEqualTo("admin");
    }

    @Test
    @SuppressWarnings("unchecked")
    void listUserVOByPageReturnsConvertedPage() throws Exception {
        User user = createUser(701L, "pageAccount", "分页用户", "user");
        UserVO userVO = createUserVO(701L, "pageAccount", "分页用户", "user");
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        Page<User> userPage = new Page<>(2, 5, 11);
        userPage.setRecords(List.of(user));
        given(userService.getQueryWrapper(any(UserQueryDTO.class))).willReturn(queryWrapper);
        given(userService.page(any(Page.class), same(queryWrapper))).willReturn(userPage);
        given(userService.getUserVOList(List.of(user))).willReturn(List.of(userVO));

        mockMvc.perform(post("/user/list/page/vo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "pageNum": 2,
                                  "pageSize": 5,
                                  "userName": "分页用户"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.current").value("2"))
                .andExpect(jsonPath("$.data.size").value("5"))
                .andExpect(jsonPath("$.data.total").value("11"))
                .andExpect(jsonPath("$.data.records[0].id").value("701"))
                .andExpect(jsonPath("$.data.records[0].userName").value("分页用户"));

        ArgumentCaptor<Page<User>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        verify(userService).page(pageCaptor.capture(), same(queryWrapper));
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(2);
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(5);
    }

    @Test
    void adminEndpointRejectsOrdinaryUser() throws Exception {
        User ordinaryUser = createUser(2L, "userAccount", "普通用户", "user");
        given(userService.getLoginUser(any(HttpServletRequest.class))).willReturn(ordinaryUser);

        mockMvc.perform(get("/user/get").param("id", "401"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40101))
                .andExpect(jsonPath("$.message").value("无权限"));

        verify(userService, never()).getById(401L);
    }

    private static User createUser(Long id, String account, String name, String role) {
        User user = new User();
        user.setId(id);
        user.setUserAccount(account);
        user.setUserName(name);
        user.setUserRole(role);
        return user;
    }

    private static UserLoginVO createLoginUserVO(Long id, String account, String name, String role) {
        UserLoginVO user = new UserLoginVO();
        user.setId(id);
        user.setUserAccount(account);
        user.setUserName(name);
        user.setUserRole(role);
        return user;
    }

    private static UserVO createUserVO(Long id, String account, String name, String role) {
        UserVO user = new UserVO();
        user.setId(id);
        user.setUserAccount(account);
        user.setUserName(name);
        user.setUserRole(role);
        return user;
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class AopTestConfiguration {
    }
}
