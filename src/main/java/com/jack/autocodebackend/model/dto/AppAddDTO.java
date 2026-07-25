package com.jack.autocodebackend.model.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class AppAddDTO implements Serializable {

    /**
     * 应用初始化的 prompt
     */
    private String initPrompt;

    private static final long serialVersionUID = 1L;
}
