package com.jack.autocodebackend.controller;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jack.autocodebackend.annotation.AuthCheck;
import com.jack.autocodebackend.common.BaseResponse;
import com.jack.autocodebackend.common.DeleteRequest;
import com.jack.autocodebackend.common.ResultUtils;
import com.jack.autocodebackend.config.OpenApiConfig;
import com.jack.autocodebackend.constant.UserConstant;
import com.jack.autocodebackend.exception.ErrorCode;
import com.jack.autocodebackend.exception.ThrowUtils;
import com.jack.autocodebackend.model.domain.App;
import com.jack.autocodebackend.model.domain.User;
import com.jack.autocodebackend.model.dto.AppAddDTO;
import com.jack.autocodebackend.model.dto.AppAdminUpdateDTO;
import com.jack.autocodebackend.model.dto.AppChatRequestDTO;
import com.jack.autocodebackend.model.dto.AppNameQueryDTO;
import com.jack.autocodebackend.model.dto.AppPreviewRequestDTO;
import com.jack.autocodebackend.model.dto.AppQueryDTO;
import com.jack.autocodebackend.model.dto.AppUpdateDTO;
import com.jack.autocodebackend.model.vo.AppDeployVO;
import com.jack.autocodebackend.model.vo.AppDetailVO;
import com.jack.autocodebackend.model.vo.AppGenerationEvent;
import com.jack.autocodebackend.model.vo.AppPreviewVO;
import com.jack.autocodebackend.model.vo.AppVO;
import com.jack.autocodebackend.model.vo.PublicAppDetailVO;
import com.jack.autocodebackend.service.AppService;
import com.jack.autocodebackend.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * 应用接口。
 */
@RestController
@RequestMapping("/app")
@Tag(name = "Application", description = "Application management, generation, and deployment")
public class AppController {

    private static final Logger log = LoggerFactory.getLogger(AppController.class);

    private static final long MAX_USER_PAGE_SIZE = 20;

    @Resource
    private AppService appService;

    @Resource
    private UserService userService;

    /**
     * 创建应用。
     */
    @PostMapping("/add")
    @AuthCheck
    @SecurityRequirement(name = OpenApiConfig.SESSION_COOKIE_SCHEME)
    public BaseResponse<Long> addApp(@RequestBody AppAddDTO appAddDTO,
                                     HttpServletRequest request) {
        ThrowUtils.throwIf(appAddDTO == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(appService.createApp(appAddDTO, loginUser));
    }

    /**
     * 与 AI 对话并流式生成应用代码；全部保存成功后发送 done 事件。
     */
    @PostMapping(value = "/chat/gen/code",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @AuthCheck
    @SecurityRequirement(name = OpenApiConfig.SESSION_COOKIE_SCHEME)
    @Operation(
            summary = "Generate or refine application code as an SSE stream",
            description = "The first generation uses the stored initPrompt and ignores message. "
                    + "Later generations require a non-blank message. Each content event contains "
                    + "JSON data shaped as {\"d\":\"<chunk>\"}. The named done event is emitted "
                    + "with a time-limited bootstrap bearer previewUrl and expiresAt only after generation, "
                    + "parsing, file publication, required database updates, and immutable preview-snapshot "
                    + "creation succeed. Opening previewUrl exchanges the bearer grant for an HttpOnly, "
                    + "SameSite=Strict, path-scoped cookie and redirects to a token-free preview-content URL. "
                    + "For iframe preview, configure the isolated preview origin to remain same-site with "
                    + "the editor (for example localhost on a separate port); a cross-site host suppresses "
                    + "the strict cookie. "
                    + "If preview capability is unavailable, the request fails before AI generation. A "
                    + "generation error or cancellation terminates the stream without a done event."
                    + " Comment-only keep-alives may appear while work is active. An asynchronous "
                    + "failure emits exactly one named error event and then closes normally; streamed "
                    + "content remains provisional until done."
    )
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            description = "Application id plus the conditionally required generation message"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "SSE content and heartbeat comments followed by exactly one "
                            + "done event on success or one error event on asynchronous failure",
                    content = @Content(
                            mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                            schema = @Schema(
                                    type = "string",
                                    example = "data:{\"d\":\"<main>hello</main>\"}\n\n"
                                            + "event:done\n"
                                            + "data:{\"previewUrl\":\"http://localhost:9332/"
                                            + "preview/HqJ7c2N4vY8sLm5pR3tW6xZ9AbCdEfGhIjKlMnoPqRs/\","
                                            + "\"expiresAt\":1753405723000}\n\n"
                            )
                    )
            ),
            @ApiResponse(responseCode = "400", description = "Invalid appId or later-generation message",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = BaseResponse.class))),
            @ApiResponse(responseCode = "401", description = "Session cookie is missing or invalid",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = BaseResponse.class))),
            @ApiResponse(responseCode = "403", description = "Caller is not the owner or must change password",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = BaseResponse.class))),
            @ApiResponse(responseCode = "404", description = "Application does not exist",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = BaseResponse.class))),
            @ApiResponse(responseCode = "500",
                    description = "Synchronous generation setup failed before SSE streaming began",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = BaseResponse.class)))
    })
    public Flux<ServerSentEvent<String>> chatToGenCode(
            @RequestBody AppChatRequestDTO appChatRequestDTO,
            HttpServletRequest request) {
        ThrowUtils.throwIf(appChatRequestDTO == null
                        || appChatRequestDTO.getAppId() == null
                        || appChatRequestDTO.getAppId() <= 0,
                ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        return appService
                .chatToGenCode(
                        appChatRequestDTO.getAppId(),
                        appChatRequestDTO.getMessage(),
                        loginUser
                )
                .takeUntil(AppGenerationEvent::isTerminal)
                .map(this::toServerSentEvent)
                .onErrorResume(error -> {
                    log.error("Unexpected application generation stream failure");
                    return Flux.just(toServerSentEvent(new AppGenerationEvent.Failed(
                            ErrorCode.OPERATION_ERROR.getCode(),
                            "生成失败，请稍后重试",
                            "FAILED"
                    )));
                });
    }

    private ServerSentEvent<String> toServerSentEvent(AppGenerationEvent generationEvent) {
        if (generationEvent instanceof AppGenerationEvent.Content content) {
            return ServerSentEvent.builder(
                    JSONUtil.toJsonStr(Map.of("d", content.chunk()))).build();
        }
        if (generationEvent instanceof AppGenerationEvent.Heartbeat) {
            return ServerSentEvent.<String>builder()
                    .comment("keep-alive")
                    .build();
        }
        if (generationEvent instanceof AppGenerationEvent.Completed completed) {
            return ServerSentEvent.<String>builder()
                    .event("done")
                    .data(JSONUtil.toJsonStr(completed.preview()))
                    .build();
        }
        AppGenerationEvent.Failed failed = (AppGenerationEvent.Failed) generationEvent;
        return ServerSentEvent.<String>builder()
                .event("error")
                .data(JSONUtil.toJsonStr(Map.of(
                        "code", failed.code(),
                        "message", failed.message(),
                        "status", failed.status()
                )))
                .build();
    }

    /**
     * Create an isolated preview grant for the latest complete generated draft.
     */
    @PostMapping(value = "/preview",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @AuthCheck
    @SecurityRequirement(name = OpenApiConfig.SESSION_COOKIE_SCHEME)
    @Operation(
            summary = "Create or refresh the current user's generated application preview",
            description = "Returns a time-limited bootstrap bearer URL on the isolated preview origin. "
                    + "Opening it exchanges the grant for an HttpOnly, SameSite=Strict, path-scoped cookie "
                    + "and redirects to a token-free URL serving an immutable snapshot. For iframe preview, "
                    + "this isolated origin must remain same-site with the editor while using a separate "
                    + "port or subdomain. The application "
                    + "must belong to the authenticated caller and have a complete generated draft. "
                    + "This operation does not deploy the application."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Preview URL returned"),
            @ApiResponse(responseCode = "400", description = "Invalid application id"),
            @ApiResponse(responseCode = "401", description = "Session cookie is missing or invalid"),
            @ApiResponse(responseCode = "403", description = "Caller is not the owner or must change password"),
            @ApiResponse(responseCode = "404", description = "Application does not exist"),
            @ApiResponse(responseCode = "500", description = "No complete generated preview is available")
    })
    public BaseResponse<AppPreviewVO> createAppPreview(
            @RequestBody AppPreviewRequestDTO appPreviewRequestDTO,
            HttpServletRequest request
    ) {
        ThrowUtils.throwIf(appPreviewRequestDTO == null
                        || appPreviewRequestDTO.getAppId() == null
                        || appPreviewRequestDTO.getAppId() <= 0,
                ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(appService.createAppPreview(
                appPreviewRequestDTO.getAppId(), loginUser));
    }

    /**
     * 部署当前用户的应用。
     */
    @PostMapping("/deploy")
    @AuthCheck
    @SecurityRequirement(name = OpenApiConfig.SESSION_COOKIE_SCHEME)
    public BaseResponse<AppDeployVO> deployApp(@RequestParam Long appId,
                                               HttpServletRequest request) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(appService.deployApp(appId, loginUser));
    }

    /**
     * 修改自己的应用名称。
     */
    @PostMapping("/update")
    @AuthCheck
    @SecurityRequirement(name = OpenApiConfig.SESSION_COOKIE_SCHEME)
    public BaseResponse<Boolean> updateApp(@RequestBody AppUpdateDTO appUpdateDTO,
                                           HttpServletRequest request) {
        ThrowUtils.throwIf(appUpdateDTO == null
                        || appUpdateDTO.getId() == null
                        || appUpdateDTO.getId() <= 0,
                ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(appService.updateAppByUser(appUpdateDTO, loginUser));
    }

    /**
     * 删除自己的应用。
     */
    @PostMapping("/delete")
    @AuthCheck
    @SecurityRequirement(name = OpenApiConfig.SESSION_COOKIE_SCHEME)
    public BaseResponse<Boolean> deleteApp(@RequestBody DeleteRequest deleteRequest,
                                           HttpServletRequest request) {
        ThrowUtils.throwIf(deleteRequest == null
                        || deleteRequest.getId() == null
                        || deleteRequest.getId() <= 0,
                ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(appService.deleteAppByUser(deleteRequest.getId(), loginUser));
    }

    /**
     * 获取应用详情。
     */
    @GetMapping("/get/vo")
    @AuthCheck
    @SecurityRequirement(name = OpenApiConfig.SESSION_COOKIE_SCHEME)
    @Operation(summary = "Get the current user's application detail",
            description = "Returns an active application only when the authenticated caller owns it.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Owner detail returned"),
            @ApiResponse(responseCode = "400", description = "Invalid application id"),
            @ApiResponse(responseCode = "401", description = "Session cookie is missing or invalid"),
            @ApiResponse(responseCode = "403", description = "Caller does not own the application"),
            @ApiResponse(responseCode = "404", description = "Application does not exist")
    })
    public BaseResponse<AppDetailVO> getAppVOById(
            @RequestParam Long id,
            HttpServletRequest request
    ) {
        ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(appService.getAppDetailVOByOwner(id, loginUser));
    }

    /**
     * Get a sanitized public detail for a featured application.
     */
    @GetMapping("/good/get/vo")
    @Operation(summary = "Get public featured application detail",
            description = "Anonymous endpoint. Non-featured and deleted applications are reported as missing. "
                    + "The response excludes initPrompt, owner id, and deployKey.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Sanitized featured detail returned"),
            @ApiResponse(responseCode = "400", description = "Invalid application id"),
            @ApiResponse(responseCode = "404", description = "Application is missing or not featured")
    })
    public BaseResponse<PublicAppDetailVO> getGoodAppVOById(@RequestParam Long id) {
        ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(appService.getPublicAppDetailVO(id));
    }

    /**
     * 分页获取当前用户的应用。
     */
    @PostMapping("/my/list/page/vo")
    @AuthCheck
    @SecurityRequirement(name = OpenApiConfig.SESSION_COOKIE_SCHEME)
    public BaseResponse<Page<AppVO>> listMyAppVOByPage(
            @RequestBody AppNameQueryDTO appNameQueryDTO,
            HttpServletRequest request) {
        validateUserPageRequest(appNameQueryDTO);
        User loginUser = userService.getLoginUser(request);
        QueryWrapper<App> queryWrapper = appService.getMyAppQueryWrapper(
                appNameQueryDTO, loginUser.getId());
        Page<App> appPage = appService.page(
                new Page<>(appNameQueryDTO.getPageNum(), appNameQueryDTO.getPageSize()),
                queryWrapper);
        return ResultUtils.success(appService.getAppVOPage(appPage));
    }

    /**
     * 分页获取精选应用。
     */
    @PostMapping("/good/list/page/vo")
    public BaseResponse<Page<AppVO>> listGoodAppVOByPage(
            @RequestBody AppNameQueryDTO appNameQueryDTO) {
        validateUserPageRequest(appNameQueryDTO);
        QueryWrapper<App> queryWrapper = appService.getGoodAppQueryWrapper(appNameQueryDTO);
        Page<App> appPage = appService.page(
                new Page<>(appNameQueryDTO.getPageNum(), appNameQueryDTO.getPageSize()),
                queryWrapper);
        return ResultUtils.success(appService.getAppVOPage(appPage));
    }

    /**
     * 管理员删除任意应用。
     */
    @PostMapping("/admin/delete")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    @SecurityRequirement(name = OpenApiConfig.SESSION_COOKIE_SCHEME)
    public BaseResponse<Boolean> deleteAppByAdmin(@RequestBody DeleteRequest deleteRequest) {
        ThrowUtils.throwIf(deleteRequest == null
                        || deleteRequest.getId() == null
                        || deleteRequest.getId() <= 0,
                ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(appService.deleteAppByAdmin(deleteRequest.getId()));
    }

    /**
     * 管理员修改应用。
     */
    @PostMapping("/admin/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    @SecurityRequirement(name = OpenApiConfig.SESSION_COOKIE_SCHEME)
    public BaseResponse<Boolean> updateAppByAdmin(
            @RequestBody AppAdminUpdateDTO appAdminUpdateDTO) {
        ThrowUtils.throwIf(appAdminUpdateDTO == null
                        || appAdminUpdateDTO.getId() == null
                        || appAdminUpdateDTO.getId() <= 0,
                ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(appService.updateAppByAdmin(appAdminUpdateDTO));
    }

    /**
     * 管理员获取应用详情。
     */
    @GetMapping("/admin/get/vo")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    @SecurityRequirement(name = OpenApiConfig.SESSION_COOKIE_SCHEME)
    public BaseResponse<AppDetailVO> getAppVOByIdByAdmin(@RequestParam Long id) {
        App app = getExistingApp(id);
        return ResultUtils.success(appService.getAppDetailVO(app));
    }

    /**
     * 管理员分页查询应用。
     */
    @PostMapping("/admin/list/page/vo")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    @SecurityRequirement(name = OpenApiConfig.SESSION_COOKIE_SCHEME)
    public BaseResponse<Page<AppVO>> listAppVOByPageByAdmin(
            @RequestBody AppQueryDTO appQueryDTO) {
        ThrowUtils.throwIf(appQueryDTO == null
                        || appQueryDTO.getPageNum() < 1
                        || appQueryDTO.getPageSize() < 1,
                ErrorCode.PARAMS_ERROR, "分页参数不合法");
        Page<App> pageRequest = new Page<>(
                appQueryDTO.getPageNum(), appQueryDTO.getPageSize());
        pageRequest.setMaxLimit(Long.MAX_VALUE);
        Page<App> appPage = appService.page(
                pageRequest, appService.getQueryWrapper(appQueryDTO));
        return ResultUtils.success(appService.getAppVOPage(appPage));
    }

    private App getExistingApp(Long id) {
        ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR);
        App app = appService.getById(id);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR);
        return app;
    }

    private void validateUserPageRequest(AppNameQueryDTO appNameQueryDTO) {
        ThrowUtils.throwIf(appNameQueryDTO == null
                        || appNameQueryDTO.getPageNum() < 1
                        || appNameQueryDTO.getPageSize() < 1
                        || appNameQueryDTO.getPageSize() > MAX_USER_PAGE_SIZE,
                ErrorCode.PARAMS_ERROR, "分页参数不合法");
    }
}
