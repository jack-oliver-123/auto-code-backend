package com.jack.autocodebackend.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jack.autocodebackend.annotation.AuthCheck;
import com.jack.autocodebackend.common.BaseResponse;
import com.jack.autocodebackend.common.ResultUtils;
import com.jack.autocodebackend.config.OpenApiConfig;
import com.jack.autocodebackend.constant.UserConstant;
import com.jack.autocodebackend.exception.ErrorCode;
import com.jack.autocodebackend.exception.ThrowUtils;
import com.jack.autocodebackend.model.domain.User;
import com.jack.autocodebackend.model.dto.ChatHistoryAdminQueryDTO;
import com.jack.autocodebackend.model.dto.ChatHistoryCursorQueryDTO;
import com.jack.autocodebackend.model.vo.ChatHistoryCursorPageVO;
import com.jack.autocodebackend.model.vo.ChatHistoryVO;
import com.jack.autocodebackend.service.ChatHistoryService;
import com.jack.autocodebackend.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对话历史接口。
 */
@RestController
@RequestMapping("/chatHistory")
@Tag(name = "Chat History", description = "Application conversation history and moderation")
public class ChatHistoryController {

    private final ChatHistoryService chatHistoryService;

    private final UserService userService;

    public ChatHistoryController(
            ChatHistoryService chatHistoryService,
            UserService userService
    ) {
        this.chatHistoryService = chatHistoryService;
        this.userService = userService;
    }

    /**
     * 游标分页加载应用对话历史。
     */
    @PostMapping(value = "/list/page/vo",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @AuthCheck
    @SecurityRequirement(name = OpenApiConfig.SESSION_COOKIE_SCHEME)
    @Operation(
            summary = "Load application chat history",
            description = "The application owner or an administrator can load the latest messages. "
                    + "Omit beforeId for the newest page and pass nextCursor to prepend older messages. "
                    + "Records are returned in chronological display order."
    )
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            description = "Positive appId plus an optional exclusive beforeId and page size from 1 to 20"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Authorized cursor page returned",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ChatHistoryCursorPageVO.class))),
            @ApiResponse(responseCode = "400", description = "Invalid appId, cursor, or page size",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = BaseResponse.class))),
            @ApiResponse(responseCode = "401", description = "Session cookie is missing or invalid",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = BaseResponse.class))),
            @ApiResponse(responseCode = "403",
                    description = "Caller cannot read this application or must change password",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = BaseResponse.class))),
            @ApiResponse(responseCode = "404", description = "Application does not exist",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = BaseResponse.class)))
    })
    public BaseResponse<ChatHistoryCursorPageVO> listAppHistory(
            @RequestBody ChatHistoryCursorQueryDTO queryDTO,
            HttpServletRequest request
    ) {
        ThrowUtils.throwIf(queryDTO == null, ErrorCode.PARAMS_ERROR, "请求参数为空");
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(chatHistoryService.listAppHistory(queryDTO, loginUser));
    }

    /**
     * 管理员分页查看全部对话历史。
     */
    @PostMapping(value = "/admin/list/page/vo",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    @SecurityRequirement(name = OpenApiConfig.SESSION_COOKIE_SCHEME)
    @Operation(
            summary = "List chat history for moderation",
            description = "Administrators can page through active history across applications, "
                    + "optionally filtering by appId, userId, or messageType. "
                    + "Results are ordered by createTime and id descending."
    )
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            description = "Positive pagination plus optional appId, userId, and user-or-ai type filters"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Moderation page returned",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = Page.class))),
            @ApiResponse(responseCode = "400", description = "Invalid pagination or filter",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = BaseResponse.class))),
            @ApiResponse(responseCode = "401", description = "Session cookie is missing or invalid",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = BaseResponse.class))),
            @ApiResponse(responseCode = "403",
                    description = "Administrator role is required and initial passwords are restricted",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = BaseResponse.class)))
    })
    public BaseResponse<Page<ChatHistoryVO>> listAllHistoryByAdmin(
            @RequestBody ChatHistoryAdminQueryDTO queryDTO
    ) {
        ThrowUtils.throwIf(queryDTO == null, ErrorCode.PARAMS_ERROR, "请求参数为空");
        return ResultUtils.success(chatHistoryService.listAllHistoryByAdmin(queryDTO));
    }
}
