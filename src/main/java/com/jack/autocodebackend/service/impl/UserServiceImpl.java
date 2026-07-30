package com.jack.autocodebackend.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.jack.autocodebackend.exception.BusinessException;
import com.jack.autocodebackend.exception.ErrorCode;
import com.jack.autocodebackend.mapper.UserMapper;
import com.jack.autocodebackend.model.domain.User;
import com.jack.autocodebackend.model.dto.UserQueryDTO;
import com.jack.autocodebackend.model.enums.UserRoleEnum;
import com.jack.autocodebackend.model.session.AuthenticatedSession;
import com.jack.autocodebackend.model.vo.UserAddResultVO;
import com.jack.autocodebackend.model.vo.UserLoginVO;
import com.jack.autocodebackend.model.vo.UserPasswordResetResultVO;
import com.jack.autocodebackend.model.vo.UserVO;
import com.jack.autocodebackend.service.UserService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.jack.autocodebackend.constant.UserConstant.USER_LOGIN_STATE;

/**
* @author qianwen.cui
* @description 针对表【user(用户)】的数据库操作Service实现
* @createDate 2026-07-23 13:21:51
*/
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User>
    implements UserService{

    private static final String LEGACY_PASSWORD_SALT = "Jack";

    private static final Pattern LEGACY_MD5_PATTERN = Pattern.compile("^[a-f0-9]{32}$");

    private static final String CURRENT_PASSWORD_PREFIX = "{pbkdf2}";

    private static final String TEMPORARY_PASSWORD_PREFIX = "{temporary}";

    private static final String INITIAL_PASSWORD_CHARACTERS =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%";

    private static final int INITIAL_PASSWORD_LENGTH = 16;

    private static final int MIN_ACCOUNT_LENGTH = 4;

    private static final int MAX_ACCOUNT_LENGTH = 256;

    private static final int MIN_PASSWORD_LENGTH = 8;

    private static final Map<String, String> SORT_FIELD_MAP = Map.of(
            "id", "id",
            "userAccount", "userAccount",
            "userName", "userName",
            "userRole", "userRole",
            "createTime", "createTime",
            "updateTime", "updateTime"
    );

    private final PasswordEncoder currentPasswordEncoder =
            Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    private final PasswordEncoder legacyBcryptPasswordEncoder = new BCryptPasswordEncoder();

    private final SecureRandom secureRandom = new SecureRandom();

    private final SessionRepository<?> sessionRepository;

    public UserServiceImpl(SessionRepository<?> sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    @Override
    public long userRegister(String userAccount, String userPassword, String checkPassword) {
        validateUserAccount(userAccount);
        validatePassword(userPassword);
        validatePassword(checkPassword);
        if (!userPassword.equals(checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "两次输入的密码不一致");
        }

        ensureActiveAccountAvailable(userAccount);

        User user = new User();
        user.setUserAccount(userAccount);
        user.setUserPassword(encodePassword(userPassword));
        user.setUserName("无名");
        user.setUserRole(UserRoleEnum.USER.getValue());
        saveNewUser(user, ErrorCode.SYSTEM_ERROR, "注册失败，数据库错误");
        return user.getId();
    }

    @Override
    public UserLoginVO userLogin(String userAccount, String userPassword, HttpServletRequest request) {
        validateUserAccount(userAccount);
        validatePassword(userPassword);
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("userAccount", userAccount);
        User user = this.baseMapper.selectOne(queryWrapper);
        if (user == null || !matchesPassword(userPassword, user.getUserPassword())) {
            log.debug("user login failed, userAccount cannot match userPassword");
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户不存在或密码错误");
        }
        if (needsPasswordUpgrade(user.getUserPassword())) {
            user = upgradePassword(user, userPassword);
        }
        establishAuthenticatedSession(request, user);
        return this.getLoginUserVO(user);
    }


    @Override
    public User getLoginUser(HttpServletRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        HttpSession session;
        Object loginState;
        try {
            session = request.getSession(false);
            loginState = session == null
                    ? null
                    : session.getAttribute(USER_LOGIN_STATE);
        } catch (SerializationException malformedSession) {
            deleteMalformedSession(request, malformedSession);
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        if (session == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        if (!(loginState instanceof AuthenticatedSession authenticatedSession)) {
            log.debug("Rejected authenticated session with an unsupported login-state format");
            session.invalidate();
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }

        User currentUser = this.getById(authenticatedSession.userId());
        if (currentUser == null) {
            log.debug("Rejected authenticated session because the account is unavailable");
            session.invalidate();
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        if (!authenticatedSession.matchesCredential(currentUser.getUserPassword())) {
            log.debug("Rejected authenticated session after credential version changed");
            session.invalidate();
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        return currentUser;
    }

    private void deleteMalformedSession(
            HttpServletRequest request,
            SerializationException malformedSession
    ) {
        String sessionId = request.getRequestedSessionId();
        if (sessionId != null && !sessionId.isBlank()) {
            try {
                sessionRepository.deleteById(sessionId);
            } catch (RuntimeException deletionFailure) {
                malformedSession.addSuppressed(deletionFailure);
                log.warn("Failed to delete malformed distributed session");
            }
        }
        log.debug("Rejected authenticated session with malformed serialized state");
    }

    @Override
    public UserLoginVO getLoginUserVO(User user) {
        if (user == null) {
            return null;
        }
        UserLoginVO loginUserVO = new UserLoginVO();
        BeanUtils.copyProperties(user, loginUserVO);
        loginUserVO.setNeedChangePassword(requiresPasswordChange(user));
        return loginUserVO;
    }

    @Override
    public boolean userLogout(HttpServletRequest request) {
        HttpSession session = request == null ? null : request.getSession(false);
        if (session == null || session.getAttribute(USER_LOGIN_STATE) == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        session.invalidate();
        return true;
    }

    @Override
    public String encodePassword(String userPassword) {
        return CURRENT_PASSWORD_PREFIX + currentPasswordEncoder.encode(userPassword);
    }

    @Override
    public boolean matchesPassword(String userPassword, String storedPassword) {
        if (StrUtil.isBlank(userPassword) || StrUtil.isBlank(storedPassword)) {
            return false;
        }
        String encodedPassword = unwrapTemporaryPassword(storedPassword);
        try {
            if (encodedPassword.startsWith(CURRENT_PASSWORD_PREFIX)) {
                return currentPasswordEncoder.matches(userPassword,
                        encodedPassword.substring(CURRENT_PASSWORD_PREFIX.length()));
            }
            if (isLegacyPassword(encodedPassword)) {
                return getEncryptPassword(userPassword).equals(encodedPassword);
            }
            if (encodedPassword.startsWith("$2")) {
                return legacyBcryptPasswordEncoder.matches(userPassword, encodedPassword);
            }
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        return false;
    }

    @Override
    public String getEncryptPassword(String userPassword) {
        return DigestUtils.md5DigestAsHex(
                (LEGACY_PASSWORD_SALT + userPassword).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String generateInitialPassword() {
        StringBuilder password = new StringBuilder(INITIAL_PASSWORD_LENGTH);
        for (int i = 0; i < INITIAL_PASSWORD_LENGTH; i++) {
            int index = secureRandom.nextInt(INITIAL_PASSWORD_CHARACTERS.length());
            password.append(INITIAL_PASSWORD_CHARACTERS.charAt(index));
        }
        return password.toString();
    }

    @Override
    public UserAddResultVO createUserByAdmin(User user) {
        if (user == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户参数为空");
        }
        validateUserAccount(user.getUserAccount());
        validateUserFields(user);
        if (StrUtil.isBlank(user.getUserRole())) {
            user.setUserRole(UserRoleEnum.USER.getValue());
        } else {
            validateUserRole(user.getUserRole());
        }
        ensureActiveAccountAvailable(user.getUserAccount());

        String initialPassword = generateInitialPassword();
        user.setUserPassword(TEMPORARY_PASSWORD_PREFIX + encodePassword(initialPassword));
        saveNewUser(user, ErrorCode.OPERATION_ERROR, ErrorCode.OPERATION_ERROR.getMessage());
        UserAddResultVO result = new UserAddResultVO();
        result.setUserId(user.getId());
        result.setInitialPassword(initialPassword);
        return result;
    }

    @Override
    public boolean updateUserByAdmin(User user) {
        if (user == null || user.getId() == null || user.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户 id 不合法");
        }
        validateUserFields(user);
        if (user.getUserRole() != null) {
            validateUserRole(user.getUserRole());
        }
        return this.updateById(user);
    }

    @Override
    public boolean requiresPasswordChange(User user) {
        return user != null && isTemporaryPassword(user.getUserPassword());
    }

    @Override
    public boolean changePassword(String oldPassword, String newPassword, String checkPassword,
                                  HttpServletRequest request) {
        validatePassword(oldPassword);
        validatePassword(newPassword);
        validatePassword(checkPassword);
        if (!newPassword.equals(checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "两次输入的新密码不一致");
        }

        User loginUser = getLoginUser(request);
        if (!matchesPassword(oldPassword, loginUser.getUserPassword())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "原密码错误");
        }
        if (matchesPassword(newPassword, loginUser.getUserPassword())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "新密码不能与原密码相同");
        }

        String encodedPassword = encodePassword(newPassword);
        User updateUser = new User();
        updateUser.setId(loginUser.getId());
        updateUser.setUserPassword(encodedPassword);
        if (!this.updateById(updateUser)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "修改密码失败");
        }

        loginUser.setUserPassword(encodedPassword);
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        request.changeSessionId();
        session.setAttribute(USER_LOGIN_STATE, authenticatedSession(loginUser));
        return true;
    }

    @Override
    public UserPasswordResetResultVO resetPasswordByAdmin(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户 id 不合法");
        }

        String temporaryPassword = generateInitialPassword();
        User updateUser = new User();
        updateUser.setId(userId);
        updateUser.setUserPassword(TEMPORARY_PASSWORD_PREFIX + encodePassword(temporaryPassword));
        if (!this.updateById(updateUser)) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        }

        UserPasswordResetResultVO result = new UserPasswordResetResultVO();
        result.setUserId(userId);
        result.setTemporaryPassword(temporaryPassword);
        return result;
    }

    @Override
    public UserVO getUserVO(User user) {
        if (user == null) {
            return null;
        }
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);
        return userVO;
    }

    @Override
    public List<UserVO> getUserVOList(List<User> userList) {
        if (CollUtil.isEmpty(userList)) {
            return new ArrayList<>();
        }
        return userList.stream().map(this::getUserVO).collect(Collectors.toList());
    }

    @Override
    public QueryWrapper<User> getQueryWrapper(UserQueryDTO userQueryRequest) {
        if (userQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        Long id = userQueryRequest.getId();
        String userAccount = userQueryRequest.getUserAccount();
        String userName = userQueryRequest.getUserName();
        String userProfile = userQueryRequest.getUserProfile();
        String userRole = userQueryRequest.getUserRole();
        String requestedSortField = userQueryRequest.getSortField();
        String sortField = StrUtil.isBlank(requestedSortField)
                ? null
                : SORT_FIELD_MAP.get(requestedSortField);
        String sortOrder = userQueryRequest.getSortOrder();
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(ObjUtil.isNotNull(id), "id", id);
        queryWrapper.eq(StrUtil.isNotBlank(userRole), "userRole", userRole);
        queryWrapper.like(StrUtil.isNotBlank(userAccount), "userAccount", userAccount);
        queryWrapper.like(StrUtil.isNotBlank(userName), "userName", userName);
        queryWrapper.like(StrUtil.isNotBlank(userProfile), "userProfile", userProfile);
        queryWrapper.orderBy(StrUtil.isNotBlank(sortField), "ascend".equals(sortOrder), sortField);
        return queryWrapper;
    }

    private User upgradePassword(User user, String rawPassword) {
        String previousPassword = user.getUserPassword();
        String upgradedPassword = encodePassword(rawPassword);
        UpdateWrapper<User> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("id", user.getId())
                .eq("userPassword", previousPassword)
                .set("userPassword", upgradedPassword);
        if (this.baseMapper.update(null, updateWrapper) == 1) {
            user.setUserPassword(upgradedPassword);
            return user;
        }

        User currentUser = this.baseMapper.selectById(user.getId());
        if (currentUser == null || !matchesPassword(rawPassword, currentUser.getUserPassword())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户不存在或密码错误");
        }
        if (needsPasswordUpgrade(currentUser.getUserPassword())) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "密码安全升级失败");
        }
        return currentUser;
    }

    private void establishAuthenticatedSession(HttpServletRequest request, User user) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            session = request.getSession(true);
        } else {
            request.changeSessionId();
        }
        session.setAttribute(USER_LOGIN_STATE, authenticatedSession(user));
    }

    private AuthenticatedSession authenticatedSession(User user) {
        return AuthenticatedSession.fromCredential(user.getId(), user.getUserPassword());
    }

    private void validateUserAccount(String userAccount) {
        if (StrUtil.isBlank(userAccount)
                || userAccount.length() < MIN_ACCOUNT_LENGTH
                || userAccount.length() > MAX_ACCOUNT_LENGTH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "用户账号长度应为 4 到 256 位");
        }
    }

    private void validatePassword(String userPassword) {
        if (StrUtil.isBlank(userPassword) || userPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户密码不能少于 8 位");
        }
    }

    private void validateUserFields(User user) {
        validateMaxLength(user.getUserName(), 256, "用户昵称不能超过 256 位");
        validateMaxLength(user.getUserAvatar(), 1024, "用户头像地址不能超过 1024 位");
        validateMaxLength(user.getUserProfile(), 512, "用户简介不能超过 512 位");
    }

    private void validateMaxLength(String value, int maxLength, String message) {
        if (value != null && value.length() > maxLength) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, message);
        }
    }

    private void validateUserRole(String userRole) {
        if (UserRoleEnum.getEnumByValue(userRole) == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户角色不合法");
        }
    }

    private void ensureActiveAccountAvailable(String userAccount) {
        QueryWrapper<User> userQueryWrapper = new QueryWrapper<>();
        userQueryWrapper.eq("userAccount", userAccount);
        Long count = this.baseMapper.selectCount(userQueryWrapper);
        if (count != null && count > 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号重复");
        }
    }

    private void saveNewUser(User user, ErrorCode saveErrorCode, String saveErrorMessage) {
        try {
            if (!this.save(user)) {
                throw new BusinessException(saveErrorCode, saveErrorMessage);
            }
        } catch (DuplicateKeyException e) {
            // 唯一索引同时保留逻辑删除账号，并作为并发注册的最终一致性兜底。
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号重复");
        }
    }

    private boolean needsPasswordUpgrade(String storedPassword) {
        return !isTemporaryPassword(storedPassword)
                && (isLegacyPassword(storedPassword)
                || (storedPassword != null && storedPassword.startsWith("$2")));
    }

    private boolean isLegacyPassword(String storedPassword) {
        return storedPassword != null && LEGACY_MD5_PATTERN.matcher(storedPassword).matches();
    }

    private boolean isTemporaryPassword(String storedPassword) {
        return storedPassword != null && storedPassword.startsWith(TEMPORARY_PASSWORD_PREFIX);
    }

    private String unwrapTemporaryPassword(String storedPassword) {
        return isTemporaryPassword(storedPassword)
                ? storedPassword.substring(TEMPORARY_PASSWORD_PREFIX.length())
                : storedPassword;
    }

}
