package com.jack.autocodebackend.model.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class UserPasswordChangeDTO implements Serializable {

    /**
     * 当前密码
     */
    private String oldPassword;

    /**
     * 新密码
     */
    private String newPassword;

    /**
     * 确认新密码
     */
    private String checkPassword;

    private static final long serialVersionUID = 1L;
}
