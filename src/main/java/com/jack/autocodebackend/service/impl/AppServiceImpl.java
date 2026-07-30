package com.jack.autocodebackend.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.jack.autocodebackend.ai.model.enums.CodeGenTypeEnum;
import com.jack.autocodebackend.config.AppDeploymentProperties;
import com.jack.autocodebackend.config.AppGenerationProperties;
import com.jack.autocodebackend.constant.AppConstant;
import com.jack.autocodebackend.core.AiCodeGeneratorFacade;
import com.jack.autocodebackend.core.CodeGenerationSession;
import com.jack.autocodebackend.core.deploy.AppDeploymentFileManager;
import com.jack.autocodebackend.core.deploy.AppDeploymentFileManager.PublishedDeployment;
import com.jack.autocodebackend.core.deploy.AppDeploymentFileManager.StagedDeployment;
import com.jack.autocodebackend.core.deploy.AppDeploymentFileManager.Undeployment;
import com.jack.autocodebackend.core.deploy.AppDeploymentLocalServer;
import com.jack.autocodebackend.core.deploy.AppDeploymentLocalServer.PreviewAccess;
import com.jack.autocodebackend.core.deploy.AppDeploymentLocalServer.PreviewPublication;
import com.jack.autocodebackend.core.deploy.DeployKeyGenerator;
import com.jack.autocodebackend.core.lock.AppProcessingLeaseManager;
import com.jack.autocodebackend.core.lock.AppProcessingLeaseManager.AppProcessingLease;
import com.jack.autocodebackend.core.lock.AppProcessingLeaseLostException;
import com.jack.autocodebackend.core.vue.VueProjectSourceContextLoader;
import com.jack.autocodebackend.core.vue.VueProjectSourceSnapshot;
import com.jack.autocodebackend.exception.BusinessException;
import com.jack.autocodebackend.exception.ErrorCode;
import com.jack.autocodebackend.exception.ThrowUtils;
import com.jack.autocodebackend.mapper.AppMapper;
import com.jack.autocodebackend.model.domain.App;
import com.jack.autocodebackend.model.domain.User;
import com.jack.autocodebackend.model.dto.AppAddDTO;
import com.jack.autocodebackend.model.dto.AppAdminUpdateDTO;
import com.jack.autocodebackend.model.dto.AppNameQueryDTO;
import com.jack.autocodebackend.model.dto.AppQueryDTO;
import com.jack.autocodebackend.model.dto.AppUpdateDTO;
import com.jack.autocodebackend.model.enums.ChatHistoryMessageTypeEnum;
import com.jack.autocodebackend.model.enums.AppGenerationStatusEnum;
import com.jack.autocodebackend.model.vo.AppDeployVO;
import com.jack.autocodebackend.model.vo.AppDetailVO;
import com.jack.autocodebackend.model.vo.AppGenerationEvent;
import com.jack.autocodebackend.model.vo.AppPreviewVO;
import com.jack.autocodebackend.model.vo.AppVO;
import com.jack.autocodebackend.model.vo.PublicAppDetailVO;
import com.jack.autocodebackend.service.AppService;
import com.jack.autocodebackend.service.ChatHistoryService;
import com.jack.autocodebackend.service.ChatMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * 应用服务实现。
 */
@Service
public class AppServiceImpl extends ServiceImpl<AppMapper, App> implements AppService {

    private static final Logger log = LoggerFactory.getLogger(AppServiceImpl.class);

    private static final int INITIAL_APP_NAME_LENGTH = 12;

    private static final int MAX_APP_NAME_LENGTH = 256;

    private static final int MAX_COVER_LENGTH = 512;

    private static final int MAX_DEPLOY_KEY_ATTEMPTS = 10;

    private static final long DATABASE_TIME_PRECISION_MILLIS = 1_000L;

    private static final Map<String, String> SORT_FIELD_MAP = Map.of(
            "id", "id",
            "appName", "appName",
            "codeGenType", "codeGenType",
            "generationStatus", "generationStatus",
            "generationStartedTime", "generationStartedTime",
            "generationFinishedTime", "generationFinishedTime",
            "priority", "priority",
            "userId", "userId",
            "createTime", "createTime",
            "updateTime", "updateTime"
    );

    private final ObjectProvider<AiCodeGeneratorFacade> aiCodeGeneratorFacadeProvider;

    private final AppDeploymentFileManager deploymentFileManager;

    private final DeployKeyGenerator deployKeyGenerator;

    private final AppDeploymentProperties deploymentProperties;

    private final AppDeploymentLocalServer deploymentLocalServer;

    private final ChatHistoryService chatHistoryService;

    private final ChatMemoryService chatMemoryService;

    private final AppProcessingLeaseManager appProcessingLeaseManager;

    private final VueProjectSourceContextLoader vueProjectSourceContextLoader;

    private final TransactionTemplate transactionTemplate;

    private final AppGenerationProperties generationProperties;

    public AppServiceImpl(
            ObjectProvider<AiCodeGeneratorFacade> aiCodeGeneratorFacadeProvider,
            AppDeploymentFileManager deploymentFileManager,
            DeployKeyGenerator deployKeyGenerator,
            AppDeploymentProperties deploymentProperties,
            AppDeploymentLocalServer deploymentLocalServer,
            ChatHistoryService chatHistoryService,
            ChatMemoryService chatMemoryService,
            AppProcessingLeaseManager appProcessingLeaseManager,
            TransactionTemplate transactionTemplate
    ) {
        this(
                aiCodeGeneratorFacadeProvider,
                deploymentFileManager,
                deployKeyGenerator,
                deploymentProperties,
                deploymentLocalServer,
                chatHistoryService,
                chatMemoryService,
                appProcessingLeaseManager,
                transactionTemplate,
                null,
                AppGenerationProperties.defaults()
        );
    }

    public AppServiceImpl(
            ObjectProvider<AiCodeGeneratorFacade> aiCodeGeneratorFacadeProvider,
            AppDeploymentFileManager deploymentFileManager,
            DeployKeyGenerator deployKeyGenerator,
            AppDeploymentProperties deploymentProperties,
            AppDeploymentLocalServer deploymentLocalServer,
            ChatHistoryService chatHistoryService,
            ChatMemoryService chatMemoryService,
            AppProcessingLeaseManager appProcessingLeaseManager,
            TransactionTemplate transactionTemplate,
            VueProjectSourceContextLoader vueProjectSourceContextLoader
    ) {
        this(
                aiCodeGeneratorFacadeProvider,
                deploymentFileManager,
                deployKeyGenerator,
                deploymentProperties,
                deploymentLocalServer,
                chatHistoryService,
                chatMemoryService,
                appProcessingLeaseManager,
                transactionTemplate,
                vueProjectSourceContextLoader,
                AppGenerationProperties.defaults()
        );
    }

    @Autowired
    public AppServiceImpl(
            ObjectProvider<AiCodeGeneratorFacade> aiCodeGeneratorFacadeProvider,
            AppDeploymentFileManager deploymentFileManager,
            DeployKeyGenerator deployKeyGenerator,
            AppDeploymentProperties deploymentProperties,
            AppDeploymentLocalServer deploymentLocalServer,
            ChatHistoryService chatHistoryService,
            ChatMemoryService chatMemoryService,
            AppProcessingLeaseManager appProcessingLeaseManager,
            TransactionTemplate transactionTemplate,
            VueProjectSourceContextLoader vueProjectSourceContextLoader,
            AppGenerationProperties generationProperties
    ) {
        this.aiCodeGeneratorFacadeProvider = aiCodeGeneratorFacadeProvider;
        this.deploymentFileManager = deploymentFileManager;
        this.deployKeyGenerator = deployKeyGenerator;
        this.deploymentProperties = deploymentProperties;
        this.deploymentLocalServer = deploymentLocalServer;
        this.chatHistoryService = chatHistoryService;
        this.chatMemoryService = chatMemoryService;
        this.appProcessingLeaseManager = appProcessingLeaseManager;
        this.transactionTemplate = transactionTemplate;
        this.vueProjectSourceContextLoader = vueProjectSourceContextLoader;
        this.generationProperties = Objects.requireNonNull(generationProperties);
    }

    @Override
    public long createApp(AppAddDTO appAddDTO, User loginUser) {
        if (appAddDTO == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用参数为空");
        }
        validateLoginUser(loginUser);
        String initPrompt = normalizePrompt(appAddDTO.getInitPrompt());

        App app = new App();
        app.setInitPrompt(initPrompt);
        app.setAppName(getInitialAppName(initPrompt));
        app.setPriority(AppConstant.DEFAULT_APP_PRIORITY);
        app.setUserId(loginUser.getId());
        app.setGenerationStatus(AppGenerationStatusEnum.PENDING.getValue());
        ThrowUtils.throwIf(!this.save(app), ErrorCode.OPERATION_ERROR, "创建应用失败");
        ThrowUtils.throwIf(app.getId() == null, ErrorCode.OPERATION_ERROR, "创建应用失败");
        return app.getId();
    }

    @Override
    public Flux<AppGenerationEvent> chatToGenCode(Long appId, String message, User loginUser) {
        validateAppId(appId);
        validateLoginUser(loginUser);
        return Flux.using(
                () -> appProcessingLeaseManager.acquire(appId),
                lease -> createDurableCodeGenerationStream(appId, message, loginUser, lease),
                AppProcessingLease::close,
                true
        );
    }

    private Flux<AppGenerationEvent> createDurableCodeGenerationStream(
            Long appId,
            String message,
            User loginUser,
            AppProcessingLease lease
    ) {
        Long userId = loginUser.getId();
        return Flux.defer(() -> {
            App app = getExistingApp(appId);
            checkOwner(app, userId);

            GenerationInput generationInput = resolveGenerationInput(app, message);
            GenerationAttemptContext attempt = startGenerationAttempt(app, userId);
            Flux<AppGenerationEvent> generationWork = runGenerationAttempt(
                    attempt, generationInput, lease);
            Flux<AppGenerationEvent> leaseLoss = lease.lossSignal()
                    .thenMany(Flux.never());
            Flux<AppGenerationEvent> attemptWork = Flux.merge(
                            generationWork,
                            leaseLoss
                    )
                    .takeUntil(AppGenerationEvent.Completed.class::isInstance)
                    .timeout(
                            generationProperties.getCompleteAttemptTimeout(),
                            Flux.defer(() -> Flux.error(attempt.timeoutFailure(
                                    generationProperties.getCompleteAttemptTimeout())))
                    )
                    .onErrorResume(error -> finalizeFailureAndEmit(attempt, error))
                    .doOnCancel(() -> finalizeCancellation(attempt));
            return withHeartbeats(attemptWork);
        });
    }

    private GenerationInput resolveGenerationInput(App app, String message) {
        boolean initialGeneration = app.getCodeGenType() == null;
        if (initialGeneration) {
            if (isBlankMessage(app.getInitPrompt())) {
                throw new BusinessException(
                        ErrorCode.SYSTEM_ERROR, "应用初始化 prompt 缺失");
            }
            return new GenerationInput(
                    true, CodeGenTypeEnum.VUE_PROJECT, app.getInitPrompt());
        }
        if (isBlankMessage(message)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "提示词不能为空");
        }
        CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        if (codeGenType == null) {
            throw new BusinessException(
                    ErrorCode.SYSTEM_ERROR, "应用代码生成类型错误");
        }
        return new GenerationInput(false, codeGenType, message);
    }

    private GenerationAttemptContext startGenerationAttempt(App app, Long userId) {
        AppGenerationStatusEnum currentStatus = AppGenerationStatusEnum.getEnumByValue(
                app.getGenerationStatus());
        if (currentStatus == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "应用生成状态错误");
        }
        String attemptId = UUID.randomUUID().toString();
        Date startedTime = new Date();
        int updated = getBaseMapper().startGenerationAttempt(
                app.getId(),
                userId,
                currentStatus.getValue(),
                app.getGenerationAttemptId(),
                attemptId,
                startedTime
        );
        ThrowUtils.throwIf(updated != 1, ErrorCode.OPERATION_ERROR, "启动生成任务失败");
        return new GenerationAttemptContext(app.getId(), userId, attemptId);
    }

    private Flux<AppGenerationEvent> runGenerationAttempt(
            GenerationAttemptContext attempt,
            GenerationInput generationInput,
            AppProcessingLease lease
    ) {
        return Flux.defer(() -> {
            attempt.phase("user-history");
            long userMessageId = chatHistoryService.addChatMessage(
                    attempt.appId(),
                    attempt.userId(),
                    generationInput.effectiveMessage(),
                    ChatHistoryMessageTypeEnum.USER
            );
            attempt.markUserMessageRecorded();

            attempt.phase("prompt-context");
            String aiRequestMessage = buildAiRequestMessage(
                    attempt.appId(), userMessageId, generationInput);
            attempt.phase("preview-preflight");
            requirePreviewAvailable();
            lease.assertHeld();

            AiCodeGeneratorFacade facade = aiCodeGeneratorFacadeProvider.getIfAvailable();
            if (facade == null) {
                throw new BusinessException(
                        ErrorCode.SYSTEM_ERROR, "AI 代码生成服务不可用");
            }
            attempt.phase("provider-and-publication");
            CodeGenerationSession generationSession = facade.startCodeGeneration(
                    aiRequestMessage,
                    generationInput.codeGenType(),
                    attempt.appId()
            );
            if (generationSession == null) {
                throw new BusinessException(
                        ErrorCode.SYSTEM_ERROR, "AI 代码生成会话不可用");
            }
            Flux<String> codeStream = generationSession.stream();
            if (codeStream == null) {
                throw new BusinessException(
                        ErrorCode.SYSTEM_ERROR, "AI 代码生成流不可用");
            }

            StringBuilder aiReply = new StringBuilder();
            return Flux.using(
                    () -> generationSession,
                    activeSession -> codeStream
                            .doOnNext(aiReply::append)
                            .map(chunk -> (AppGenerationEvent)
                                    new AppGenerationEvent.Content(chunk))
                            .concatWith(completeGeneratedCandidate(
                                    activeSession,
                                    attempt,
                                    generationInput,
                                    aiReply,
                                    lease
                            ))
                            .onErrorResume(error -> completeDurablePlainConversation(
                                    error,
                                    generationInput,
                                    aiReply.toString(),
                                    attempt,
                                    lease
                            )),
                    CodeGenerationSession::rollback,
                    true
            );
        });
    }

    private String buildAiRequestMessage(
            Long appId,
            long userMessageId,
            GenerationInput generationInput
    ) {
        if (!generationInput.initialGeneration()
                && generationInput.codeGenType() == CodeGenTypeEnum.VUE_PROJECT) {
            if (vueProjectSourceContextLoader == null) {
                throw new BusinessException(
                        ErrorCode.SYSTEM_ERROR,
                        "Vue project source service is unavailable"
                );
            }
            VueProjectSourceSnapshot sourceSnapshot =
                    vueProjectSourceContextLoader.load(appId);
            return chatMemoryService.buildPrompt(
                    appId,
                    userMessageId,
                    generationInput.effectiveMessage(),
                    false,
                    sourceSnapshot
            );
        }
        return chatMemoryService.buildPrompt(
                appId,
                userMessageId,
                generationInput.effectiveMessage(),
                generationInput.initialGeneration()
        );
    }

    private Flux<AppGenerationEvent> completeGeneratedCandidate(
            CodeGenerationSession generationSession,
            GenerationAttemptContext attempt,
            GenerationInput generationInput,
            StringBuilder aiReply,
            AppProcessingLease lease
    ) {
        return Flux.defer(() -> {
            lease.assertHeld();
            attempt.phase("preview-preparation");
            try (PreviewPublication previewPublication = preparePreview(
                    attempt.appId(), generationInput.codeGenType())) {
                AppPreviewVO preview = toPreviewVO(previewPublication.access());
                attempt.phase("success-transaction");
                finalizeSuccessTransaction(
                        attempt,
                        generationInput,
                        aiReply.toString()
                );
                previewPublication.commit();
                generationSession.commit();
                attempt.markSucceeded();
                refreshChatMemoryBestEffort(attempt.appId());
                return Flux.just(new AppGenerationEvent.Completed(preview));
            }
        });
    }

    private Flux<AppGenerationEvent> completeDurablePlainConversation(
            Throwable failure,
            GenerationInput generationInput,
            String aiReply,
            GenerationAttemptContext attempt,
            AppProcessingLease lease
    ) {
        if (!isPlainConversationResponse(
                generationInput.initialGeneration(),
                failure,
                aiReply,
                generationInput.codeGenType())) {
            return Flux.error(failure);
        }
        return Flux.defer(() -> {
            lease.assertHeld();
            attempt.phase("plain-conversation-finalization");
            try (PreviewPublication previewPublication = preparePreview(
                    attempt.appId(), generationInput.codeGenType())) {
                AppPreviewVO preview = toPreviewVO(previewPublication.access());
                finalizeSuccessTransaction(attempt, generationInput, aiReply);
                previewPublication.commit();
                attempt.markSucceeded();
                refreshChatMemoryBestEffort(attempt.appId());
                log.info("AI returned an ordinary conversation; retained app {} code",
                        attempt.appId());
                return Flux.just(new AppGenerationEvent.Completed(preview));
            }
        });
    }

    private Flux<AppGenerationEvent> withHeartbeats(Flux<AppGenerationEvent> attemptWork) {
        Flux<AppGenerationEvent> heartbeats = Flux.interval(
                        generationProperties.getHeartbeatInterval())
                .map(ignored -> new AppGenerationEvent.Heartbeat());
        return Flux.merge(attemptWork, heartbeats)
                .takeUntil(AppGenerationEvent::isTerminal);
    }

    private boolean isPlainConversationResponse(
            boolean initialGeneration,
            Throwable failure,
            String aiReply,
            CodeGenTypeEnum codeGenType
    ) {
        if (initialGeneration
                || !(failure instanceof AiCodeGeneratorFacade.CodeResponseFormatException)
                || isBlankMessage(aiReply)) {
            return false;
        }
        if (codeGenType == CodeGenTypeEnum.VUE_PROJECT) {
            return ((AiCodeGeneratorFacade.CodeResponseFormatException) failure)
                    .isOrdinaryConversationCandidate();
        }
        String normalizedReply = aiReply.toLowerCase(Locale.ROOT);
        return !aiReply.contains("```")
                && !normalizedReply.contains("<!doctype html")
                && !normalizedReply.contains("<html");
    }

    @Override
    public AppPreviewVO createAppPreview(Long appId, User loginUser) {
        validateAppId(appId);
        validateLoginUser(loginUser);
        try (AppProcessingLease lease = appProcessingLeaseManager.acquire(appId)) {
            App app = getExistingApp(appId);
            checkOwner(app, loginUser.getId());
            CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
            if (codeGenType == null) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "应用尚未完成代码生成");
            }
            lease.assertHeld();
            return issuePreview(appId, codeGenType);
        }
    }

    private void requirePreviewAvailable() {
        try {
            deploymentLocalServer.requirePreviewAvailable();
        } catch (RuntimeException exception) {
            throw operationFailure("应用预览服务不可用", exception);
        }
    }

    private AppPreviewVO issuePreview(Long appId, CodeGenTypeEnum codeGenType) {
        PreviewAccess previewAccess;
        try {
            previewAccess = deploymentLocalServer.issuePreview(appId, codeGenType);
        } catch (RuntimeException exception) {
            throw operationFailure("创建应用预览失败", exception);
        }
        return toPreviewVO(previewAccess);
    }

    private PreviewPublication preparePreview(Long appId, CodeGenTypeEnum codeGenType) {
        PreviewPublication publication;
        try {
            publication = deploymentLocalServer.preparePreview(appId, codeGenType);
        } catch (RuntimeException exception) {
            throw operationFailure("创建应用预览失败", exception);
        }
        ThrowUtils.throwIf(publication == null || publication.access() == null,
                ErrorCode.OPERATION_ERROR, "创建应用预览失败");
        return publication;
    }

    private AppPreviewVO toPreviewVO(PreviewAccess previewAccess) {
        ThrowUtils.throwIf(previewAccess == null || StrUtil.isBlank(previewAccess.url()),
                ErrorCode.OPERATION_ERROR, "创建应用预览失败");
        AppPreviewVO appPreviewVO = new AppPreviewVO();
        appPreviewVO.setPreviewUrl(previewAccess.url());
        appPreviewVO.setExpiresAt(previewAccess.expiresAt().toEpochMilli());
        return appPreviewVO;
    }

    private void saveInitialCodeGenType(Long appId, Long userId, CodeGenTypeEnum codeGenType) {
        App updateApp = new App();
        updateApp.setCodeGenType(codeGenType.getValue());
        UpdateWrapper<App> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("id", appId)
                .eq("userId", userId)
                .isNull("codeGenType");
        ThrowUtils.throwIf(!this.update(updateApp, updateWrapper),
                ErrorCode.OPERATION_ERROR, "保存应用代码生成类型失败");
    }

    private void finalizeSuccessTransaction(
            GenerationAttemptContext attempt,
            GenerationInput generationInput,
            String aiReply
    ) {
        Boolean committed = transactionTemplate.execute(status -> {
            chatHistoryService.addChatMessage(
                    attempt.appId(),
                    attempt.userId(),
                    aiReply,
                    ChatHistoryMessageTypeEnum.AI
            );
            if (generationInput.initialGeneration()) {
                saveInitialCodeGenType(
                        attempt.appId(), attempt.userId(), generationInput.codeGenType());
            }
            int updated = getBaseMapper().completeGenerationAttempt(
                    attempt.appId(),
                    attempt.userId(),
                    attempt.attemptId(),
                    new Date()
            );
            ThrowUtils.throwIf(updated != 1,
                    ErrorCode.OPERATION_ERROR, "完成生成任务失败");
            return Boolean.TRUE;
        });
        ThrowUtils.throwIf(!Boolean.TRUE.equals(committed),
                ErrorCode.OPERATION_ERROR, "完成生成任务失败");
    }

    private Flux<AppGenerationEvent> finalizeFailureAndEmit(
            GenerationAttemptContext attempt,
            Throwable failure
    ) {
        FailureDescriptor descriptor = classifyGenerationFailure(failure);
        finalizeAttemptFailure(attempt, failure, descriptor, false);
        return Flux.just(new AppGenerationEvent.Failed(
                descriptor.apiCode(),
                descriptor.safeMessage(),
                AppGenerationStatusEnum.FAILED.getValue()
        ));
    }

    private void finalizeCancellation(GenerationAttemptContext attempt) {
        CancellationException cancellation = new CancellationException(
                "generation stream cancelled");
        finalizeAttemptFailure(
                attempt,
                cancellation,
                new FailureDescriptor(
                        "GENERATION_CANCELLED",
                        "生成已取消",
                        ErrorCode.OPERATION_ERROR.getCode()
                ),
                true
        );
    }

    private void finalizeAttemptFailure(
            GenerationAttemptContext attempt,
            Throwable primaryFailure,
            FailureDescriptor descriptor,
            boolean cancellation
    ) {
        if (!attempt.markTerminal()) {
            return;
        }
        if (primaryFailure instanceof GenerationAttemptTimeoutException timeout) {
            log.warn(
                    "Generation attempt timed out: appId={}, attemptId={}, phase={}, "
                            + "configuredLimitMs={}, durationMs={}",
                    timeout.appId(),
                    timeout.attemptId(),
                    timeout.phase(),
                    timeout.configuredLimitMillis(),
                    timeout.durationMillis()
            );
        }
        try {
            Boolean finalized = transactionTemplate.execute(status -> {
                if (attempt.userMessageRecorded()) {
                    if (cancellation) {
                        chatHistoryService.addAiCancellationMessage(
                                attempt.appId(), attempt.userId());
                    } else {
                        chatHistoryService.addAiFailureMessage(
                                attempt.appId(), attempt.userId(), primaryFailure);
                    }
                }
                int updated = getBaseMapper().failGenerationAttempt(
                        attempt.appId(),
                        attempt.userId(),
                        attempt.attemptId(),
                        descriptor.storedCode(),
                        descriptor.safeMessage(),
                        new Date()
                );
                ThrowUtils.throwIf(updated != 1,
                        ErrorCode.OPERATION_ERROR, "记录生成失败状态失败");
                return Boolean.TRUE;
            });
            ThrowUtils.throwIf(!Boolean.TRUE.equals(finalized),
                    ErrorCode.OPERATION_ERROR, "记录生成失败状态失败");
            refreshChatMemoryBestEffort(attempt.appId());
        } catch (RuntimeException secondaryFailure) {
            if (secondaryFailure != primaryFailure) {
                primaryFailure.addSuppressed(secondaryFailure);
            }
            invalidateChatMemoryBestEffort(attempt.appId());
            log.error(
                    "Generation failure finalization failed: appId={}, attemptId={}, category={}",
                    attempt.appId(), attempt.attemptId(), descriptor.storedCode());
        }
    }

    private FailureDescriptor classifyGenerationFailure(Throwable failure) {
        if (failure instanceof GenerationAttemptTimeoutException
                || failure instanceof TimeoutException) {
            return new FailureDescriptor(
                    "GENERATION_TIMEOUT",
                    "生成超时，请重试",
                    ErrorCode.OPERATION_ERROR.getCode()
            );
        }
        if (failure instanceof AppProcessingLeaseLostException) {
            return new FailureDescriptor(
                    "GENERATION_LEASE_LOST",
                    "生成任务已中断，请重试",
                    ErrorCode.OPERATION_ERROR.getCode()
            );
        }
        if (failure instanceof AiCodeGeneratorFacade.CodeResponseFormatException) {
            return new FailureDescriptor(
                    "INVALID_AI_RESPONSE",
                    "AI 返回内容不完整，请重试",
                    ErrorCode.OPERATION_ERROR.getCode()
            );
        }
        return new FailureDescriptor(
                "GENERATION_FAILED",
                "生成失败，请稍后重试",
                ErrorCode.OPERATION_ERROR.getCode()
        );
    }

    private void invalidateChatMemoryBestEffort(Long appId) {
        try {
            chatMemoryService.invalidate(appId);
        } catch (RuntimeException invalidationFailure) {
            log.warn("Chat-memory invalidation failed for app {}", appId);
        }
    }

    private void refreshChatMemoryBestEffort(Long appId) {
        try {
            chatMemoryService.refresh(appId);
        } catch (RuntimeException refreshFailure) {
            try {
                chatMemoryService.invalidate(appId);
            } catch (RuntimeException invalidationFailure) {
                refreshFailure.addSuppressed(invalidationFailure);
            }
            log.warn("Chat-memory refresh failed for app {}; generation remains successful",
                    appId);
        }
    }

    private boolean isBlankMessage(String message) {
        if (message == null || message.isEmpty()) {
            return true;
        }
        return message.codePoints().allMatch(this::isWhitespace);
    }

    @Override
    public AppDeployVO deployApp(Long appId, User loginUser) {
        validateAppId(appId);
        validateLoginUser(loginUser);
        try (AppProcessingLease lease = appProcessingLeaseManager.acquire(appId)) {
            App app = getExistingApp(appId);
            checkOwner(app, loginUser.getId());
            CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
            if (codeGenType == null) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "应用尚未完成代码生成");
            }

            try (StagedDeployment stagedDeployment =
                         deploymentFileManager.stage(codeGenType, appId)) {
                KeyResolution keyResolution = resolveDeployKey(app, loginUser.getId());
                lease.assertHeld();
                PublishedDeployment publication = keyResolution.replaceExisting()
                        ? stagedDeployment.publishReplacement(keyResolution.deployKey())
                        : stagedDeployment.publishNew(keyResolution.deployKey());
                try (publication) {
                    return completeDeployment(app, keyResolution.deployKey(), publication);
                }
            }
        }
    }

    private KeyResolution resolveDeployKey(App app, Long userId) {
        if (app.getDeployKey() != null) {
            return new KeyResolution(app.getDeployKey(), true);
        }

        for (int attempt = 0; attempt < MAX_DEPLOY_KEY_ATTEMPTS; attempt++) {
            String candidate = deployKeyGenerator.generate();
            if (!deploymentFileManager.isTargetAvailableForNewKey(candidate)) {
                continue;
            }

            App updateApp = new App();
            updateApp.setDeployKey(candidate);
            UpdateWrapper<App> updateWrapper = new UpdateWrapper<>();
            updateWrapper.eq("id", app.getId())
                    .eq("userId", userId)
                    .isNull("deployKey");
            try {
                if (this.update(updateApp, updateWrapper)) {
                    app.setDeployKey(candidate);
                    return new KeyResolution(candidate, false);
                }
            } catch (DuplicateKeyException exception) {
                continue;
            } catch (RuntimeException exception) {
                throw operationFailure("生成部署标识失败", exception);
            }

            App reloadedApp = getExistingApp(app.getId());
            checkOwner(reloadedApp, userId);
            if (reloadedApp.getDeployKey() != null) {
                app.setDeployKey(reloadedApp.getDeployKey());
                app.setDeployedTime(reloadedApp.getDeployedTime());
                return new KeyResolution(reloadedApp.getDeployKey(), true);
            }
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "生成部署标识失败");
        }
        throw new BusinessException(ErrorCode.OPERATION_ERROR, "生成唯一部署标识失败，请重试");
    }

    private AppDeployVO completeDeployment(
            App app,
            String deployKey,
            PublishedDeployment publication
    ) {
        Date intendedDeployedTime = nextDeploymentTime(app.getDeployedTime());
        App updateApp = new App();
        updateApp.setDeployedTime(intendedDeployedTime);
        UpdateWrapper<App> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("id", app.getId())
                .eq("userId", app.getUserId())
                .eq("deployKey", deployKey);

        boolean updated;
        try {
            updated = this.update(updateApp, updateWrapper);
        } catch (RuntimeException metadataFailure) {
            return reconcileMetadataFailure(
                    app,
                    deployKey,
                    intendedDeployedTime,
                    publication,
                    metadataFailure
            );
        }
        if (!updated) {
            BusinessException failure = new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "保存部署时间失败"
            );
            rollbackPublication(publication, failure);
            throw failure;
        }

        publication.commit();
        return createDeployVO(deployKey, intendedDeployedTime);
    }

    private AppDeployVO reconcileMetadataFailure(
            App app,
            String deployKey,
            Date intendedDeployedTime,
            PublishedDeployment publication,
            RuntimeException metadataFailure
    ) {
        App reloadedApp;
        try {
            reloadedApp = this.getById(app.getId());
        } catch (RuntimeException verificationFailure) {
            publication.preserve();
            metadataFailure.addSuppressed(verificationFailure);
            log.error(
                    "Deployment metadata outcome is indeterminate for app {} and key {}; "
                            + "the published and backup snapshots were preserved",
                    app.getId(),
                    deployKey,
                    metadataFailure
            );
            throw operationFailure("部署状态无法确认，请稍后重试", metadataFailure);
        }

        if (isIntendedDeploymentCommitted(
                reloadedApp,
                app.getUserId(),
                deployKey,
                intendedDeployedTime
        )) {
            publication.commit();
            log.warn(
                    "Deployment metadata update reported an error but committed for app {} and key {}",
                    app.getId(),
                    deployKey,
                    metadataFailure
            );
            return createDeployVO(deployKey, intendedDeployedTime);
        }

        BusinessException failure = operationFailure("保存部署时间失败", metadataFailure);
        rollbackPublication(publication, failure);
        throw failure;
    }

    private boolean isIntendedDeploymentCommitted(
            App app,
            Long userId,
            String deployKey,
            Date intendedDeployedTime
    ) {
        return app != null
                && Objects.equals(app.getUserId(), userId)
                && Objects.equals(app.getDeployKey(), deployKey)
                && Objects.equals(app.getDeployedTime(), intendedDeployedTime);
    }

    private void rollbackPublication(
            PublishedDeployment publication,
            RuntimeException deploymentFailure
    ) {
        try {
            publication.rollback();
        } catch (RuntimeException rollbackFailure) {
            deploymentFailure.addSuppressed(rollbackFailure);
            log.error("Failed to roll back application deployment", rollbackFailure);
        }
    }

    private Date nextDeploymentTime(Date previousDeployedTime) {
        long intendedTime = Math.floorDiv(
                System.currentTimeMillis(),
                DATABASE_TIME_PRECISION_MILLIS
        ) * DATABASE_TIME_PRECISION_MILLIS;
        if (previousDeployedTime != null && intendedTime <= previousDeployedTime.getTime()) {
            intendedTime = Math.floorDiv(
                    previousDeployedTime.getTime(),
                    DATABASE_TIME_PRECISION_MILLIS
            ) * DATABASE_TIME_PRECISION_MILLIS + DATABASE_TIME_PRECISION_MILLIS;
        }
        return new Date(intendedTime);
    }

    private AppDeployVO createDeployVO(String deployKey, Date deployedTime) {
        AppDeployVO appDeployVO = new AppDeployVO();
        appDeployVO.setDeployKey(deployKey);
        appDeployVO.setDeployUrl(deploymentProperties.buildDeployUrl(deployKey));
        appDeployVO.setDeployedTime(deployedTime);
        return appDeployVO;
    }

    @Override
    public boolean updateAppByUser(AppUpdateDTO appUpdateDTO, User loginUser) {
        if (appUpdateDTO == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用参数为空");
        }
        validateLoginUser(loginUser);
        Long appId = appUpdateDTO.getId();
        validateAppId(appId);
        validateAppName(appUpdateDTO.getAppName());
        App existingApp = getExistingApp(appId);
        checkOwner(existingApp, loginUser.getId());

        App updateApp = new App();
        updateApp.setAppName(appUpdateDTO.getAppName().trim());
        updateApp.setEditTime(new Date());
        UpdateWrapper<App> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("id", appId).eq("userId", loginUser.getId());
        boolean updated = this.update(updateApp, updateWrapper);
        ThrowUtils.throwIf(!updated, ErrorCode.OPERATION_ERROR, "更新应用失败");
        return true;
    }

    @Override
    public boolean deleteAppByUser(Long appId, User loginUser) {
        validateLoginUser(loginUser);
        validateAppId(appId);
        try (AppProcessingLease lease = appProcessingLeaseManager.acquire(appId)) {
            App existingApp = getExistingApp(appId);
            checkOwner(existingApp, loginUser.getId());

            QueryWrapper<App> removeWrapper = new QueryWrapper<>();
            removeWrapper.eq("id", appId).eq("userId", loginUser.getId());
            return deleteWithUndeployment(existingApp,
                    () -> purgeMemoryAndDeleteApplicationData(
                            appId, lease, () -> this.remove(removeWrapper)));
        }
    }

    @Override
    public boolean updateAppByAdmin(AppAdminUpdateDTO appAdminUpdateDTO) {
        if (appAdminUpdateDTO == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用参数为空");
        }
        Long appId = appAdminUpdateDTO.getId();
        validateAppId(appId);
        validateAdminUpdate(appAdminUpdateDTO);
        getExistingApp(appId);

        App updateApp = new App();
        updateApp.setId(appId);
        if (appAdminUpdateDTO.getAppName() != null) {
            updateApp.setAppName(appAdminUpdateDTO.getAppName().trim());
        }
        if (appAdminUpdateDTO.getCover() != null) {
            updateApp.setCover(appAdminUpdateDTO.getCover());
        }
        if (appAdminUpdateDTO.getPriority() != null) {
            updateApp.setPriority(appAdminUpdateDTO.getPriority());
        }
        updateApp.setEditTime(new Date());
        boolean updated = this.updateById(updateApp);
        ThrowUtils.throwIf(!updated, ErrorCode.OPERATION_ERROR, "更新应用失败");
        return true;
    }

    @Override
    public boolean deleteAppByAdmin(Long appId) {
        validateAppId(appId);
        try (AppProcessingLease lease = appProcessingLeaseManager.acquire(appId)) {
            App existingApp = getExistingApp(appId);
            return deleteWithUndeployment(existingApp,
                    () -> purgeMemoryAndDeleteApplicationData(
                            appId, lease, () -> this.removeById(appId)));
        }
    }

    private boolean purgeMemoryAndDeleteApplicationData(
            Long appId,
            AppProcessingLease lease,
            BooleanSupplier applicationDeletion
    ) {
        lease.assertHeld();
        chatMemoryService.purge(appId);
        lease.assertHeld();
        return deleteApplicationData(appId, applicationDeletion);
    }

    private boolean deleteApplicationData(Long appId, BooleanSupplier applicationDeletion) {
        Boolean removed = transactionTemplate.execute((TransactionStatus status) -> {
            boolean appRemoved = applicationDeletion.getAsBoolean();
            if (!appRemoved) {
                status.setRollbackOnly();
                return false;
            }
            chatHistoryService.deleteByAppId(appId);
            return true;
        });
        return Boolean.TRUE.equals(removed);
    }

    private boolean deleteWithUndeployment(App app, BooleanSupplier deletion) {
        Undeployment undeployment = app.getDeployKey() == null
                ? null
                : deploymentFileManager.prepareUndeployment(app.getDeployKey());

        boolean removed;
        try {
            removed = deletion.getAsBoolean();
        } catch (RuntimeException deletionFailure) {
            return reconcileDeletionFailure(app, undeployment, deletionFailure);
        }
        if (!removed) {
            BusinessException failure = new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "删除应用失败"
            );
            rollbackUndeployment(undeployment, failure);
            throw failure;
        }

        completeDeletion(app, undeployment);
        return true;
    }

    private boolean reconcileDeletionFailure(
            App app,
            Undeployment undeployment,
            RuntimeException deletionFailure
    ) {
        App reloadedApp;
        try {
            reloadedApp = this.getById(app.getId());
        } catch (RuntimeException verificationFailure) {
            deletionFailure.addSuppressed(verificationFailure);
            revokePreviewAfterIndeterminateDeletion(app.getId(), deletionFailure);
            log.error(
                    "Application deletion outcome is indeterminate for app {}; "
                            + "deployment remains offline and preview access was revoked",
                    app.getId(),
                    deletionFailure
            );
            throw operationFailure("删除状态无法确认，应用已下线", deletionFailure);
        }

        if (reloadedApp == null) {
            log.warn(
                    "Application deletion reported an error but committed for app {}",
                    app.getId(),
                    deletionFailure
            );
            completeDeletion(app, undeployment);
            return true;
        }

        rollbackUndeployment(undeployment, deletionFailure);
        throw operationFailure("删除应用失败", deletionFailure);
    }

    private void completeDeletion(App app, Undeployment undeployment) {
        try {
            if (undeployment != null) {
                undeployment.commit();
            }
        } finally {
            deploymentLocalServer.revokePreview(app.getId());
        }
    }

    private void revokePreviewAfterIndeterminateDeletion(
            Long appId,
            RuntimeException deletionFailure
    ) {
        try {
            deploymentLocalServer.revokePreview(appId);
        } catch (RuntimeException revocationFailure) {
            deletionFailure.addSuppressed(revocationFailure);
            log.error("Failed to revoke preview after indeterminate deletion for app {}",
                    appId, revocationFailure);
        }
    }

    private void rollbackUndeployment(
            Undeployment undeployment,
            RuntimeException deletionFailure
    ) {
        if (undeployment == null) {
            return;
        }
        try {
            undeployment.rollback();
        } catch (RuntimeException rollbackFailure) {
            deletionFailure.addSuppressed(rollbackFailure);
            log.error("Failed to restore application deployment after deletion failure", rollbackFailure);
        }
    }

    @Override
    public QueryWrapper<App> getMyAppQueryWrapper(AppNameQueryDTO appNameQueryDTO, Long userId) {
        if (appNameQueryDTO == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        QueryWrapper<App> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("userId", userId);
        queryWrapper.like(StrUtil.isNotBlank(appNameQueryDTO.getAppName()),
                "appName", appNameQueryDTO.getAppName());
        applySorting(queryWrapper, appNameQueryDTO.getSortField(), appNameQueryDTO.getSortOrder());
        return queryWrapper;
    }

    @Override
    public QueryWrapper<App> getGoodAppQueryWrapper(AppNameQueryDTO appNameQueryDTO) {
        if (appNameQueryDTO == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        QueryWrapper<App> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("priority", AppConstant.GOOD_APP_PRIORITY);
        queryWrapper.like(StrUtil.isNotBlank(appNameQueryDTO.getAppName()),
                "appName", appNameQueryDTO.getAppName());
        applySorting(queryWrapper, appNameQueryDTO.getSortField(), appNameQueryDTO.getSortOrder());
        return queryWrapper;
    }

    @Override
    public QueryWrapper<App> getQueryWrapper(AppQueryDTO appQueryDTO) {
        if (appQueryDTO == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        QueryWrapper<App> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(appQueryDTO.getId() != null, "id", appQueryDTO.getId());
        queryWrapper.like(StrUtil.isNotBlank(appQueryDTO.getAppName()),
                "appName", appQueryDTO.getAppName());
        queryWrapper.like(StrUtil.isNotBlank(appQueryDTO.getCover()),
                "cover", appQueryDTO.getCover());
        queryWrapper.like(StrUtil.isNotBlank(appQueryDTO.getInitPrompt()),
                "initPrompt", appQueryDTO.getInitPrompt());
        queryWrapper.eq(StrUtil.isNotBlank(appQueryDTO.getCodeGenType()),
                "codeGenType", appQueryDTO.getCodeGenType());
        if (StrUtil.isNotBlank(appQueryDTO.getGenerationStatus())) {
            AppGenerationStatusEnum generationStatus =
                    AppGenerationStatusEnum.getEnumByValue(
                            appQueryDTO.getGenerationStatus());
            ThrowUtils.throwIf(generationStatus == null,
                    ErrorCode.PARAMS_ERROR, "生成状态不合法");
            queryWrapper.eq("generationStatus", generationStatus.getValue());
        }
        queryWrapper.eq(StrUtil.isNotBlank(appQueryDTO.getDeployKey()),
                "deployKey", appQueryDTO.getDeployKey());
        queryWrapper.eq(appQueryDTO.getPriority() != null,
                "priority", appQueryDTO.getPriority());
        queryWrapper.eq(appQueryDTO.getUserId() != null,
                "userId", appQueryDTO.getUserId());
        applySorting(queryWrapper, appQueryDTO.getSortField(), appQueryDTO.getSortOrder());
        return queryWrapper;
    }

    @Override
    public AppVO getAppVO(App app) {
        if (app == null) {
            return null;
        }
        AppVO appVO = new AppVO();
        BeanUtils.copyProperties(app, appVO);
        appVO.setDeployUrl(getCompletedDeployUrl(app));
        return appVO;
    }

    @Override
    public List<AppVO> getAppVOList(List<App> appList) {
        if (CollUtil.isEmpty(appList)) {
            return new ArrayList<>();
        }
        return appList.stream().map(this::getAppVO).collect(Collectors.toList());
    }

    @Override
    public Page<AppVO> getAppVOPage(Page<App> appPage) {
        if (appPage == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "分页结果为空");
        }
        Page<AppVO> appVOPage = new Page<>(
                appPage.getCurrent(), appPage.getSize(), appPage.getTotal());
        appVOPage.setRecords(getAppVOList(appPage.getRecords()));
        return appVOPage;
    }

    @Override
    public AppDetailVO getAppDetailVOByOwner(Long appId, User loginUser) {
        validateAppId(appId);
        validateLoginUser(loginUser);
        App app = getExistingApp(appId);
        checkOwner(app, loginUser.getId());
        return getAppDetailVO(app);
    }

    @Override
    public PublicAppDetailVO getPublicAppDetailVO(Long appId) {
        validateAppId(appId);
        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null
                        || !Objects.equals(app.getPriority(), AppConstant.GOOD_APP_PRIORITY),
                ErrorCode.NOT_FOUND_ERROR, "应用不存在");

        PublicAppDetailVO publicAppDetailVO = new PublicAppDetailVO();
        publicAppDetailVO.setId(app.getId());
        publicAppDetailVO.setAppName(app.getAppName());
        publicAppDetailVO.setCover(app.getCover());
        publicAppDetailVO.setCodeGenType(app.getCodeGenType());
        publicAppDetailVO.setGenerationStatus(app.getGenerationStatus());
        publicAppDetailVO.setDeployUrl(getCompletedDeployUrl(app));
        publicAppDetailVO.setDeployedTime(app.getDeployedTime());
        publicAppDetailVO.setCreateTime(app.getCreateTime());
        publicAppDetailVO.setUpdateTime(app.getUpdateTime());
        return publicAppDetailVO;
    }

    @Override
    public AppDetailVO getAppDetailVO(App app) {
        if (app == null) {
            return null;
        }
        AppDetailVO appDetailVO = new AppDetailVO();
        BeanUtils.copyProperties(app, appDetailVO);
        appDetailVO.setDeployUrl(getCompletedDeployUrl(app));
        return appDetailVO;
    }

    private String getCompletedDeployUrl(App app) {
        if (app.getDeployedTime() == null || StrUtil.isBlank(app.getDeployKey())) {
            return null;
        }
        try {
            return deploymentProperties.buildDeployUrl(app.getDeployKey());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private App getExistingApp(Long appId) {
        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        return app;
    }

    private void checkOwner(App app, Long userId) {
        if (!Objects.equals(app.getUserId(), userId)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
    }

    private void validateLoginUser(User loginUser) {
        if (loginUser == null || loginUser.getId() == null || loginUser.getId() <= 0) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
    }

    private void validateAppId(Long appId) {
        if (appId == null || appId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 id 不合法");
        }
    }

    private String normalizePrompt(String initPrompt) {
        if (initPrompt == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "初始化 prompt 不能为空");
        }
        int start = 0;
        int end = initPrompt.length();
        while (start < end) {
            int codePoint = initPrompt.codePointAt(start);
            if (!isWhitespace(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        while (start < end) {
            int codePoint = initPrompt.codePointBefore(end);
            if (!isWhitespace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        if (start == end) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "初始化 prompt 不能为空");
        }
        return initPrompt.substring(start, end);
    }

    private String getInitialAppName(String initPrompt) {
        int codePointCount = Math.min(
                initPrompt.codePointCount(0, initPrompt.length()), INITIAL_APP_NAME_LENGTH);
        int endIndex = initPrompt.offsetByCodePoints(0, codePointCount);
        return initPrompt.substring(0, endIndex);
    }

    private boolean isWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private void validateAppName(String appName) {
        if (StrUtil.isBlank(appName)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用名称不能为空");
        }
        validateMaxLength(appName, MAX_APP_NAME_LENGTH, "应用名称不能超过 256 位");
    }

    private void validateAdminUpdate(AppAdminUpdateDTO appAdminUpdateDTO) {
        if (appAdminUpdateDTO.getAppName() == null
                && appAdminUpdateDTO.getCover() == null
                && appAdminUpdateDTO.getPriority() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "至少需要提供一个更新字段");
        }
        if (appAdminUpdateDTO.getAppName() != null) {
            validateAppName(appAdminUpdateDTO.getAppName());
        }
        validateMaxLength(appAdminUpdateDTO.getCover(), MAX_COVER_LENGTH,
                "应用封面地址不能超过 512 位");
        if (appAdminUpdateDTO.getPriority() != null && appAdminUpdateDTO.getPriority() < 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用优先级不能为负数");
        }
    }

    private void validateMaxLength(String value, int maxLength, String message) {
        if (value != null && value.length() > maxLength) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, message);
        }
    }

    private void applySorting(QueryWrapper<App> queryWrapper,
                              String requestedSortField,
                              String sortOrder) {
        String sortField = StrUtil.isBlank(requestedSortField)
                ? null
                : SORT_FIELD_MAP.get(requestedSortField);
        if (sortField == null) {
            queryWrapper.orderByDesc("createTime", "id");
            return;
        }
        queryWrapper.orderBy(true, "ascend".equals(sortOrder), sortField);
    }

    private static BusinessException operationFailure(String message, Throwable cause) {
        BusinessException exception = new BusinessException(ErrorCode.OPERATION_ERROR, message);
        exception.initCause(cause);
        return exception;
    }

    private record GenerationInput(
            boolean initialGeneration,
            CodeGenTypeEnum codeGenType,
            String effectiveMessage
    ) {
    }

    private record FailureDescriptor(
            String storedCode,
            String safeMessage,
            int apiCode
    ) {
    }

    private static final class GenerationAttemptContext {

        private final Long appId;
        private final Long userId;
        private final String attemptId;
        private final long startedNanos = System.nanoTime();
        private final AtomicReference<String> phase = new AtomicReference<>("starting");
        private final AtomicBoolean terminal = new AtomicBoolean();
        private volatile boolean userMessageRecorded;

        private GenerationAttemptContext(
                Long appId,
                Long userId,
                String attemptId
        ) {
            this.appId = appId;
            this.userId = userId;
            this.attemptId = attemptId;
        }

        private Long appId() {
            return appId;
        }

        private Long userId() {
            return userId;
        }

        private String attemptId() {
            return attemptId;
        }

        private void phase(String nextPhase) {
            phase.set(nextPhase);
        }

        private void markUserMessageRecorded() {
            userMessageRecorded = true;
        }

        private boolean userMessageRecorded() {
            return userMessageRecorded;
        }

        private boolean markTerminal() {
            return terminal.compareAndSet(false, true);
        }

        private void markSucceeded() {
            terminal.compareAndSet(false, true);
        }

        private GenerationAttemptTimeoutException timeoutFailure(Duration limit) {
            long durationMillis = Duration.ofNanos(
                    Math.max(0L, System.nanoTime() - startedNanos)).toMillis();
            return new GenerationAttemptTimeoutException(
                    appId,
                    attemptId,
                    phase.get(),
                    limit.toMillis(),
                    durationMillis
            );
        }
    }

    private static final class GenerationAttemptTimeoutException extends RuntimeException {

        private final Long appId;
        private final String attemptId;
        private final String phase;
        private final long configuredLimitMillis;
        private final long durationMillis;

        private GenerationAttemptTimeoutException(
                Long appId,
                String attemptId,
                String phase,
                long configuredLimitMillis,
                long durationMillis
        ) {
            super("generation attempt exceeded its configured deadline");
            this.appId = appId;
            this.attemptId = attemptId;
            this.phase = phase;
            this.configuredLimitMillis = configuredLimitMillis;
            this.durationMillis = durationMillis;
        }

        private Long appId() {
            return appId;
        }

        private String attemptId() {
            return attemptId;
        }

        private String phase() {
            return phase;
        }

        private long configuredLimitMillis() {
            return configuredLimitMillis;
        }

        private long durationMillis() {
            return durationMillis;
        }
    }

    private record KeyResolution(String deployKey, boolean replaceExisting) {
    }
}
