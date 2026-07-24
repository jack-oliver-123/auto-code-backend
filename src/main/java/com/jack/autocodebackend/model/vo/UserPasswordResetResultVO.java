package com.jack.autocodebackend.model.vo;

import lombok.Data;

import java.io.Serializable;

@Data
public class UserPasswordResetResultVO implements Serializable {

    /**
     * 已重置密码的用户 id
     */
    private Long userId;

    /**
     * 一次性临时密码
     */
    private String temporaryPassword;

    private static final long serialVersionUID = 1L;
}
