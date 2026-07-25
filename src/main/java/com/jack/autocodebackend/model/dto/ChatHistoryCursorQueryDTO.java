package com.jack.autocodebackend.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 应用对话历史游标查询。
 */
@Data
@Schema(description = "Cursor request for one application's active chat history")
public class ChatHistoryCursorQueryDTO implements Serializable {

    /**
     * 应用 id
     */
    @Schema(description = "Positive application id", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2001")
    private Long appId;

    /**
     * 仅查询小于该 id 的更早消息
     */
    @Schema(description = "Exclusive cursor; only records with a smaller id are returned",
            example = "501")
    private Long beforeId;

    /**
     * 每次加载条数
     */
    @Schema(description = "Number of records to return, from 1 through 20",
            defaultValue = "10", minimum = "1", maximum = "20", example = "10")
    private Integer pageSize = 10;

    private static final long serialVersionUID = 1L;
}
