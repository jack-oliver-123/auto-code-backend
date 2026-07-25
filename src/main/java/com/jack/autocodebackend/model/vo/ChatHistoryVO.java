package com.jack.autocodebackend.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 对话历史视图。
 */
@Data
@Schema(description = "Public chat-history record without logical-delete metadata")
public class ChatHistoryVO implements Serializable {

    @Schema(description = "History record id")
    private Long id;

    @Schema(description = "Exact stored message content")
    private String message;

    @Schema(description = "Message sender type", allowableValues = {"user", "ai"})
    private String messageType;

    @Schema(description = "Owning application id")
    private Long appId;

    @Schema(description = "User who initiated the generation request")
    private Long userId;

    @Schema(description = "Record creation time")
    private Date createTime;

    private static final long serialVersionUID = 1L;
}
