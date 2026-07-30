package com.jack.autocodebackend.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * Sanitized detail view for a featured application.
 */
@Data
@Schema(description = "Public detail for a featured application")
public class PublicAppDetailVO implements Serializable {

    private Long id;

    private String appName;

    private String cover;

    private String codeGenType;

    @Schema(description = "Latest generation status; failure details are never public")
    private String generationStatus;

    @Schema(description = "Public deployment URL; null until a deployment completes")
    private String deployUrl;

    private Date deployedTime;

    private Date createTime;

    private Date updateTime;

    private static final long serialVersionUID = 1L;
}
