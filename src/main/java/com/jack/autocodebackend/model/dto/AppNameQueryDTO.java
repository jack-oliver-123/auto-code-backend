package com.jack.autocodebackend.model.dto;

import com.jack.autocodebackend.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

@EqualsAndHashCode(callSuper = true)
@Data
public class AppNameQueryDTO extends PageRequest implements Serializable {

    /**
     * 应用名称
     */
    private String appName;

    private static final long serialVersionUID = 1L;
}
