package com.jack.autocodebackend.model.vo;

import lombok.Data;

import java.io.Serializable;

@Data
public class UserAddResultVO implements Serializable {

    /**
     * 新建用户 id
     */
    private Long userId;

    /**
     * 一次性初始密码
     */
    private String initialPassword;

    private static final long serialVersionUID = 1L;
}
