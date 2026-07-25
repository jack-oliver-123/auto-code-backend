package com.jack.autocodebackend.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.jack.autocodebackend.ai.model.enums.CodeGenTypeEnum;
import com.jack.autocodebackend.config.AppDeploymentProperties;
import com.jack.autocodebackend.constant.AppConstant;
import com.jack.autocodebackend.core.AiCodeGeneratorFacade;
import com.jack.autocodebackend.core.CodeGenerationSession;
import com.jack.autocodebackend.core.deploy.AppDeploymentFileManager;
import com.jack.autocodebackend.core.deploy.AppDeploymentFileManager.PublishedDeployment;
import com.jack.autocodebackend.core.deploy.AppDeploymentFileManager.StagedDeployment;
import com.jack.autocodebackend.core.deploy.AppDeploymentFileManager.Undeployment;
import com.jack.autocodebackend.core.deploy.AppDeploymentLocalServer;
import com.jack.autocodebackend.core.deploy.AppDeploymentLocalServer.PreviewAccess;
import com.jack.autocodebackend.core.deploy.DeployKeyGenerator;
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
import com.jack.autocodebackend.model.vo.AppDeployVO;
import com.jack.autocodebackend.model.vo.AppDetailVO;
import com.jack.autocodebackend.model.vo.AppGenerationEvent;
import com.jack.autocodebackend.model.vo.AppPreviewVO;
import com.jack.autocodebackend.model.vo.AppVO;
import com.jack.autocodebackend.model.vo.PublicAppDetailVO;
import com.jack.autocodebackend.service.AppService;
import com.jack.autocodebackend.service.ChatHistoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    private final TransactionTemplate transactionTemplate;

    private final Set<Long> processingAppIds = ConcurrentHashMap.newKeySet();

    public AppServiceImpl(
            ObjectProvider<AiCodeGeneratorFacade> aiCodeGeneratorFacadeProvider,
            AppDeploymentFileManager deploymentFileManager,
            DeployKeyGenerator deployKeyGenerator,
            AppDeploymentProperties deploymentProperties,
            AppDeploymentLocalServer deploymentLocalServer,
            ChatHistoryService chatHistoryService,
            TransactionTemplate transactionTemplate
    ) {
        this.aiCodeGeneratorFacadeProvider = aiCodeGeneratorFacadeProvider;
        this.deploymentFileManager = deploymentFileManager;
        this.deployKeyGenerator = deployKeyGenerator;
        this.deploymentProperties = deploymentProperties;
        this.deploymentLocalServer = deploymentLocalServer;
        this.chatHistoryService = chatHistoryService;
        this.transactionTemplate = transactionTemplate;
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
        ThrowUtils.throwIf(!this.save(app), ErrorCode.OPERATION_ERROR, "创建应用失败");
        ThrowUtils.throwIf(app.getId() == null, ErrorCode.OPERATION_ERROR, "创建应用失败");
        return app.getId();
    }

    @Override
    public Flux<AppGenerationEvent> chatToGenCode(Long appId, String message, User loginUser) {
        validateAppId(appId);
        validateLoginUser(loginUser);
        return Flux.defer(() -> {
            if (!processingAppIds.add(appId)) {
                return Flux.error(new BusinessException(
                        ErrorCode.OPERATION_ERROR, "应用正在处理中，请稍后重试"));
            }
            return Flux.defer(() -> createCodeGenerationStream(appId, message, loginUser.getId()))
                    .doFinally(signalType -> processingAppIds.remove(appId));
        });
    }

    private Flux<AppGenerationEvent> createCodeGenerationStream(
            Long appId,
            String message,
            Long userId
    ) {
        GenerationHistoryTracker historyTracker = new GenerationHistoryTracker(appId, userId);
        return Flux.defer(() -> {
                    App app = getExistingApp(appId);
                    checkOwner(app, userId);

                    boolean initialGeneration = app.getCodeGenType() == null;
                    CodeGenTypeEnum codeGenType;
                    String effectiveMessage;
                    if (initialGeneration) {
                        if (isBlankMessage(app.getInitPrompt())) {
                            throw new BusinessException(
                                    ErrorCode.SYSTEM_ERROR, "应用初始化 prompt 缺失");
                        }
                        codeGenType = CodeGenTypeEnum.MULTI_FILE;
                        effectiveMessage = app.getInitPrompt();
                    } else {
                        if (isBlankMessage(message)) {
                            throw new BusinessException(ErrorCode.PARAMS_ERROR, "提示词不能为空");
                        }
                        codeGenType = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
                        if (codeGenType == null) {
                            throw new BusinessException(
                                    ErrorCode.SYSTEM_ERROR, "应用代码生成类型错误");
                        }
                        effectiveMessage = message;
                    }

                    historyTracker.recordUserMessage(effectiveMessage);
                    requirePreviewAvailable();
                    AiCodeGeneratorFacade facade = aiCodeGeneratorFacadeProvider.getIfAvailable();
                    if (facade == null) {
                        throw new BusinessException(
                                ErrorCode.SYSTEM_ERROR, "AI 代码生成服务不可用");
                    }
                    CodeGenerationSession generationSession = facade.startCodeGeneration(
                            effectiveMessage, codeGenType, appId);
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
                                    .concatWith(Flux.defer(() -> {
                                        AppGenerationEvent.Completed completed =
                                                activeSession.commitAfter(() -> {
                                                    AppPreviewVO preview =
                                                            issuePreview(appId, codeGenType);
                                                    try {
                                                        historyTracker.recordSuccessfulAiReply(
                                                                aiReply.toString());
                                                        if (initialGeneration) {
                                                            saveInitialCodeGenType(
                                                                    appId, userId, codeGenType);
                                                        }
                                                    } catch (RuntimeException persistenceFailure) {
                                                        revokePreviewAfterPersistenceFailure(
                                                                appId, persistenceFailure);
                                                        throw persistenceFailure;
                                                    }
                                                    return new AppGenerationEvent.Completed(preview);
                                                });
                                        historyTracker.markCompleted();
                                        return Flux.just(completed);
                                    })),
                            CodeGenerationSession::rollback,
                            true
                    );
                })
                .onErrorResume(error -> {
                    recordGenerationFailure(historyTracker, error);
                    return Flux.error(error);
                })
                .doFinally(signalType -> {
                    if (SignalType.CANCEL.equals(signalType)) {
                        recordGenerationCancellation(historyTracker);
                    }
                });
    }

    @Override
    public AppPreviewVO createAppPreview(Long appId, User loginUser) {
        validateAppId(appId);
        validateLoginUser(loginUser);
        acquireAppProcessingLock(appId);
        try {
            App app = getExistingApp(appId);
            checkOwner(app, loginUser.getId());
            CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
            if (codeGenType == null) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "应用尚未完成代码生成");
            }
            return issuePreview(appId, codeGenType);
        } finally {
            processingAppIds.remove(appId);
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
        ThrowUtils.throwIf(previewAccess == null || StrUtil.isBlank(previewAccess.url()),
                ErrorCode.OPERATION_ERROR, "创建应用预览失败");
        AppPreviewVO appPreviewVO = new AppPreviewVO();
        appPreviewVO.setPreviewUrl(previewAccess.url());
        appPreviewVO.setExpiresAt(previewAccess.expiresAt().toEpochMilli());
        return appPreviewVO;
    }

    private void revokePreviewAfterPersistenceFailure(
            Long appId,
            RuntimeException persistenceFailure
    ) {
        try {
            deploymentLocalServer.revokePreview(appId);
        } catch (RuntimeException revocationFailure) {
            persistenceFailure.addSuppressed(revocationFailure);
            log.error("Failed to revoke preview after type persistence failed for app {}",
                    appId, revocationFailure);
        }
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

    private void recordGenerationFailure(
            GenerationHistoryTracker historyTracker,
            Throwable generationFailure
    ) {
        try {
            historyTracker.recordFailure(generationFailure);
        } catch (RuntimeException historyFailure) {
            if (historyFailure != generationFailure) {
                generationFailure.addSuppressed(historyFailure);
            }
            log.error("Failed to record generation failure for app {}",
                    historyTracker.appId(), historyFailure);
        }
    }

    private void recordGenerationCancellation(GenerationHistoryTracker historyTracker) {
        try {
            historyTracker.recordCancellation();
        } catch (RuntimeException historyFailure) {
            log.error("Failed to record generation cancellation for app {}",
                    historyTracker.appId(), historyFailure);
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
        acquireAppProcessingLock(appId);
        try {
            App app = getExistingApp(appId);
            checkOwner(app, loginUser.getId());
            CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
            if (codeGenType == null) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "应用尚未完成代码生成");
            }

            try (StagedDeployment stagedDeployment =
                         deploymentFileManager.stage(codeGenType, appId)) {
                KeyResolution keyResolution = resolveDeployKey(app, loginUser.getId());
                PublishedDeployment publication = keyResolution.replaceExisting()
                        ? stagedDeployment.publishReplacement(keyResolution.deployKey())
                        : stagedDeployment.publishNew(keyResolution.deployKey());
                try (publication) {
                    return completeDeployment(app, keyResolution.deployKey(), publication);
                }
            }
        } finally {
            processingAppIds.remove(appId);
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
        acquireAppProcessingLock(appId);
        try {
            App existingApp = getExistingApp(appId);
            checkOwner(existingApp, loginUser.getId());

            QueryWrapper<App> removeWrapper = new QueryWrapper<>();
            removeWrapper.eq("id", appId).eq("userId", loginUser.getId());
            return deleteWithUndeployment(existingApp,
                    () -> deleteApplicationData(
                            appId,
                            () -> this.remove(removeWrapper)
                    ));
        } finally {
            processingAppIds.remove(appId);
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
        acquireAppProcessingLock(appId);
        try {
            App existingApp = getExistingApp(appId);
            return deleteWithUndeployment(existingApp,
                    () -> deleteApplicationData(
                            appId,
                            () -> this.removeById(appId)
                    ));
        } finally {
            processingAppIds.remove(appId);
        }
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

    private void acquireAppProcessingLock(Long appId) {
        if (!processingAppIds.add(appId)) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR, "应用正在处理中，请稍后重试");
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

    private final class GenerationHistoryTracker {

        private final Long appId;

        private final Long userId;

        private boolean userMessageRecorded;

        private boolean completed;

        private boolean terminalFailureRecorded;

        private Long successfulAiMessageId;

        private GenerationHistoryTracker(Long appId, Long userId) {
            this.appId = appId;
            this.userId = userId;
        }

        private Long appId() {
            return appId;
        }

        private synchronized void recordUserMessage(String message) {
            chatHistoryService.addChatMessage(
                    appId,
                    userId,
                    message,
                    ChatHistoryMessageTypeEnum.USER
            );
            userMessageRecorded = true;
        }

        private synchronized void recordSuccessfulAiReply(String message) {
            if (!userMessageRecorded || successfulAiMessageId != null) {
                throw new IllegalStateException("Generation history state is invalid");
            }
            successfulAiMessageId = chatHistoryService.addChatMessage(
                    appId,
                    userId,
                    message,
                    ChatHistoryMessageTypeEnum.AI
            );
        }

        private synchronized void markCompleted() {
            completed = true;
        }

        private synchronized void recordFailure(Throwable failure) {
            if (!userMessageRecorded || completed || terminalFailureRecorded) {
                return;
            }
            terminalFailureRecorded = true;
            compensateSuccessfulAiMessage();
            chatHistoryService.addAiFailureMessage(appId, userId, failure);
        }

        private synchronized void recordCancellation() {
            if (!userMessageRecorded || completed || terminalFailureRecorded) {
                return;
            }
            terminalFailureRecorded = true;
            compensateSuccessfulAiMessage();
            chatHistoryService.addAiCancellationMessage(appId, userId);
        }

        private void compensateSuccessfulAiMessage() {
            if (successfulAiMessageId == null) {
                return;
            }
            boolean removed = chatHistoryService.removeById(successfulAiMessageId);
            if (!removed) {
                throw new BusinessException(
                        ErrorCode.OPERATION_ERROR,
                        "回滚对话历史失败"
                );
            }
            successfulAiMessageId = null;
        }
    }

    private record KeyResolution(String deployKey, boolean replaceExisting) {
    }
}
