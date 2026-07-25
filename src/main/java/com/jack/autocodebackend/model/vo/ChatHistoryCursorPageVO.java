package com.jack.autocodebackend.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 对话历史游标分页视图。
 */
@Data
@Schema(description = "Chronological chat-history page for prepend-style loading")
public class ChatHistoryCursorPageVO implements Serializable {

    @Schema(description = "At most the requested number of records, oldest first")
    private List<ChatHistoryVO> records = new ArrayList<>();

    @Schema(description = "Whether active records exist before this page")
    private boolean hasMore;

    @Schema(description = "Oldest returned id when hasMore is true; otherwise null",
            example = "491")
    private Long nextCursor;

    private static final long serialVersionUID = 1L;
}
