package com.jack.autocodebackend.service;


import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.spring.service.IService;
import com.jack.autocodebackend.model.domain.User;
import com.jack.autocodebackend.model.dto.UserQueryDTO;
import com.jack.autocodebackend.model.vo.UserLoginVO;
import com.jack.autocodebackend.model.vo.UserVO;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;


/**
* @author qianwen.cui
* @description 针对表【user(用户)】的数据库操作Service
* @createDate 2026-07-23 13:21:51
*/
public interface UserService extends IService<User> {
    /**
     * 用户注册
     *
     * @param userAccount   用户账户
     * @param userPassword  用户密码
     * @param checkPassword 校验密码
     * @return 新用户 id
     */
    long userRegister(String userAccount, String userPassword, String checkPassword);

    /**
     * 用户登录
     *
     * @param userAccount  用户账户
     * @param userPassword 用户密码
     * @param request
     * @return 脱敏后的用户信息
     */
    UserLoginVO userLogin(String userAccount, String userPassword, HttpServletRequest request);

    User getLoginUser(HttpServletRequest request);

    UserLoginVO getLoginUserVO(User loginUser);

    /**
     * 用户注销
     *
     * @param request
     * @return
     */
    boolean userLogout(HttpServletRequest request);

    String getEncryptPassword(String userPassword);

    UserVO getUserVO(User user);
    List<UserVO> getUserVOList(List<User> userList);
    QueryWrapper<User> getQueryWrapper(UserQueryDTO userQueryRequest);
}
