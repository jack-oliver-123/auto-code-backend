package com.jack.autocodebackend.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.jack.autocodebackend.exception.BusinessException;
import com.jack.autocodebackend.mapper.UserMapper;
import com.jack.autocodebackend.model.domain.User;
import com.jack.autocodebackend.model.dto.UserQueryDTO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class UserServiceImplTest {

    private final UserMapper userMapper = mock(UserMapper.class);

    private final HttpServletRequest request = mock(HttpServletRequest.class);

    private final HttpSession session = mock(HttpSession.class);

    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        userService = new UserServiceImpl();
        ReflectionTestUtils.setField(userService, "baseMapper", userMapper);
        given(request.getSession()).willReturn(session);
        given(request.getSession(false)).willReturn(session);
        given(request.getSession(true)).willReturn(session);
    }

    @Test
    void encodePasswordSupportsValuesLongerThanBcryptLimit() {
        String password = "a".repeat(73);

        String encoded = userService.encodePassword(password);

        assertThat(userService.matchesPassword(password, encoded)).isTrue();
    }

    @Test
    void encodePasswordUsesSaltedCurrentFormat() {
        String first = userService.encodePassword("12345678");
        String second = userService.encodePassword("12345678");

        assertThat(first).startsWith("{pbkdf2}");
        assertThat(second).startsWith("{pbkdf2}");
        assertThat(first).isNotEqualTo(second);
        assertThat(userService.matchesPassword("12345678", first)).isTrue();
    }

    @Test
    void userLoginAcceptsCurrentPasswordAndRotatesExistingSession() {
        User user = createUser(userService.encodePassword("12345678"));
        given(userMapper.selectOne(any(QueryWrapper.class))).willReturn(user);

        userService.userLogin("testAccount", "12345678", request);

        verify(request).changeSessionId();
        verify(session).setAttribute("user_login", user);
        verify(userMapper, never()).updateById(org.mockito.ArgumentMatchers.<User>any());
    }

    @Test
    void userLoginCreatesFreshSessionWhenNoneExists() {
        HttpServletRequest freshRequest = mock(HttpServletRequest.class);
        HttpSession freshSession = mock(HttpSession.class);
        User user = createUser(userService.encodePassword("12345678"));
        given(userMapper.selectOne(any(QueryWrapper.class))).willReturn(user);
        given(freshRequest.getSession(false)).willReturn(null);
        given(freshRequest.getSession(true)).willReturn(freshSession);

        userService.userLogin("testAccount", "12345678", freshRequest);

        verify(freshRequest, never()).changeSessionId();
        verify(freshSession).setAttribute("user_login", user);
    }

    @Test
    void userLoginUpgradesBareBcryptPassword() {
        User user = createUser(new BCryptPasswordEncoder().encode("12345678"));
        given(userMapper.selectOne(any(QueryWrapper.class))).willReturn(user);
        given(userMapper.updateById(org.mockito.ArgumentMatchers.<User>any())).willReturn(1);

        userService.userLogin("testAccount", "12345678", request);

        verify(userMapper).updateById(argThat((User update) ->
                update.getId().equals(user.getId())
                        && update.getUserPassword().startsWith("{pbkdf2}")
                        && userService.matchesPassword("12345678", update.getUserPassword())));
    }

    @Test
    void userLoginUpgradesLegacyMd5Password() {
        String legacyPassword = userService.getEncryptPassword("12345678");
        User user = createUser(legacyPassword);
        given(userMapper.selectOne(any(QueryWrapper.class))).willReturn(user);
        given(userMapper.updateById(org.mockito.ArgumentMatchers.<User>any())).willReturn(1);

        userService.userLogin("testAccount", "12345678", request);

        verify(userMapper).updateById(argThat((User update) ->
                update.getId().equals(user.getId())
                        && update.getUserPassword().startsWith("{pbkdf2}")
                        && userService.matchesPassword("12345678", update.getUserPassword())));
        assertThat(user.getUserPassword()).startsWith("{pbkdf2}");
        verify(session).setAttribute("user_login", user);
    }

    @Test
    void userLoginMigratesLongLegacyPassword() {
        String password = "long-password-" + "密".repeat(30);
        User user = createUser(userService.getEncryptPassword(password));
        given(userMapper.selectOne(any(QueryWrapper.class))).willReturn(user);
        given(userMapper.updateById(org.mockito.ArgumentMatchers.<User>any())).willReturn(1);

        userService.userLogin("testAccount", password, request);

        assertThat(user.getUserPassword()).startsWith("{pbkdf2}");
        assertThat(userService.matchesPassword(password, user.getUserPassword())).isTrue();
    }

    @Test
    void userLoginRejectsWrongPassword() {
        User user = createUser(userService.encodePassword("correctPassword"));
        given(userMapper.selectOne(any(QueryWrapper.class))).willReturn(user);

        assertThatThrownBy(() -> userService.userLogin("testAccount", "wrongPassword", request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("用户不存在或密码错误");

        verify(session, never()).setAttribute(any(), any());
    }

    @Test
    void generateInitialPasswordCreatesDistinctStrongValues() {
        String first = userService.generateInitialPassword();
        String second = userService.generateInitialPassword();

        assertThat(first).hasSize(16);
        assertThat(second).hasSize(16);
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void createUserByAdminMarksInitialPasswordAsTemporaryAndDefaultsRole() {
        User user = new User();
        user.setUserAccount("createdAccount");
        user.setUserName("新用户");
        given(userMapper.selectCount(any(QueryWrapper.class))).willReturn(0L);
        given(userMapper.insert(any(User.class))).willAnswer(invocation -> {
            User inserted = invocation.getArgument(0);
            inserted.setId(2001L);
            return 1;
        });

        var result = userService.createUserByAdmin(user);

        assertThat(user.getUserRole()).isEqualTo("user");
        assertThat(user.getUserPassword()).startsWith("{temporary}{pbkdf2}");
        assertThat(userService.matchesPassword(result.getInitialPassword(), user.getUserPassword())).isTrue();
        assertThat(userService.getLoginUserVO(user).getNeedChangePassword()).isTrue();
    }

    @Test
    void changePasswordClearsTemporaryStateAndRefreshesCurrentSession() {
        String temporaryPassword = "{temporary}" + userService.encodePassword("Initial-Password-1!");
        User sessionUser = createUser(temporaryPassword);
        User databaseUser = createUser(temporaryPassword);
        given(session.getAttribute("user_login")).willReturn(sessionUser);
        given(userMapper.selectById(sessionUser.getId())).willReturn(databaseUser);
        given(userMapper.updateById(org.mockito.ArgumentMatchers.<User>any())).willReturn(1);

        boolean changed = userService.changePassword(
                "Initial-Password-1!", "New-Password-2!", "New-Password-2!", request);

        assertThat(changed).isTrue();
        assertThat(databaseUser.getUserPassword()).startsWith("{pbkdf2}");
        assertThat(userService.requiresPasswordChange(databaseUser)).isFalse();
        assertThat(userService.matchesPassword("New-Password-2!", databaseUser.getUserPassword())).isTrue();
        assertThat(userService.matchesPassword("Initial-Password-1!", databaseUser.getUserPassword())).isFalse();
        verify(request).changeSessionId();
        verify(session).setAttribute("user_login", databaseUser);
    }

    @Test
    void createUserByAdminRejectsAccountThatCannotLogIn() {
        User user = new User();
        user.setUserAccount("abc");
        user.setUserRole("user");

        assertThatThrownBy(() -> userService.createUserByAdmin(user))
                .isInstanceOf(BusinessException.class)
                .hasMessage("用户账号长度应为 4 到 256 位");

        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void createUserByAdminRejectsUnknownRole() {
        User user = new User();
        user.setUserAccount("createdAccount");
        user.setUserRole("super-admin");

        assertThatThrownBy(() -> userService.createUserByAdmin(user))
                .isInstanceOf(BusinessException.class)
                .hasMessage("用户角色不合法");

        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void updateUserByAdminRejectsUnknownRole() {
        User user = new User();
        user.setId(2001L);
        user.setUserRole("");

        assertThatThrownBy(() -> userService.updateUserByAdmin(user))
                .isInstanceOf(BusinessException.class)
                .hasMessage("用户角色不合法");

        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    void userRegisterMapsUniqueConstraintConflictToDuplicateAccount() {
        given(userMapper.selectCount(any(QueryWrapper.class))).willReturn(0L);
        given(userMapper.insert(any(User.class))).willThrow(new DuplicateKeyException("duplicate"));

        assertThatThrownBy(() -> userService.userRegister("testAccount", "12345678", "12345678"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("账号重复");
    }

    @Test
    void getLoginUserRejectsSessionCreatedWithOldPasswordVersion() {
        User sessionUser = createUser(userService.encodePassword("oldPassword"));
        User databaseUser = createUser(userService.encodePassword("newPassword"));
        given(session.getAttribute("user_login")).willReturn(sessionUser);
        given(userMapper.selectById(sessionUser.getId())).willReturn(databaseUser);

        assertThatThrownBy(() -> userService.getLoginUser(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("未登录");

        verify(session).invalidate();
    }

    @Test
    void getLoginUserReturnsDatabaseUserWhenPasswordVersionMatches() {
        String password = userService.encodePassword("12345678");
        User sessionUser = createUser(password);
        User databaseUser = createUser(password);
        databaseUser.setUserName("最新昵称");
        given(session.getAttribute("user_login")).willReturn(sessionUser);
        given(userMapper.selectById(sessionUser.getId())).willReturn(databaseUser);

        assertThat(userService.getLoginUser(request)).isSameAs(databaseUser);

        verify(session, never()).invalidate();
    }

    @Test
    void getLoginUserDoesNotCreateSessionForAnonymousRequest() {
        HttpServletRequest anonymousRequest = mock(HttpServletRequest.class);
        given(anonymousRequest.getSession(false)).willReturn(null);

        assertThatThrownBy(() -> userService.getLoginUser(anonymousRequest))
                .isInstanceOf(BusinessException.class)
                .hasMessage("未登录");

        verify(anonymousRequest, never()).getSession();
        verify(anonymousRequest, never()).getSession(true);
    }

    @Test
    void userLogoutInvalidatesSession() {
        given(session.getAttribute("user_login")).willReturn(createUser(userService.encodePassword("12345678")));

        assertThat(userService.userLogout(request)).isTrue();

        verify(session).invalidate();
        verify(session, never()).removeAttribute("user_login");
    }

    @Test
    void getQueryWrapperAllowsKnownSortField() {
        UserQueryDTO request = new UserQueryDTO();
        request.setSortField("createTime");
        request.setSortOrder("ascend");

        QueryWrapper<User> wrapper = userService.getQueryWrapper(request);

        assertThat(wrapper.getSqlSegment()).contains("ORDER BY createTime ASC");
    }

    @Test
    void getQueryWrapperDropsUntrustedSortField() {
        UserQueryDTO request = new UserQueryDTO();
        request.setSortField("id desc; drop table user");
        request.setSortOrder("ascend");

        QueryWrapper<User> wrapper = userService.getQueryWrapper(request);

        assertThat(wrapper.getSqlSegment()).doesNotContain("drop table").doesNotContain("ORDER BY");
    }

    private static User createUser(String password) {
        User user = new User();
        user.setId(1001L);
        user.setUserAccount("testAccount");
        user.setUserPassword(password);
        user.setUserRole("user");
        return user;
    }
}
