package com.jack.autocodebackend.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 管理员对话历史分页查询。
 */
@Data
@Schema(description = "Administrator moderation query with fixed newest-first ordering")
public class ChatHistoryAdminQueryDTO implements Serializable {

    @Schema(description = "Positive page number", defaultValue = "1", minimum = "1")
    private int pageNum = 1;

    @Schema(description = "Page size from 1 through 100", defaultValue = "10",
            minimum = "1", maximum = "100")
    private int pageSize = 10;

    @Schema(description = "Optional positive application id filter", example = "2001")
    private Long appId;

    @Schema(description = "Optional positive initiating-user id filter", example = "1001")
    private Long userId;

    @Schema(description = "Optional validated message type filter", allowableValues = {"user", "ai"})
    private String messageType;

    private static final long serialVersionUID = 1L;
}
