package com.jack.autocodebackend.model.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class UserPasswordResetDTO implements Serializable {

    /**
     * 待重置密码的用户 id
     */
    private Long userId;

    private static final long serialVersionUID = 1L;
}
