package com.jack.autocodebackend.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * Request for generating or refining an application's code.
 */
@Data
@Schema(description = "Application code-generation request")
public class AppChatRequestDTO implements Serializable {

    @Schema(description = "Positive application id owned by the current user",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2001")
    private Long appId;

    @Schema(description = "Ignored for the first generation, which uses the stored initPrompt; "
            + "required and non-blank for every later generation",
            example = "Refine the navigation spacing")
    private String message;

    private static final long serialVersionUID = 1L;
}
