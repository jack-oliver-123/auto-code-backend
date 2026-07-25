package com.jack.autocodebackend.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * Request for creating or refreshing an owner's generated-code preview.
 */
@Data
@Schema(description = "Generated application preview request")
public class AppPreviewRequestDTO implements Serializable {

    @Schema(description = "Positive application id owned by the current user",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2001")
    private Long appId;

    private static final long serialVersionUID = 1L;
}
