package com.jack.autocodebackend.model.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class AppUpdateDTO implements Serializable {

    /**
     * id
     */
    private Long id;

    /**
     * 应用名称
     */
    private String appName;

    private static final long serialVersionUID = 1L;
}
