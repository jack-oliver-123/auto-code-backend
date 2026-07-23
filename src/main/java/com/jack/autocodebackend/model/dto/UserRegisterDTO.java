package com.jack.autocodebackend.model.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class UserRegisterDTO implements Serializable {

        private static final long serialVersionUID = 3191241716373120793L;

        /**
         * 账号
         */
        private String userAccount;

        /**
         * 密码
         */
        private String userPassword;

        /**
         * 确认密码
         */
        private String checkPassword;

}
