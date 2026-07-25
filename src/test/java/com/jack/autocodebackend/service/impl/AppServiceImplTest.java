package com.jack.autocodebackend.service.impl;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import com.jack.autocodebackend.service.ChatHistoryService;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.ReflectionUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.Serializable;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@SuppressWarnings({"rawtypes", "unchecked"})
class AppServiceImplTest {

    private static final long APP_ID = 2001L;

    private static final long OWNER_ID = 1001L;

    private static final String PREVIEW_URL = "http://127.0.0.1:9332/preview/token/";

    private static final long PREVIEW_EXPIRES_AT = 1_753_405_723_000L;

    private final AppMapper appMapper = mock(AppMapper.class);

    private final AiCodeGeneratorFacade aiCodeGeneratorFacade = mock(AiCodeGeneratorFacade.class);

    private final ObjectProvider<AiCodeGeneratorFacade> aiCodeGeneratorFacadeProvider =
            mock(ObjectProvider.class);

    private final AppDeploymentFileManager deploymentFileManager =
            mock(AppDeploymentFileManager.class);

    private final DeployKeyGenerator deployKeyGenerator = mock(DeployKeyGenerator.class);

    private final AppDeploymentLocalServer deploymentLocalServer =
            mock(AppDeploymentLocalServer.class);

    private final ChatHistoryService chatHistoryService = mock(ChatHistoryService.class);

    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);

    private final TransactionStatus transactionStatus = mock(TransactionStatus.class);

    private final AtomicLong historyIdSequence = new AtomicLong(10_000L);

    private final AppDeploymentProperties deploymentProperties = new AppDeploymentProperties(
            Path.of("tmp/test-code-deploy"), "https://deploy.example.com/"
    );

    private AppServiceImpl appService;

    @BeforeEach
    void setUp() {
        given(aiCodeGeneratorFacadeProvider.getIfAvailable()).willReturn(aiCodeGeneratorFacade);
        given(aiCodeGeneratorFacade.startCodeGeneration(
                any(), any(CodeGenTypeEnum.class), anyLong())).willAnswer(invocation -> {
            String message = invocation.getArgument(0);
            CodeGenTypeEnum type = invocation.getArgument(1);
            Long appId = invocation.getArgument(2);
            Flux<String> stream = aiCodeGeneratorFacade.generateAndSaveCodeStream(
                    message, type, appId);
            return mockGenerationSession(stream);
        });
        given(deploymentLocalServer.issuePreview(anyLong(), any(CodeGenTypeEnum.class)))
                .willReturn(previewAccess());
        given(chatHistoryService.addChatMessage(
                anyLong(), anyLong(), any(), any(ChatHistoryMessageTypeEnum.class)))
                .willAnswer(invocation -> historyIdSequence.incrementAndGet());
        given(chatHistoryService.addAiFailureMessage(anyLong(), anyLong(), any()))
                .willAnswer(invocation -> historyIdSequence.incrementAndGet());
        given(chatHistoryService.addAiCancellationMessage(anyLong(), anyLong()))
                .willAnswer(invocation -> historyIdSequence.incrementAndGet());
        given(chatHistoryService.removeById(anyLong())).willReturn(true);
        given(transactionTemplate.execute(any())).willAnswer(invocation -> {
            TransactionCallback<Boolean> callback = invocation.getArgument(0);
            return callback.doInTransaction(transactionStatus);
        });
        appService = createAppService(transactionTemplate);
    }

    @Test
    void createAppNormalizesPromptAndAppliesServerControlledDefaults() {
        AppAddDTO request = new AppAddDTO();
        request.setInitPrompt("  123456789012345  ");
        given(appMapper.insert(any(App.class))).willAnswer(invocation -> {
            App inserted = invocation.getArgument(0);
            inserted.setId(APP_ID);
            return 1;
        });

        Long result = appService.createApp(request, user(OWNER_ID));

        ArgumentCaptor<App> appCaptor = ArgumentCaptor.forClass(App.class);
        verify(appMapper).insert(appCaptor.capture());
        App inserted = appCaptor.getValue();
        assertThat(result).isEqualTo(APP_ID);
        assertThat(inserted.getInitPrompt()).isEqualTo("123456789012345");
        assertThat(inserted.getAppName()).isEqualTo("123456789012");
        assertThat(inserted.getPriority()).isEqualTo(AppConstant.DEFAULT_APP_PRIORITY);
        assertThat(inserted.getUserId()).isEqualTo(OWNER_ID);
        assertThat(inserted.getCover()).isNull();
        assertThat(inserted.getCodeGenType()).isNull();
        assertThat(inserted.getDeployKey()).isNull();
    }

    @Test
    void createAppNormalizesUnicodeWhitespaceAndKeepsWholeCodePoints() {
        String emoji = "\uD83D\uDE80";
        String normalizedPrompt = emoji.repeat(12) + "build";
        AppAddDTO request = new AppAddDTO();
        request.setInitPrompt("\u3000" + normalizedPrompt + "\u00A0");
        given(appMapper.insert(any(App.class))).willAnswer(invocation -> {
            App inserted = invocation.getArgument(0);
            inserted.setId(APP_ID);
            return 1;
        });

        appService.createApp(request, user(OWNER_ID));

        ArgumentCaptor<App> appCaptor = ArgumentCaptor.forClass(App.class);
        verify(appMapper).insert(appCaptor.capture());
        App inserted = appCaptor.getValue();
        assertThat(inserted.getInitPrompt()).isEqualTo(normalizedPrompt);
        assertThat(inserted.getAppName()).isEqualTo(emoji.repeat(12));
        assertThat(inserted.getAppName().codePointCount(0, inserted.getAppName().length()))
                .isEqualTo(12);
    }

    @Test
    void createAppRejectsMissingOrBlankPromptBeforeWriting() {
        expectBusinessException(() -> appService.createApp(null, user(OWNER_ID)), ErrorCode.PARAMS_ERROR);
        for (String prompt : Arrays.asList(null, "", "   ", "\u00A0\u3000")) {
            AppAddDTO request = new AppAddDTO();
            request.setInitPrompt(prompt);

            expectBusinessException(() -> appService.createApp(request, user(OWNER_ID)),
                    ErrorCode.PARAMS_ERROR);
        }

        verify(appMapper, never()).insert(any(App.class));
    }

    @Test
    void createAppMapsInsertFailureAndMissingGeneratedIdToOperationError() {
        AppAddDTO request = new AppAddDTO();
        request.setInitPrompt("build a dashboard");
        given(appMapper.insert(any(App.class))).willReturn(0, 1);

        expectBusinessException(() -> appService.createApp(request, user(OWNER_ID)),
                ErrorCode.OPERATION_ERROR);
        expectBusinessException(() -> appService.createApp(request, user(OWNER_ID)),
                ErrorCode.OPERATION_ERROR);

        verify(appMapper, org.mockito.Mockito.times(2)).insert(any(App.class));
    }

    @Test
    void initialChatUsesInitPromptAndPersistsTypeOnlyAfterSuccessfulCompletion() {
        App app = existingApp(OWNER_ID);
        app.setInitPrompt("build the initial website");
        Runnable generationCompleted = mock(Runnable.class);
        Runnable generationCommitted = mock(Runnable.class);
        Runnable completedEventEmitted = mock(Runnable.class);
        CodeGenerationSession generationSession = mockGenerationSession(
                Flux.just(" leading space", " and trailing ")
                        .doOnComplete(generationCompleted::run));
        org.mockito.Mockito.doAnswer(invocation -> {
                    Supplier<?> finalization = invocation.getArgument(0);
                    Object result = finalization.get();
                    generationCommitted.run();
                    return result;
                })
                .when(generationSession)
                .commitAfter(any());
        given(appMapper.selectById(APP_ID)).willReturn(app);
        org.mockito.Mockito.doReturn(generationSession)
                .when(aiCodeGeneratorFacade)
                .startCodeGeneration(
                        "build the initial website", CodeGenTypeEnum.MULTI_FILE, APP_ID);
        given(appMapper.update(any(App.class), any(UpdateWrapper.class))).willReturn(1);

        List<AppGenerationEvent> events = new ArrayList<>();
        appService.chatToGenCode(APP_ID, "ignored on first generation", user(OWNER_ID))
                .doOnNext(event -> {
                    events.add(event);
                    if (event instanceof AppGenerationEvent.Completed) {
                        completedEventEmitted.run();
                    }
                })
                .blockLast();

        assertSuccessfulGeneration(events, " leading space", " and trailing ");
        ArgumentCaptor<App> appCaptor = ArgumentCaptor.forClass(App.class);
        ArgumentCaptor<UpdateWrapper<App>> wrapperCaptor = updateWrapperCaptor();
        verify(appMapper).update(appCaptor.capture(), wrapperCaptor.capture());
        assertThat(appCaptor.getValue().getCodeGenType())
                .isEqualTo(CodeGenTypeEnum.MULTI_FILE.getValue());
        assertThat(compactSql(wrapperCaptor.getValue())).contains("codegentypeisnull");
        assertOwnerConstrained(wrapperCaptor.getValue());
        InOrder generationOrder = inOrder(
                chatHistoryService,
                deploymentLocalServer,
                aiCodeGeneratorFacade,
                generationCompleted,
                appMapper,
                generationCommitted,
                completedEventEmitted
        );
        generationOrder.verify(chatHistoryService).addChatMessage(
                APP_ID,
                OWNER_ID,
                "build the initial website",
                ChatHistoryMessageTypeEnum.USER
        );
        generationOrder.verify(deploymentLocalServer).requirePreviewAvailable();
        generationOrder.verify(aiCodeGeneratorFacade).startCodeGeneration(
                "build the initial website", CodeGenTypeEnum.MULTI_FILE, APP_ID);
        generationOrder.verify(generationCompleted).run();
        generationOrder.verify(deploymentLocalServer)
                .issuePreview(APP_ID, CodeGenTypeEnum.MULTI_FILE);
        generationOrder.verify(chatHistoryService).addChatMessage(
                APP_ID,
                OWNER_ID,
                " leading space and trailing ",
                ChatHistoryMessageTypeEnum.AI
        );
        generationOrder.verify(appMapper).update(any(App.class), any(UpdateWrapper.class));
        generationOrder.verify(generationCommitted).run();
        generationOrder.verify(completedEventEmitted).run();
        verify(generationSession).commitAfter(any());
        verify(generationSession).rollback();
    }

    @Test
    void subsequentChatUsesMessageAndStoredCodeGenerationTypeWithoutUpdatingIt() {
        App app = existingApp(OWNER_ID);
        app.setCodeGenType(CodeGenTypeEnum.HTML.getValue());
        given(appMapper.selectById(APP_ID)).willReturn(app);
        given(aiCodeGeneratorFacade.generateAndSaveCodeStream(
                " add a footer ", CodeGenTypeEnum.HTML, APP_ID))
                .willReturn(Flux.just(" <footer>", " </footer> "));

        List<AppGenerationEvent> events = appService.chatToGenCode(
                APP_ID, " add a footer ", user(OWNER_ID)).collectList().block();

        assertSuccessfulGeneration(events, " <footer>", " </footer> ");
        verify(aiCodeGeneratorFacade).generateAndSaveCodeStream(
                " add a footer ", CodeGenTypeEnum.HTML, APP_ID);
        verify(chatHistoryService).addChatMessage(
                APP_ID, OWNER_ID, " add a footer ", ChatHistoryMessageTypeEnum.USER);
        verify(chatHistoryService).addChatMessage(
                APP_ID, OWNER_ID, " <footer> </footer> ", ChatHistoryMessageTypeEnum.AI);
        verify(appMapper, never()).update(any(App.class), any(Wrapper.class));
        verify(deploymentLocalServer).issuePreview(APP_ID, CodeGenTypeEnum.HTML);
    }

    @Test
    void chatRejectsMissingAndForeignApplicationsBeforeCallingFacade() {
        given(appMapper.selectById(APP_ID))
                .willReturn(null, existingApp(OWNER_ID + 1));

        expectBusinessException(
                () -> appService.chatToGenCode(APP_ID, "message", user(OWNER_ID)).blockLast(),
                ErrorCode.NOT_FOUND_ERROR);
        expectBusinessException(
                () -> appService.chatToGenCode(APP_ID, "message", user(OWNER_ID)).blockLast(),
                ErrorCode.NO_AUTH_ERROR);

        verify(aiCodeGeneratorFacade, never())
                .generateAndSaveCodeStream(any(), any(), any());
        verify(deploymentLocalServer, never()).requirePreviewAvailable();
    }

    @Test
    void chatValidatesIdsLoginMessageAndStoredCodeGenerationType() {
        expectBusinessException(
                () -> appService.chatToGenCode(null, "message", user(OWNER_ID)),
                ErrorCode.PARAMS_ERROR);
        expectBusinessException(
                () -> appService.chatToGenCode(APP_ID, "message", new User()),
                ErrorCode.NOT_LOGIN_ERROR);

        App invalidTypeApp = existingApp(OWNER_ID);
        invalidTypeApp.setCodeGenType("unsupported");
        App generatedApp = existingApp(OWNER_ID);
        generatedApp.setCodeGenType(CodeGenTypeEnum.MULTI_FILE.getValue());
        given(appMapper.selectById(APP_ID)).willReturn(invalidTypeApp, generatedApp);

        expectBusinessException(
                () -> appService.chatToGenCode(APP_ID, "message", user(OWNER_ID)).blockLast(),
                ErrorCode.SYSTEM_ERROR);
        expectBusinessException(
                () -> appService.chatToGenCode(APP_ID, "\u00A0\u3000", user(OWNER_ID)).blockLast(),
                ErrorCode.PARAMS_ERROR);

        verify(aiCodeGeneratorFacade, never())
                .generateAndSaveCodeStream(any(), any(), any());
        verify(deploymentLocalServer, never()).requirePreviewAvailable();
    }

    @Test
    void chatReportsUnavailableFacadeAsSystemError() {
        App app = existingApp(OWNER_ID);
        app.setCodeGenType(CodeGenTypeEnum.HTML.getValue());
        given(appMapper.selectById(APP_ID)).willReturn(app);
        given(aiCodeGeneratorFacadeProvider.getIfAvailable()).willReturn(null);

        expectBusinessException(
                () -> appService.chatToGenCode(APP_ID, "message", user(OWNER_ID)).blockLast(),
                ErrorCode.SYSTEM_ERROR);
    }

    @Test
    void previewPreflightFailureStopsBeforeFacadeAndEmitsNoEvents() {
        App app = existingApp(OWNER_ID);
        app.setInitPrompt("build a site");
        IllegalStateException previewUnavailable =
                new IllegalStateException("preview server disabled");
        given(appMapper.selectById(APP_ID)).willReturn(app);
        org.mockito.Mockito.doThrow(previewUnavailable)
                .when(deploymentLocalServer)
                .requirePreviewAvailable();
        List<AppGenerationEvent> received = new ArrayList<>();

        BusinessException thrown = expectBusinessException(
                () -> appService.chatToGenCode(APP_ID, null, user(OWNER_ID))
                        .doOnNext(received::add)
                        .blockLast(),
                ErrorCode.OPERATION_ERROR
        );

        assertThat(thrown.getCause()).isSameAs(previewUnavailable);
        assertThat(received).isEmpty();
        InOrder failureOrder = inOrder(chatHistoryService, deploymentLocalServer);
        failureOrder.verify(chatHistoryService).addChatMessage(
                APP_ID, OWNER_ID, "build a site", ChatHistoryMessageTypeEnum.USER);
        failureOrder.verify(deploymentLocalServer).requirePreviewAvailable();
        failureOrder.verify(chatHistoryService).addAiFailureMessage(
                eq(APP_ID), eq(OWNER_ID), any(BusinessException.class));
        verify(aiCodeGeneratorFacadeProvider, never()).getIfAvailable();
        verifyNoInteractions(aiCodeGeneratorFacade);
        verify(appMapper, never()).update(any(App.class), any(Wrapper.class));
        verify(deploymentLocalServer, never()).issuePreview(anyLong(), any());
    }

    @Test
    void failedInitialStreamDoesNotPersistCodeGenerationType() {
        App app = existingApp(OWNER_ID);
        app.setInitPrompt("build a site");
        RuntimeException generationFailure = new RuntimeException("generation failed");
        given(appMapper.selectById(APP_ID)).willReturn(app);
        given(aiCodeGeneratorFacade.generateAndSaveCodeStream(
                "build a site", CodeGenTypeEnum.MULTI_FILE, APP_ID))
                .willReturn(Flux.concat(Flux.just("partial "), Flux.error(generationFailure)));

        Throwable thrown = catchThrowable(() -> appService.chatToGenCode(
                APP_ID, null, user(OWNER_ID)).blockLast());

        assertThat(thrown).isSameAs(generationFailure);
        verify(chatHistoryService).addChatMessage(
                APP_ID, OWNER_ID, "build a site", ChatHistoryMessageTypeEnum.USER);
        verify(chatHistoryService).addAiFailureMessage(APP_ID, OWNER_ID, generationFailure);
        verify(appMapper, never()).update(any(App.class), any(Wrapper.class));
        verify(deploymentLocalServer, never()).issuePreview(anyLong(), any());
    }

    @Test
    void previewFailureRollsBackGeneratedDirectoryPublication() {
        App app = existingApp(OWNER_ID);
        app.setCodeGenType(CodeGenTypeEnum.HTML.getValue());
        CodeGenerationSession generationSession = mockGenerationSession(Flux.just("new version"));
        IllegalStateException previewFailure = new IllegalStateException("preview failed");
        given(appMapper.selectById(APP_ID)).willReturn(app);
        org.mockito.Mockito.doReturn(generationSession)
                .when(aiCodeGeneratorFacade)
                .startCodeGeneration("refine", CodeGenTypeEnum.HTML, APP_ID);
        given(deploymentLocalServer.issuePreview(APP_ID, CodeGenTypeEnum.HTML))
                .willThrow(previewFailure);

        BusinessException thrown = expectBusinessException(
                () -> appService.chatToGenCode(APP_ID, "refine", user(OWNER_ID)).blockLast(),
                ErrorCode.OPERATION_ERROR);

        assertThat(thrown.getCause()).isSameAs(previewFailure);
        verify(generationSession).commitAfter(any());
        verify(generationSession).rollback();
        verify(appMapper, never()).update(any(App.class), any(Wrapper.class));
    }

    @Test
    void failedInitialTypePersistenceRevokesIssuedPreviewAndOmitsCompletedEvent() {
        App app = existingApp(OWNER_ID);
        app.setInitPrompt("build a site");
        given(appMapper.selectById(APP_ID)).willReturn(app);
        given(aiCodeGeneratorFacade.generateAndSaveCodeStream(
                "build a site", CodeGenTypeEnum.MULTI_FILE, APP_ID))
                .willReturn(Flux.just("complete code"), Flux.just("retry code"));
        given(appMapper.update(any(App.class), any(UpdateWrapper.class))).willReturn(0, 1);
        given(chatHistoryService.addChatMessage(
                APP_ID, OWNER_ID, "complete code", ChatHistoryMessageTypeEnum.AI))
                .willReturn(7001L);
        given(chatHistoryService.addChatMessage(
                APP_ID, OWNER_ID, "retry code", ChatHistoryMessageTypeEnum.AI))
                .willReturn(7002L);
        List<AppGenerationEvent> received = new ArrayList<>();

        BusinessException thrown = expectBusinessException(
                () -> appService.chatToGenCode(APP_ID, null, user(OWNER_ID))
                        .doOnNext(received::add)
                        .blockLast(),
                ErrorCode.OPERATION_ERROR);

        assertThat(thrown.getMessage()).contains("保存应用代码生成类型失败");
        assertThat(received)
                .containsExactly(new AppGenerationEvent.Content("complete code"));
        InOrder failureOrder = inOrder(
                deploymentLocalServer, chatHistoryService, appMapper);
        failureOrder.verify(deploymentLocalServer)
                .issuePreview(APP_ID, CodeGenTypeEnum.MULTI_FILE);
        failureOrder.verify(chatHistoryService).addChatMessage(
                APP_ID, OWNER_ID, "complete code", ChatHistoryMessageTypeEnum.AI);
        failureOrder.verify(appMapper).update(any(App.class), any(UpdateWrapper.class));
        failureOrder.verify(deploymentLocalServer).revokePreview(APP_ID);
        failureOrder.verify(chatHistoryService).removeById(7001L);
        failureOrder.verify(chatHistoryService).addAiFailureMessage(
                APP_ID, OWNER_ID, thrown);

        assertSuccessfulGeneration(appService.chatToGenCode(
                APP_ID, "ignored again", user(OWNER_ID)).collectList().block(), "retry code");
        verify(aiCodeGeneratorFacade, times(2)).generateAndSaveCodeStream(
                "build a site", CodeGenTypeEnum.MULTI_FILE, APP_ID);
        verify(appMapper, times(2)).update(any(App.class), any(UpdateWrapper.class));
        verify(deploymentLocalServer, times(2))
                .issuePreview(APP_ID, CodeGenTypeEnum.MULTI_FILE);
        verify(deploymentLocalServer).revokePreview(APP_ID);
    }

    @Test
    void initialPreviewFailureDoesNotPersistTypeOrEmitCompletedAndRetryUsesInitPrompt() {
        App app = existingApp(OWNER_ID);
        app.setInitPrompt("build a site");
        IllegalStateException previewFailure = new IllegalStateException("preview failed");
        given(appMapper.selectById(APP_ID)).willReturn(app);
        given(aiCodeGeneratorFacade.generateAndSaveCodeStream(
                "build a site", CodeGenTypeEnum.MULTI_FILE, APP_ID))
                .willReturn(Flux.just(" complete chunk "), Flux.just(" retry chunk "));
        given(appMapper.update(any(App.class), any(UpdateWrapper.class))).willReturn(1);
        given(deploymentLocalServer.issuePreview(APP_ID, CodeGenTypeEnum.MULTI_FILE))
                .willThrow(previewFailure);
        List<AppGenerationEvent> received = new ArrayList<>();

        BusinessException thrown = expectBusinessException(
                () -> appService.chatToGenCode(APP_ID, "ignored", user(OWNER_ID))
                        .doOnNext(received::add)
                        .blockLast(),
                ErrorCode.OPERATION_ERROR
        );

        assertThat(thrown.getCause()).isSameAs(previewFailure);
        assertThat(received)
                .containsExactly(new AppGenerationEvent.Content(" complete chunk "));
        verify(appMapper, never()).update(any(App.class), any(Wrapper.class));
        verify(deploymentLocalServer, never()).revokePreview(anyLong());

        org.mockito.BDDMockito.willReturn(previewAccess())
                .given(deploymentLocalServer)
                .issuePreview(APP_ID, CodeGenTypeEnum.MULTI_FILE);
        assertSuccessfulGeneration(appService.chatToGenCode(
                APP_ID, "still ignored", user(OWNER_ID)).collectList().block(), " retry chunk ");
        verify(aiCodeGeneratorFacade, times(2)).generateAndSaveCodeStream(
                "build a site", CodeGenTypeEnum.MULTI_FILE, APP_ID);
        verify(appMapper).update(any(App.class), any(UpdateWrapper.class));
        verify(deploymentLocalServer, times(2))
                .issuePreview(APP_ID, CodeGenTypeEnum.MULTI_FILE);
    }

    @Test
    void cancelledInitialStreamDoesNotPersistCodeGenerationType() {
        App app = existingApp(OWNER_ID);
        app.setInitPrompt("build a site");
        Sinks.Many<String> codeSink = Sinks.many().unicast().onBackpressureBuffer();
        CodeGenerationSession generationSession = mockGenerationSession(codeSink.asFlux());
        given(appMapper.selectById(APP_ID)).willReturn(app);
        org.mockito.Mockito.doReturn(generationSession)
                .when(aiCodeGeneratorFacade)
                .startCodeGeneration("build a site", CodeGenTypeEnum.MULTI_FILE, APP_ID);

        Disposable subscription = appService.chatToGenCode(
                APP_ID, null, user(OWNER_ID)).subscribe();
        codeSink.tryEmitNext("partial");
        subscription.dispose();

        verify(appMapper, never()).update(any(App.class), any(Wrapper.class));
        verify(deploymentLocalServer, never()).issuePreview(anyLong(), any());
        verify(generationSession, never()).commitAfter(any());
        verify(generationSession).rollback();
        verify(chatHistoryService).addChatMessage(
                APP_ID, OWNER_ID, "build a site", ChatHistoryMessageTypeEnum.USER);
        verify(chatHistoryService).addAiCancellationMessage(APP_ID, OWNER_ID);
        verify(chatHistoryService, never()).addAiFailureMessage(anyLong(), anyLong(), any());
        verify(chatHistoryService, never()).addChatMessage(
                APP_ID, OWNER_ID, "partial", ChatHistoryMessageTypeEnum.AI);
    }

    @Test
    void userHistoryWriteFailureStopsBeforeProviderAndReleasesTheAppLock() {
        App app = generatedApp(APP_ID, OWNER_ID, CodeGenTypeEnum.HTML);
        BusinessException historyFailure = new BusinessException(
                ErrorCode.OPERATION_ERROR, "history write failed");
        given(appMapper.selectById(APP_ID)).willReturn(app);
        given(chatHistoryService.addChatMessage(
                APP_ID, OWNER_ID, "message", ChatHistoryMessageTypeEnum.USER))
                .willThrow(historyFailure)
                .willReturn(7101L);
        given(aiCodeGeneratorFacade.generateAndSaveCodeStream(
                "message", CodeGenTypeEnum.HTML, APP_ID))
                .willReturn(Flux.just("retry"));

        Throwable thrown = catchThrowable(() -> appService.chatToGenCode(
                APP_ID, "message", user(OWNER_ID)).blockLast());

        assertThat(thrown).isSameAs(historyFailure);
        verify(deploymentLocalServer, never()).requirePreviewAvailable();
        verify(aiCodeGeneratorFacadeProvider, never()).getIfAvailable();
        verify(chatHistoryService, never()).addAiFailureMessage(anyLong(), anyLong(), any());

        assertSuccessfulGeneration(appService.chatToGenCode(
                APP_ID, "message", user(OWNER_ID)).collectList().block(), "retry");
        verify(aiCodeGeneratorFacade).generateAndSaveCodeStream(
                "message", CodeGenTypeEnum.HTML, APP_ID);
    }

    @Test
    void successfulAiHistoryWriteFailureOmitsDoneRecordsFailureAndAllowsRetry() {
        App app = generatedApp(APP_ID, OWNER_ID, CodeGenTypeEnum.HTML);
        IllegalStateException historyFailure = new IllegalStateException("history write failed");
        given(appMapper.selectById(APP_ID)).willReturn(app);
        given(aiCodeGeneratorFacade.generateAndSaveCodeStream(
                "message", CodeGenTypeEnum.HTML, APP_ID))
                .willReturn(Flux.just("first"), Flux.just("retry"));
        given(chatHistoryService.addChatMessage(
                APP_ID, OWNER_ID, "first", ChatHistoryMessageTypeEnum.AI))
                .willThrow(historyFailure);
        List<AppGenerationEvent> received = new ArrayList<>();

        Throwable thrown = catchThrowable(() -> appService.chatToGenCode(
                        APP_ID, "message", user(OWNER_ID))
                .doOnNext(received::add)
                .blockLast());

        assertThat(thrown).isSameAs(historyFailure);
        assertThat(received).containsExactly(new AppGenerationEvent.Content("first"));
        verify(deploymentLocalServer).revokePreview(APP_ID);
        verify(chatHistoryService).addAiFailureMessage(APP_ID, OWNER_ID, historyFailure);
        verify(chatHistoryService, never()).removeById(anyLong());

        assertSuccessfulGeneration(appService.chatToGenCode(
                APP_ID, "message", user(OWNER_ID)).collectList().block(), "retry");
        verify(aiCodeGeneratorFacade, times(2)).generateAndSaveCodeStream(
                "message", CodeGenTypeEnum.HTML, APP_ID);
    }

    @Test
    void failureHistoryWriteDoesNotMaskProviderError() {
        App app = generatedApp(APP_ID, OWNER_ID, CodeGenTypeEnum.HTML);
        IllegalStateException providerFailure = new IllegalStateException("provider failed");
        IllegalStateException historyFailure = new IllegalStateException("history failed");
        given(appMapper.selectById(APP_ID)).willReturn(app);
        given(aiCodeGeneratorFacade.generateAndSaveCodeStream(
                "message", CodeGenTypeEnum.HTML, APP_ID))
                .willReturn(Flux.error(providerFailure));
        given(chatHistoryService.addAiFailureMessage(APP_ID, OWNER_ID, providerFailure))
                .willThrow(historyFailure);

        Throwable thrown = catchThrowable(() -> appService.chatToGenCode(
                APP_ID, "message", user(OWNER_ID)).blockLast());

        assertThat(thrown).isSameAs(providerFailure);
        assertThat(providerFailure.getSuppressed()).contains(historyFailure);
        verify(deploymentLocalServer, never()).issuePreview(anyLong(), any());
    }

    @Test
    void createAppPreviewValidatesOwnerAndReturnsEpochMillis() {
        App app = generatedApp(APP_ID, OWNER_ID, CodeGenTypeEnum.MULTI_FILE);
        given(appMapper.selectById(APP_ID)).willReturn(app);

        AppPreviewVO preview = appService.createAppPreview(APP_ID, user(OWNER_ID));

        assertThat(preview.getPreviewUrl()).isEqualTo(PREVIEW_URL);
        assertThat(preview.getExpiresAt()).isEqualTo(PREVIEW_EXPIRES_AT);
        verify(deploymentLocalServer)
                .issuePreview(APP_ID, CodeGenTypeEnum.MULTI_FILE);
    }

    @Test
    void createAppPreviewRejectsInvalidIdentityOwnershipAndUngeneratedApps() {
        expectBusinessException(() -> appService.createAppPreview(null, user(OWNER_ID)),
                ErrorCode.PARAMS_ERROR);
        expectBusinessException(() -> appService.createAppPreview(APP_ID, new User()),
                ErrorCode.NOT_LOGIN_ERROR);

        App foreignApp = generatedApp(APP_ID, OWNER_ID + 1, CodeGenTypeEnum.HTML);
        App ungeneratedApp = existingApp(OWNER_ID);
        given(appMapper.selectById(APP_ID)).willReturn(null, foreignApp, ungeneratedApp);

        expectBusinessException(() -> appService.createAppPreview(APP_ID, user(OWNER_ID)),
                ErrorCode.NOT_FOUND_ERROR);
        expectBusinessException(() -> appService.createAppPreview(APP_ID, user(OWNER_ID)),
                ErrorCode.NO_AUTH_ERROR);
        expectBusinessException(() -> appService.createAppPreview(APP_ID, user(OWNER_ID)),
                ErrorCode.OPERATION_ERROR);

        verify(deploymentLocalServer, never()).issuePreview(anyLong(), any());
    }

    @Test
    void concurrentSubscriptionsForSameAppAreRejectedAndCancellationReleasesLock() {
        App app = existingApp(OWNER_ID);
        app.setCodeGenType(CodeGenTypeEnum.HTML.getValue());
        Sinks.Many<String> codeSink = Sinks.many().unicast().onBackpressureBuffer();
        given(appMapper.selectById(APP_ID)).willReturn(app);
        given(aiCodeGeneratorFacade.generateAndSaveCodeStream(
                "message", CodeGenTypeEnum.HTML, APP_ID))
                .willReturn(codeSink.asFlux(), Flux.just("retry"));

        Disposable firstSubscription = appService.chatToGenCode(
                APP_ID, "message", user(OWNER_ID)).subscribe();
        expectBusinessException(
                () -> appService.chatToGenCode(APP_ID, "message", user(OWNER_ID)).blockLast(),
                ErrorCode.OPERATION_ERROR);
        firstSubscription.dispose();

        assertSuccessfulGeneration(appService.chatToGenCode(
                APP_ID, "message", user(OWNER_ID)).collectList().block(), "retry");
        verify(aiCodeGeneratorFacade, times(2)).generateAndSaveCodeStream(
                eq("message"), eq(CodeGenTypeEnum.HTML), eq(APP_ID));
        verify(deploymentLocalServer).issuePreview(APP_ID, CodeGenTypeEnum.HTML);
    }

    @Test
    void deletionIsRejectedDuringGenerationAndAllowedAfterCancellation() {
        App app = existingApp(OWNER_ID);
        app.setCodeGenType(CodeGenTypeEnum.HTML.getValue());
        Sinks.Many<String> codeSink = Sinks.many().unicast().onBackpressureBuffer();
        given(appMapper.selectById(APP_ID)).willReturn(app);
        given(aiCodeGeneratorFacade.generateAndSaveCodeStream(
                "message", CodeGenTypeEnum.HTML, APP_ID))
                .willReturn(codeSink.asFlux());
        given(appMapper.delete(any(QueryWrapper.class))).willReturn(1);

        Disposable generation = appService.chatToGenCode(
                APP_ID, "message", user(OWNER_ID)).subscribe();

        expectBusinessException(
                () -> appService.deleteAppByUser(APP_ID, user(OWNER_ID)),
                ErrorCode.OPERATION_ERROR);
        expectBusinessException(
                () -> appService.deleteAppByAdmin(APP_ID),
                ErrorCode.OPERATION_ERROR);
        expectBusinessException(
                () -> appService.deployApp(APP_ID, user(OWNER_ID)),
                ErrorCode.OPERATION_ERROR);
        verify(appMapper, never()).delete(any(Wrapper.class));
        verify(appMapper, never()).deleteById(any(Serializable.class));
        verify(deploymentFileManager, never()).stage(any(), any());

        generation.dispose();

        assertThat(appService.deleteAppByUser(APP_ID, user(OWNER_ID))).isTrue();
        verify(appMapper).delete(any(QueryWrapper.class));
    }

    @Test
    void deployValidatesIdentityExistenceOwnershipAndGenerationTypeBeforeStaging() {
        expectBusinessException(() -> appService.deployApp(null, user(OWNER_ID)),
                ErrorCode.PARAMS_ERROR);
        expectBusinessException(() -> appService.deployApp(APP_ID, new User()),
                ErrorCode.NOT_LOGIN_ERROR);
        verify(appMapper, never()).selectById(any());

        App foreignApp = generatedApp(APP_ID, OWNER_ID + 1, CodeGenTypeEnum.HTML);
        App unsupportedApp = existingApp(OWNER_ID);
        unsupportedApp.setCodeGenType("unsupported");
        given(appMapper.selectById(APP_ID)).willReturn(null, foreignApp, unsupportedApp);

        expectBusinessException(() -> appService.deployApp(APP_ID, user(OWNER_ID)),
                ErrorCode.NOT_FOUND_ERROR);
        User administrator = user(OWNER_ID);
        administrator.setUserRole("admin");
        expectBusinessException(() -> appService.deployApp(APP_ID, administrator),
                ErrorCode.NO_AUTH_ERROR);
        expectBusinessException(() -> appService.deployApp(APP_ID, user(OWNER_ID)),
                ErrorCode.OPERATION_ERROR);

        verify(deploymentFileManager, never()).stage(any(), any());
        verifyNoInteractions(deployKeyGenerator);
    }

    @Test
    void deployRejectsIncompleteGeneratedSourceBeforeAllocatingKey() {
        App app = generatedApp(APP_ID, OWNER_ID, CodeGenTypeEnum.MULTI_FILE);
        BusinessException sourceFailure = new BusinessException(
                ErrorCode.OPERATION_ERROR, "missing generated files");
        given(appMapper.selectById(APP_ID)).willReturn(app);
        given(deploymentFileManager.stage(CodeGenTypeEnum.MULTI_FILE, APP_ID))
                .willThrow(sourceFailure);

        BusinessException thrown = expectBusinessException(
                () -> appService.deployApp(APP_ID, user(OWNER_ID)),
                ErrorCode.OPERATION_ERROR);

        assertThat(thrown).isSameAs(sourceFailure);
        verifyNoInteractions(deployKeyGenerator);
        verify(appMapper, never()).update(any(App.class), any(Wrapper.class));
    }

    @Test
    void firstDeploymentStagesBeforeReservingKeyAndReturnsCommittedMetadata() {
        String deployKey = "Ab3D9z";
        App app = generatedApp(APP_ID, OWNER_ID, CodeGenTypeEnum.HTML);
        DeploymentHandles handles = stubPublication(
                CodeGenTypeEnum.HTML, APP_ID, deployKey, false);
        given(appMapper.selectById(APP_ID)).willReturn(app);
        given(deployKeyGenerator.generate()).willReturn(deployKey);
        given(deploymentFileManager.isTargetAvailableForNewKey(deployKey)).willReturn(true);
        given(appMapper.update(any(App.class), any(UpdateWrapper.class))).willReturn(1);

        AppDeployVO result = appService.deployApp(APP_ID, user(OWNER_ID));

        assertThat(result.getDeployKey()).isEqualTo(deployKey);
        assertThat(result.getDeployUrl()).isEqualTo(
                "https://deploy.example.com/" + deployKey + "/");
        assertThat(result.getDeployedTime()).isNotNull();
        assertThat(result.getDeployedTime().getTime() % 1_000L).isZero();

        ArgumentCaptor<App> updateCaptor = ArgumentCaptor.forClass(App.class);
        ArgumentCaptor<UpdateWrapper<App>> wrapperCaptor = updateWrapperCaptor();
        verify(appMapper, times(2)).update(updateCaptor.capture(), wrapperCaptor.capture());
        List<App> updates = updateCaptor.getAllValues();
        assertThat(updates.get(0).getDeployKey()).isEqualTo(deployKey);
        assertThat(updates.get(0).getDeployedTime()).isNull();
        assertThat(updates.get(1).getDeployKey()).isNull();
        assertThat(updates.get(1).getDeployedTime()).isEqualTo(result.getDeployedTime());
        assertThat(compactSql(wrapperCaptor.getAllValues().get(0)))
                .contains("deploykeyisnull");
        assertOwnerConstrained(wrapperCaptor.getAllValues().get(0));
        assertThat(compactSql(wrapperCaptor.getAllValues().get(1)))
                .contains("deploykey=");
        assertOwnerConstrained(wrapperCaptor.getAllValues().get(1));
        assertThat(wrapperParameters(wrapperCaptor.getAllValues().get(1)))
                .containsValue(deployKey);

        InOrder order = inOrder(
                appMapper,
                deploymentFileManager,
                deployKeyGenerator,
                handles.staged(),
                handles.published()
        );
        order.verify(appMapper).selectById(APP_ID);
        order.verify(deploymentFileManager).stage(CodeGenTypeEnum.HTML, APP_ID);
        order.verify(deployKeyGenerator).generate();
        order.verify(deploymentFileManager).isTargetAvailableForNewKey(deployKey);
        order.verify(appMapper).update(any(App.class), any(UpdateWrapper.class));
        order.verify(handles.staged()).publishNew(deployKey);
        order.verify(appMapper).update(any(App.class), any(UpdateWrapper.class));
        order.verify(handles.published()).commit();
        verify(handles.staged()).close();
        verify(handles.published()).close();
    }

    @Test
    void redeploymentReusesStableKeyAndAdvancesSecondPrecisionTimestamp() {
        String deployKey = "Reuse1";
        Date previousDeployedTime = new Date(System.currentTimeMillis() + 30_123L);
        App app = generatedApp(APP_ID, OWNER_ID, CodeGenTypeEnum.MULTI_FILE);
        app.setDeployKey(deployKey);
        app.setDeployedTime(previousDeployedTime);
        DeploymentHandles handles = stubPublication(
                CodeGenTypeEnum.MULTI_FILE, APP_ID, deployKey, true);
        given(appMapper.selectById(APP_ID)).willReturn(app);
        given(appMapper.update(any(App.class), any(UpdateWrapper.class))).willReturn(1);

        AppDeployVO result = appService.deployApp(APP_ID, user(OWNER_ID));

        assertThat(result.getDeployKey()).isEqualTo(deployKey);
        assertThat(result.getDeployedTime()).isAfter(previousDeployedTime);
        assertThat(result.getDeployedTime().getTime() % 1_000L).isZero();
        verifyNoInteractions(deployKeyGenerator);
        verify(handles.staged()).publishReplacement(deployKey);
        verify(handles.staged(), never()).publishNew(any());
        verify(handles.published()).commit();

        ArgumentCaptor<App> updateCaptor = ArgumentCaptor.forClass(App.class);
        verify(appMapper).update(updateCaptor.capture(), any(UpdateWrapper.class));
        assertThat(updateCaptor.getValue().getDeployKey()).isNull();
        assertThat(updateCaptor.getValue().getDeployedTime())
                .isEqualTo(result.getDeployedTime());
    }

    @Test
    void keyAllocationSkipsOrphanTargetAndRetriesDatabaseCollision() {
        String orphanKey = "ORPH01";
        String duplicateKey = "DUP001";
        String deployKey = "NEW001";
        App app = generatedApp(APP_ID, OWNER_ID, CodeGenTypeEnum.HTML);
        DeploymentHandles handles = stubPublication(
                CodeGenTypeEnum.HTML, APP_ID, deployKey, false);
        given(appMapper.selectById(APP_ID)).willReturn(app);
        given(deployKeyGenerator.generate()).willReturn(orphanKey, duplicateKey, deployKey);
        given(deploymentFileManager.isTargetAvailableForNewKey(orphanKey)).willReturn(false);
        given(deploymentFileManager.isTargetAvailableForNewKey(duplicateKey)).willReturn(true);
        given(deploymentFileManager.isTargetAvailableForNewKey(deployKey)).willReturn(true);
        given(appMapper.update(any(App.class), any(UpdateWrapper.class)))
                .willThrow(new DuplicateKeyException("collision"))
                .willReturn(1, 1);

        AppDeployVO result = appService.deployApp(APP_ID, user(OWNER_ID));

        assertThat(result.getDeployKey()).isEqualTo(deployKey);
        verify(deployKeyGenerator, times(3)).generate();
        verify(appMapper, times(3)).update(any(App.class), any(UpdateWrapper.class));
        verify(handles.staged()).publishNew(deployKey);
        verify(handles.staged(), never()).publishNew(orphanKey);
        verify(handles.staged(), never()).publishNew(duplicateKey);
    }

    @Test
    void keyAllocationExhaustionCleansStagingWithoutPublishing() {
        String orphanKey = "ORPH01";
        App app = generatedApp(APP_ID, OWNER_ID, CodeGenTypeEnum.HTML);
        StagedDeployment staged = mock(StagedDeployment.class);
        given(appMapper.selectById(APP_ID)).willReturn(app);
        given(deploymentFileManager.stage(CodeGenTypeEnum.HTML, APP_ID)).willReturn(staged);
        given(deployKeyGenerator.generate()).willReturn(orphanKey);
        given(deploymentFileManager.isTargetAvailableForNewKey(orphanKey)).willReturn(false);

        expectBusinessException(() -> appService.deployApp(APP_ID, user(OWNER_ID)),
                ErrorCode.OPERATION_ERROR);

        verify(deployKeyGenerator, times(10)).generate();
        verify(appMapper, never()).update(any(App.class), any(Wrapper.class));
        verify(staged, never()).publishNew(any());
        verify(staged, never()).publishReplacement(any());
        verify(staged).close();
    }

    @Test
    void conditionalKeyRaceReloadsAndReusesConcurrentAssignment() {
        String candidate = "TRY001";
        String assignedKey = "RACE01";
        Date concurrentDeployedTime = new Date(System.currentTimeMillis() + 60_111L);
        App app = generatedApp(APP_ID, OWNER_ID, CodeGenTypeEnum.HTML);
        App reloadedApp = generatedApp(APP_ID, OWNER_ID, CodeGenTypeEnum.HTML);
        reloadedApp.setDeployKey(assignedKey);
        reloadedApp.setDeployedTime(concurrentDeployedTime);
        DeploymentHandles handles = stubPublication(
                CodeGenTypeEnum.HTML, APP_ID, assignedKey, true);
        given(appMapper.selectById(APP_ID)).willReturn(app, reloadedApp);
        given(deployKeyGenerator.generate()).willReturn(candidate);
        given(deploymentFileManager.isTargetAvailableForNewKey(candidate)).willReturn(true);
        given(appMapper.update(any(App.class), any(UpdateWrapper.class))).willReturn(0, 1);

        AppDeployVO result = appService.deployApp(APP_ID, user(OWNER_ID));

        assertThat(result.getDeployKey()).isEqualTo(assignedKey);
        assertThat(result.getDeployedTime()).isAfter(concurrentDeployedTime);
        verify(handles.staged()).publishReplacement(assignedKey);
        verify(handles.staged(), never()).publishNew(candidate);
    }

    @Test
    void nonCollisionKeyPersistenceFailureCleansStagingAndDoesNotPublish() {
        String deployKey = "FAIL01";
        RuntimeException databaseFailure = new IllegalStateException("database unavailable");
        App app = generatedApp(APP_ID, OWNER_ID, CodeGenTypeEnum.HTML);
        StagedDeployment staged = mock(StagedDeployment.class);
        given(appMapper.selectById(APP_ID)).willReturn(app);
        given(deploymentFileManager.stage(CodeGenTypeEnum.HTML, APP_ID)).willReturn(staged);
        given(deployKeyGenerator.generate()).willReturn(deployKey);
        given(deploymentFileManager.isTargetAvailableForNewKey(deployKey)).willReturn(true);
        given(appMapper.update(any(App.class), any(UpdateWrapper.class)))
                .willThrow(databaseFailure);

        BusinessException thrown = expectBusinessException(
                () -> appService.deployApp(APP_ID, user(OWNER_ID)),
                ErrorCode.OPERATION_ERROR);

        assertThat(thrown.getCause()).isSameAs(databaseFailure);
        verify(staged, never()).publishNew(any());
        verify(staged, never()).publishReplacement(any());
        verify(staged).close();
    }

    @Test
    void firstPublicationFailureRetainsReservedKeyButDoesNotWriteTimestamp() {
        String deployKey = "FILE01";
        App app = generatedApp(APP_ID, OWNER_ID, CodeGenTypeEnum.HTML);
        StagedDeployment staged = mock(StagedDeployment.class);
        BusinessException publishFailure = new BusinessException(
                ErrorCode.OPERATION_ERROR, "publish failed");
        given(appMapper.selectById(APP_ID)).willReturn(app);
        given(deploymentFileManager.stage(CodeGenTypeEnum.HTML, APP_ID)).willReturn(staged);
        given(deployKeyGenerator.generate()).willReturn(deployKey);
        given(deploymentFileManager.isTargetAvailableForNewKey(deployKey)).willReturn(true);
        given(appMapper.update(any(App.class), any(UpdateWrapper.class))).willReturn(1);
        given(staged.publishNew(deployKey)).willThrow(publishFailure);

        BusinessException thrown = expectBusinessException(
                () -> appService.deployApp(APP_ID, user(OWNER_ID)),
                ErrorCode.OPERATION_ERROR);

        assertThat(thrown).isSameAs(publishFailure);
        verify(appMapper).update(any(App.class), any(UpdateWrapper.class));
        verify(staged).close();
    }

    @Test
    void zeroRowMetadataUpdateRollsBackPublication() {
        String deployKey = "ROLL01";
        App app = generatedApp(APP_ID, OWNER_ID, CodeGenTypeEnum.HTML);
        app.setDeployKey(deployKey);
        DeploymentHandles handles = stubPublication(
                CodeGenTypeEnum.HTML, APP_ID, deployKey, true);
        given(appMapper.selectById(APP_ID)).willReturn(app);
        given(appMapper.update(any(App.class), any(UpdateWrapper.class))).willReturn(0);

        expectBusinessException(() -> appService.deployApp(APP_ID, user(OWNER_ID)),
                ErrorCode.OPERATION_ERROR);

        verify(handles.published()).rollback();
        verify(handles.published(), never()).commit();
        verify(handles.published(), never()).preserve();
    }

    @Test
    void metadataExceptionWhoseTimestampCommittedReturnsSuccessAndCommitsPublication() {
        String deployKey = "DONE01";
        RuntimeException reportedFailure = new IllegalStateException("lost database response");
        App app = generatedApp(APP_ID, OWNER_ID, CodeGenTypeEnum.HTML);
        app.setDeployKey(deployKey);
        App reloadedApp = generatedApp(APP_ID, OWNER_ID, CodeGenTypeEnum.HTML);
        reloadedApp.setDeployKey(deployKey);
        DeploymentHandles handles = stubPublication(
                CodeGenTypeEnum.HTML, APP_ID, deployKey, true);
        given(appMapper.selectById(APP_ID)).willReturn(app, reloadedApp);
        given(appMapper.update(any(App.class), any(UpdateWrapper.class)))
                .willAnswer(invocation -> {
                    App metadataUpdate = invocation.getArgument(0);
                    reloadedApp.setDeployedTime(metadataUpdate.getDeployedTime());
                    throw reportedFailure;
                });

        AppDeployVO result = appService.deployApp(APP_ID, user(OWNER_ID));

        assertThat(result.getDeployedTime()).isEqualTo(reloadedApp.getDeployedTime());
        verify(handles.published()).commit();
        verify(handles.published(), never()).rollback();
        verify(handles.published(), never()).preserve();
    }

    @Test
    void metadataExceptionConfirmedNotCommittedRollsBackPublication() {
        String deployKey = "OLD001";
        RuntimeException databaseFailure = new IllegalStateException("metadata update failed");
        App app = generatedApp(APP_ID, OWNER_ID, CodeGenTypeEnum.HTML);
        app.setDeployKey(deployKey);
        app.setDeployedTime(new Date(1_000L));
        App reloadedApp = generatedApp(APP_ID, OWNER_ID, CodeGenTypeEnum.HTML);
        reloadedApp.setDeployKey(deployKey);
        reloadedApp.setDeployedTime(new Date(1_000L));
        DeploymentHandles handles = stubPublication(
                CodeGenTypeEnum.HTML, APP_ID, deployKey, true);
        given(appMapper.selectById(APP_ID)).willReturn(app, reloadedApp);
        given(appMapper.update(any(App.class), any(UpdateWrapper.class)))
                .willThrow(databaseFailure);

        BusinessException thrown = expectBusinessException(
                () -> appService.deployApp(APP_ID, user(OWNER_ID)),
                ErrorCode.OPERATION_ERROR);

        assertThat(thrown.getCause()).isSameAs(databaseFailure);
        verify(handles.published()).rollback();
        verify(handles.published(), never()).commit();
        verify(handles.published(), never()).preserve();
    }

    @Test
    void unreadableMetadataOutcomePreservesBothFilesystemSnapshots() {
        String deployKey = "UNKN01";
        RuntimeException metadataFailure = new IllegalStateException("metadata write failed");
        RuntimeException readFailure = new IllegalStateException("metadata read failed");
        App app = generatedApp(APP_ID, OWNER_ID, CodeGenTypeEnum.HTML);
        app.setDeployKey(deployKey);
        DeploymentHandles handles = stubPublication(
                CodeGenTypeEnum.HTML, APP_ID, deployKey, true);
        given(appMapper.selectById(APP_ID)).willReturn(app).willThrow(readFailure);
        given(appMapper.update(any(App.class), any(UpdateWrapper.class)))
                .willThrow(metadataFailure);

        BusinessException thrown = expectBusinessException(
                () -> appService.deployApp(APP_ID, user(OWNER_ID)),
                ErrorCode.OPERATION_ERROR);

        assertThat(thrown.getCause()).isSameAs(metadataFailure);
        assertThat(metadataFailure.getSuppressed()).contains(readFailure);
        verify(handles.published()).preserve();
        verify(handles.published(), never()).rollback();
        verify(handles.published(), never()).commit();
    }

    @Test
    void deploymentGuardRejectsSameAppOperationsButAllowsAnotherApp() throws Exception {
        long otherAppId = APP_ID + 1;
        String firstKey = "LOCK01";
        String otherKey = "FREE01";
        App firstApp = generatedApp(APP_ID, OWNER_ID, CodeGenTypeEnum.HTML);
        firstApp.setDeployKey(firstKey);
        App otherApp = generatedApp(otherAppId, OWNER_ID, CodeGenTypeEnum.HTML);
        otherApp.setDeployKey(otherKey);
        StagedDeployment firstStaged = mock(StagedDeployment.class);
        PublishedDeployment firstPublished = mock(PublishedDeployment.class);
        StagedDeployment otherStaged = mock(StagedDeployment.class);
        PublishedDeployment otherPublished = mock(PublishedDeployment.class);
        CountDownLatch stageEntered = new CountDownLatch(1);
        CountDownLatch releaseStage = new CountDownLatch(1);
        AtomicReference<AppDeployVO> firstResult = new AtomicReference<>();
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();

        given(appMapper.selectById(any())).willAnswer(invocation -> {
            long requestedId = ((Number) invocation.getArgument(0)).longValue();
            return requestedId == APP_ID ? firstApp : otherApp;
        });
        given(deploymentFileManager.stage(CodeGenTypeEnum.HTML, APP_ID))
                .willAnswer(invocation -> {
                    stageEntered.countDown();
                    if (!releaseStage.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting to release deployment");
                    }
                    return firstStaged;
                });
        given(deploymentFileManager.stage(CodeGenTypeEnum.HTML, otherAppId))
                .willReturn(otherStaged);
        given(firstStaged.publishReplacement(firstKey)).willReturn(firstPublished);
        given(otherStaged.publishReplacement(otherKey)).willReturn(otherPublished);
        given(appMapper.update(any(App.class), any(UpdateWrapper.class))).willReturn(1);

        Thread deploymentThread = Thread.startVirtualThread(() -> {
            try {
                firstResult.set(appService.deployApp(APP_ID, user(OWNER_ID)));
            } catch (Throwable throwable) {
                firstFailure.set(throwable);
            }
        });

        try {
            assertThat(stageEntered.await(5, TimeUnit.SECONDS)).isTrue();
            expectBusinessException(() -> appService.deployApp(APP_ID, user(OWNER_ID)),
                    ErrorCode.OPERATION_ERROR);
            expectBusinessException(
                    () -> appService.chatToGenCode(APP_ID, "message", user(OWNER_ID)).blockLast(),
                    ErrorCode.OPERATION_ERROR);
            expectBusinessException(
                    () -> appService.createAppPreview(APP_ID, user(OWNER_ID)),
                    ErrorCode.OPERATION_ERROR);
            expectBusinessException(() -> appService.deleteAppByUser(APP_ID, user(OWNER_ID)),
                    ErrorCode.OPERATION_ERROR);

            AppDeployVO otherResult = appService.deployApp(otherAppId, user(OWNER_ID));
            assertThat(otherResult.getDeployKey()).isEqualTo(otherKey);
            verify(otherPublished).commit();
        } finally {
            releaseStage.countDown();
        }

        deploymentThread.join(5_000L);
        assertThat(deploymentThread.isAlive()).isFalse();
        assertThat(firstFailure.get()).isNull();
        assertThat(firstResult.get()).isNotNull();
        assertThat(firstResult.get().getDeployKey()).isEqualTo(firstKey);
        assertThat(appService.deployApp(APP_ID, user(OWNER_ID)).getDeployKey())
                .isEqualTo(firstKey);
        verify(firstPublished, times(2)).commit();
        verify(deploymentFileManager, times(2)).stage(CodeGenTypeEnum.HTML, APP_ID);
    }

    @Test
    void viewConversionKeepsPromptOutOfSummaryButIncludesItInDetail() {
        App app = existingApp(OWNER_ID);
        app.setAppName("Dashboard");
        app.setCover("https://example.com/cover.png");
        app.setInitPrompt("Create a private analytics dashboard");
        app.setPriority(99);
        app.setCreateTime(new Date(1_000));
        app.setUpdateTime(new Date(2_000));

        AppVO summary = appService.getAppVO(app);
        AppDetailVO detail = appService.getAppDetailVO(app);
        Page<AppVO> summaryPage = appService.getAppVOPage(
                new Page<App>(2, 5, 1).setRecords(List.of(app)));

        assertThat(ReflectionTestUtils.getField(summary, "id")).isEqualTo(APP_ID);
        assertThat(ReflectionTestUtils.getField(summary, "appName")).isEqualTo("Dashboard");
        assertThat(ReflectionUtils.findField(AppVO.class, "initPrompt")).isNull();
        assertThat(ReflectionUtils.findField(AppVO.class, "isDelete")).isNull();
        assertThat(summary.getDeployUrl()).isNull();
        assertThat(ReflectionTestUtils.getField(detail, "initPrompt"))
                .isEqualTo("Create a private analytics dashboard");
        assertThat(detail.getDeployUrl()).isNull();
        assertThat(ReflectionUtils.findField(AppDetailVO.class, "isDelete")).isNull();
        assertThat(summaryPage.getCurrent()).isEqualTo(2);
        assertThat(summaryPage.getSize()).isEqualTo(5);
        assertThat(summaryPage.getTotal()).isEqualTo(1);
        assertThat(summaryPage.getRecords()).singleElement()
                .extracting(record -> ReflectionTestUtils.getField(record, "appName"))
                .isEqualTo("Dashboard");
        assertThat(ReflectionUtils.findField(summaryPage.getRecords().getFirst().getClass(),
                "initPrompt")).isNull();
    }

    @Test
    void ownerDetailRequiresExactOwnerAndBuildsCompletedDeploymentUrl() {
        App app = existingApp(OWNER_ID);
        app.setDeployKey("Own001");
        app.setDeployedTime(new Date(2_000L));
        given(appMapper.selectById(APP_ID)).willReturn(app);

        AppDetailVO detail = appService.getAppDetailVOByOwner(APP_ID, user(OWNER_ID));

        assertThat(detail.getId()).isEqualTo(APP_ID);
        assertThat(detail.getDeployUrl()).isEqualTo(
                "https://deploy.example.com/Own001/");
        expectBusinessException(
                () -> appService.getAppDetailVOByOwner(APP_ID, user(OWNER_ID + 1)),
                ErrorCode.NO_AUTH_ERROR
        );
    }

    @Test
    void ownerDetailValidatesIdentityAndMissingApplication() {
        expectBusinessException(
                () -> appService.getAppDetailVOByOwner(null, user(OWNER_ID)),
                ErrorCode.PARAMS_ERROR
        );
        expectBusinessException(
                () -> appService.getAppDetailVOByOwner(APP_ID, new User()),
                ErrorCode.NOT_LOGIN_ERROR
        );
        given(appMapper.selectById(APP_ID)).willReturn(null);
        expectBusinessException(
                () -> appService.getAppDetailVOByOwner(APP_ID, user(OWNER_ID)),
                ErrorCode.NOT_FOUND_ERROR
        );
    }

    @Test
    void publicFeaturedDetailIsSanitizedAndIncludesOnlyCompletedDeployUrl() {
        App app = existingApp(OWNER_ID);
        app.setAppName("Featured app");
        app.setCover("cover.png");
        app.setInitPrompt("private prompt");
        app.setCodeGenType(CodeGenTypeEnum.HTML.getValue());
        app.setDeployKey("Pub001");
        app.setDeployedTime(new Date(3_000L));
        app.setPriority(AppConstant.GOOD_APP_PRIORITY);
        app.setCreateTime(new Date(1_000L));
        app.setUpdateTime(new Date(2_000L));
        given(appMapper.selectById(APP_ID)).willReturn(app);

        PublicAppDetailVO detail = appService.getPublicAppDetailVO(APP_ID);

        assertThat(detail.getId()).isEqualTo(APP_ID);
        assertThat(detail.getAppName()).isEqualTo("Featured app");
        assertThat(detail.getDeployUrl()).isEqualTo(
                "https://deploy.example.com/Pub001/");
        assertThat(detail.getDeployedTime()).isEqualTo(new Date(3_000L));
        assertThat(ReflectionUtils.findField(PublicAppDetailVO.class, "initPrompt")).isNull();
        assertThat(ReflectionUtils.findField(PublicAppDetailVO.class, "userId")).isNull();
        assertThat(ReflectionUtils.findField(PublicAppDetailVO.class, "deployKey")).isNull();
    }

    @Test
    void publicDetailHidesNonFeaturedAndMissingApplications() {
        App ordinaryApp = existingApp(OWNER_ID);
        ordinaryApp.setPriority(AppConstant.DEFAULT_APP_PRIORITY);
        given(appMapper.selectById(APP_ID)).willReturn(ordinaryApp, (App) null);

        expectBusinessException(() -> appService.getPublicAppDetailVO(APP_ID),
                ErrorCode.NOT_FOUND_ERROR);
        expectBusinessException(() -> appService.getPublicAppDetailVO(APP_ID),
                ErrorCode.NOT_FOUND_ERROR);
    }

    @Test
    void viewsOmitDeployUrlForIncompleteOrMalformedDeploymentMetadata() {
        App app = existingApp(OWNER_ID);
        app.setDeployKey("Valid1");

        assertThat(appService.getAppVO(app).getDeployUrl()).isNull();
        assertThat(appService.getAppDetailVO(app).getDeployUrl()).isNull();

        app.setDeployedTime(new Date(4_000L));
        assertThat(appService.getAppVO(app).getDeployUrl())
                .isEqualTo("https://deploy.example.com/Valid1/");
        assertThat(appService.getAppDetailVO(app).getDeployUrl())
                .isEqualTo("https://deploy.example.com/Valid1/");

        app.setDeployKey("malformed-key");
        assertThat(appService.getAppVO(app).getDeployUrl()).isNull();
        assertThat(appService.getAppDetailVO(app).getDeployUrl()).isNull();
    }

    @Test
    void ownerUpdateWritesOnlyTrimmedNameWithIdAndOwnerConditions() {
        AppUpdateDTO request = updateRequest("  Renamed App  ");
        given(appMapper.selectById(APP_ID)).willReturn(existingApp(OWNER_ID));
        given(appMapper.update(any(App.class), any(UpdateWrapper.class))).willReturn(1);

        boolean result = appService.updateAppByUser(request, user(OWNER_ID));

        ArgumentCaptor<App> appCaptor = ArgumentCaptor.forClass(App.class);
        ArgumentCaptor<UpdateWrapper<App>> wrapperCaptor = updateWrapperCaptor();
        verify(appMapper).update(appCaptor.capture(), wrapperCaptor.capture());
        App update = appCaptor.getValue();
        assertThat(result).isTrue();
        assertThat(update.getAppName()).isEqualTo("Renamed App");
        assertThat(update.getEditTime()).isNotNull();
        assertThat(update.getId()).isNull();
        assertThat(update.getCover()).isNull();
        assertThat(update.getInitPrompt()).isNull();
        assertThat(update.getPriority()).isNull();
        assertThat(update.getUserId()).isNull();
        assertOwnerConstrained(wrapperCaptor.getValue());
    }

    @Test
    void ownerUpdateRejectsBlankAndOverlongNamesBeforeReading() {
        for (String name : List.of("   ", "a".repeat(257))) {
            expectBusinessException(() -> appService.updateAppByUser(updateRequest(name), user(OWNER_ID)),
                    ErrorCode.PARAMS_ERROR);
        }

        verify(appMapper, never()).selectById(any());
        verify(appMapper, never()).update(any(App.class), any(Wrapper.class));
    }

    @Test
    void ownerWritesRejectForeignApplicationsWithoutMutation() {
        given(appMapper.selectById(APP_ID)).willReturn(existingApp(9999L));

        expectBusinessException(() -> appService.updateAppByUser(updateRequest("Renamed"), user(OWNER_ID)),
                ErrorCode.NO_AUTH_ERROR);
        expectBusinessException(() -> appService.deleteAppByUser(APP_ID, user(OWNER_ID)),
                ErrorCode.NO_AUTH_ERROR);

        verify(appMapper, never()).update(any(App.class), any(Wrapper.class));
        verify(appMapper, never()).delete(any(Wrapper.class));
    }

    @Test
    void ownerWritesRejectMissingApplicationsWithoutMutation() {
        given(appMapper.selectById(APP_ID)).willReturn(null);

        expectBusinessException(() -> appService.updateAppByUser(updateRequest("Renamed"), user(OWNER_ID)),
                ErrorCode.NOT_FOUND_ERROR);
        expectBusinessException(() -> appService.deleteAppByUser(APP_ID, user(OWNER_ID)),
                ErrorCode.NOT_FOUND_ERROR);

        verify(appMapper, never()).update(any(App.class), any(Wrapper.class));
        verify(appMapper, never()).delete(any(Wrapper.class));
    }

    @Test
    void ownerUpdateMapsZeroAffectedRowsToOperationError() {
        given(appMapper.selectById(APP_ID)).willReturn(existingApp(OWNER_ID));
        given(appMapper.update(any(App.class), any(UpdateWrapper.class))).willReturn(0);

        expectBusinessException(() -> appService.updateAppByUser(updateRequest("Renamed"), user(OWNER_ID)),
                ErrorCode.OPERATION_ERROR);
    }

    @Test
    void ownerDeleteUsesIdAndOwnerConditions() {
        given(appMapper.selectById(APP_ID)).willReturn(existingApp(OWNER_ID));
        given(appMapper.delete(any(QueryWrapper.class))).willReturn(1);

        boolean result = appService.deleteAppByUser(APP_ID, user(OWNER_ID));

        ArgumentCaptor<QueryWrapper<App>> wrapperCaptor = queryWrapperCaptor();
        verify(appMapper).delete(wrapperCaptor.capture());
        assertThat(result).isTrue();
        assertOwnerConstrained(wrapperCaptor.getValue());
        verify(chatHistoryService).deleteByAppId(APP_ID);
        verify(appMapper, never()).deleteById(any(Serializable.class));
        verify(deploymentLocalServer).revokePreview(APP_ID);
    }

    @Test
    void ownerDeleteMapsZeroAffectedRowsToOperationError() {
        given(appMapper.selectById(APP_ID)).willReturn(existingApp(OWNER_ID));
        given(appMapper.delete(any(QueryWrapper.class))).willReturn(0);

        expectBusinessException(() -> appService.deleteAppByUser(APP_ID, user(OWNER_ID)),
                ErrorCode.OPERATION_ERROR);

        verify(transactionStatus).setRollbackOnly();
        verify(chatHistoryService, never()).deleteByAppId(anyLong());
        verify(deploymentLocalServer, never()).revokePreview(anyLong());
    }

    @Test
    void ownerDeletePreparesUndeploymentBeforeDatabaseDeleteAndCommitsAfterward() {
        String deployKey = "DEL001";
        App app = existingApp(OWNER_ID);
        app.setDeployKey(deployKey);
        Undeployment undeployment = mock(Undeployment.class);
        given(appMapper.selectById(APP_ID)).willReturn(app);
        given(deploymentFileManager.prepareUndeployment(deployKey)).willReturn(undeployment);
        given(appMapper.delete(any(QueryWrapper.class))).willReturn(1);

        assertThat(appService.deleteAppByUser(APP_ID, user(OWNER_ID))).isTrue();

        InOrder order = inOrder(
                appMapper,
                deploymentFileManager,
                undeployment,
                deploymentLocalServer
        );
        order.verify(appMapper).selectById(APP_ID);
        order.verify(deploymentFileManager).prepareUndeployment(deployKey);
        order.verify(appMapper).delete(any(QueryWrapper.class));
        order.verify(undeployment).commit();
        order.verify(deploymentLocalServer).revokePreview(APP_ID);
        verify(undeployment, never()).rollback();
    }

    @Test
    void ownerDeleteRevokesPreviewWhenPostDatabaseUndeploymentCommitFails() {
        String deployKey = "DEL005";
        IllegalStateException commitFailure = new IllegalStateException("commit failed");
        App app = existingApp(OWNER_ID);
        app.setDeployKey(deployKey);
        Undeployment undeployment = mock(Undeployment.class);
        given(appMapper.selectById(APP_ID)).willReturn(app);
        given(deploymentFileManager.prepareUndeployment(deployKey)).willReturn(undeployment);
        given(appMapper.delete(any(QueryWrapper.class))).willReturn(1);
        org.mockito.Mockito.doThrow(commitFailure).when(undeployment).commit();

        Throwable thrown = catchThrowable(
                () -> appService.deleteAppByUser(APP_ID, user(OWNER_ID)));

        assertThat(thrown).isSameAs(commitFailure);
        verify(deploymentLocalServer).revokePreview(APP_ID);
    }

    @Test
    void ownerDeleteRestoresDeploymentWhenDatabaseDeleteAffectsNoRows() {
        String deployKey = "DEL002";
        App app = existingApp(OWNER_ID);
        app.setDeployKey(deployKey);
        Undeployment undeployment = mock(Undeployment.class);
        given(appMapper.selectById(APP_ID)).willReturn(app);
        given(deploymentFileManager.prepareUndeployment(deployKey)).willReturn(undeployment);
        given(appMapper.delete(any(QueryWrapper.class))).willReturn(0);

        expectBusinessException(() -> appService.deleteAppByUser(APP_ID, user(OWNER_ID)),
                ErrorCode.OPERATION_ERROR);

        verify(undeployment).rollback();
        verify(undeployment, never()).commit();
        verify(deploymentLocalServer, never()).revokePreview(anyLong());
    }

    @Test
    void ownerDeleteRestoresDeploymentAndMapsDatabaseException() {
        String deployKey = "DEL003";
        RuntimeException databaseFailure = new IllegalStateException("delete failed");
        App app = existingApp(OWNER_ID);
        app.setDeployKey(deployKey);
        Undeployment undeployment = mock(Undeployment.class);
        given(appMapper.selectById(APP_ID)).willReturn(app, app);
        given(deploymentFileManager.prepareUndeployment(deployKey)).willReturn(undeployment);
        given(appMapper.delete(any(QueryWrapper.class))).willThrow(databaseFailure);

        BusinessException thrown = expectBusinessException(
                () -> appService.deleteAppByUser(APP_ID, user(OWNER_ID)),
                ErrorCode.OPERATION_ERROR);

        assertThat(thrown.getCause()).isSameAs(databaseFailure);
        verify(appMapper, times(2)).selectById(APP_ID);
        verify(undeployment).rollback();
        verify(undeployment, never()).commit();
        verify(deploymentLocalServer, never()).revokePreview(anyLong());
    }

    @Test
    void historyDeletionFailureRollsBackDatabaseTransactionAndRestoresDeployment() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        given(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .willReturn(status);
        AppServiceImpl transactionalService = createAppService(
                new TransactionTemplate(transactionManager));
        String deployKey = "DELH01";
        App app = existingApp(OWNER_ID);
        app.setDeployKey(deployKey);
        Undeployment undeployment = mock(Undeployment.class);
        IllegalStateException historyFailure = new IllegalStateException("history delete failed");
        given(appMapper.selectById(APP_ID)).willReturn(app, app);
        given(deploymentFileManager.prepareUndeployment(deployKey)).willReturn(undeployment);
        given(appMapper.delete(any(QueryWrapper.class))).willReturn(1);
        org.mockito.Mockito.doThrow(historyFailure)
                .when(chatHistoryService).deleteByAppId(APP_ID);

        BusinessException thrown = expectBusinessException(
                () -> transactionalService.deleteAppByUser(APP_ID, user(OWNER_ID)),
                ErrorCode.OPERATION_ERROR);

        assertThat(thrown.getCause()).isSameAs(historyFailure);
        InOrder order = inOrder(appMapper, chatHistoryService, transactionManager, undeployment);
        order.verify(appMapper).delete(any(QueryWrapper.class));
        order.verify(chatHistoryService).deleteByAppId(APP_ID);
        order.verify(transactionManager).rollback(status);
        order.verify(undeployment).rollback();
        verify(transactionManager, never()).commit(any(TransactionStatus.class));
        verify(undeployment, never()).commit();
        verify(deploymentLocalServer, never()).revokePreview(anyLong());
    }

    @Test
    void ownerDeleteCommitsUndeploymentWhenDatabaseDeleteCommittedBeforeThrowing() {
        String deployKey = "DEL006";
        RuntimeException databaseFailure = new IllegalStateException("delete acknowledgement lost");
        App app = existingApp(OWNER_ID);
        app.setDeployKey(deployKey);
        Undeployment undeployment = mock(Undeployment.class);
        given(appMapper.selectById(APP_ID)).willReturn(app).willReturn((App) null);
        given(deploymentFileManager.prepareUndeployment(deployKey)).willReturn(undeployment);
        given(appMapper.delete(any(QueryWrapper.class))).willThrow(databaseFailure);

        assertThat(appService.deleteAppByUser(APP_ID, user(OWNER_ID))).isTrue();

        InOrder order = inOrder(appMapper, undeployment, deploymentLocalServer);
        order.verify(appMapper).selectById(APP_ID);
        order.verify(appMapper).delete(any(QueryWrapper.class));
        order.verify(appMapper).selectById(APP_ID);
        order.verify(undeployment).commit();
        order.verify(deploymentLocalServer).revokePreview(APP_ID);
        verify(undeployment, never()).rollback();
    }

    @Test
    void ownerDeleteKeepsDeploymentOfflineAndRevokesPreviewWhenOutcomeCannotBeRead() {
        String deployKey = "DEL007";
        RuntimeException databaseFailure = new IllegalStateException("delete acknowledgement lost");
        RuntimeException verificationFailure = new IllegalStateException("verification failed");
        App app = existingApp(OWNER_ID);
        app.setDeployKey(deployKey);
        Undeployment undeployment = mock(Undeployment.class);
        given(appMapper.selectById(APP_ID)).willReturn(app).willThrow(verificationFailure);
        given(deploymentFileManager.prepareUndeployment(deployKey)).willReturn(undeployment);
        given(appMapper.delete(any(QueryWrapper.class))).willThrow(databaseFailure);

        BusinessException thrown = expectBusinessException(
                () -> appService.deleteAppByUser(APP_ID, user(OWNER_ID)),
                ErrorCode.OPERATION_ERROR);

        assertThat(thrown.getCause()).isSameAs(databaseFailure);
        assertThat(databaseFailure.getSuppressed()).contains(verificationFailure);
        verify(undeployment, never()).commit();
        verify(undeployment, never()).rollback();
        verify(deploymentLocalServer).revokePreview(APP_ID);
    }

    @Test
    void undeploymentPreparationFailureLeavesDatabaseRowActive() {
        String deployKey = "DEL004";
        App app = existingApp(OWNER_ID);
        app.setDeployKey(deployKey);
        BusinessException undeploymentFailure = new BusinessException(
                ErrorCode.OPERATION_ERROR, "cannot move deployment");
        given(appMapper.selectById(APP_ID)).willReturn(app);
        given(deploymentFileManager.prepareUndeployment(deployKey))
                .willThrow(undeploymentFailure);

        BusinessException thrown = expectBusinessException(
                () -> appService.deleteAppByUser(APP_ID, user(OWNER_ID)),
                ErrorCode.OPERATION_ERROR);

        assertThat(thrown).isSameAs(undeploymentFailure);
        verify(appMapper, never()).delete(any(Wrapper.class));
        verify(deploymentLocalServer, never()).revokePreview(anyLong());
    }

    @Test
    void adminUpdateWritesOnlyApprovedFields() {
        AppAdminUpdateDTO request = new AppAdminUpdateDTO();
        request.setId(APP_ID);
        request.setAppName("  Featured App  ");
        request.setCover("https://example.com/featured.png");
        request.setPriority(99);
        given(appMapper.selectById(APP_ID)).willReturn(existingApp(OWNER_ID));
        given(appMapper.updateById(any(App.class))).willReturn(1);

        boolean result = appService.updateAppByAdmin(request);

        ArgumentCaptor<App> appCaptor = ArgumentCaptor.forClass(App.class);
        verify(appMapper).updateById(appCaptor.capture());
        App update = appCaptor.getValue();
        assertThat(result).isTrue();
        assertThat(update.getId()).isEqualTo(APP_ID);
        assertThat(update.getAppName()).isEqualTo("Featured App");
        assertThat(update.getCover()).isEqualTo("https://example.com/featured.png");
        assertThat(update.getPriority()).isEqualTo(99);
        assertThat(update.getEditTime()).isNotNull();
        assertThat(update.getInitPrompt()).isNull();
        assertThat(update.getCodeGenType()).isNull();
        assertThat(update.getDeployKey()).isNull();
        assertThat(update.getUserId()).isNull();
    }

    @Test
    void adminUpdateValidatesEveryApprovedField() {
        given(appMapper.selectById(APP_ID)).willReturn(existingApp(OWNER_ID));

        AppAdminUpdateDTO empty = adminUpdateRequest();
        AppAdminUpdateDTO blankName = adminUpdateRequest();
        blankName.setAppName("   ");
        AppAdminUpdateDTO longName = adminUpdateRequest();
        longName.setAppName("a".repeat(257));
        AppAdminUpdateDTO longCover = adminUpdateRequest();
        longCover.setCover("c".repeat(513));
        AppAdminUpdateDTO negativePriority = adminUpdateRequest();
        negativePriority.setPriority(-1);

        for (AppAdminUpdateDTO request :
                List.of(empty, blankName, longName, longCover, negativePriority)) {
            expectBusinessException(() -> appService.updateAppByAdmin(request),
                    ErrorCode.PARAMS_ERROR);
        }

        verify(appMapper, never()).updateById(any(App.class));
    }

    @Test
    void adminWritesRejectMissingApplications() {
        given(appMapper.selectById(APP_ID)).willReturn(null);
        AppAdminUpdateDTO request = adminUpdateRequest();
        request.setPriority(99);

        expectBusinessException(() -> appService.updateAppByAdmin(request),
                ErrorCode.NOT_FOUND_ERROR);
        expectBusinessException(() -> appService.deleteAppByAdmin(APP_ID),
                ErrorCode.NOT_FOUND_ERROR);

        verify(appMapper, never()).updateById(any(App.class));
        verify(appMapper, never()).deleteById(any(Serializable.class));
    }

    @Test
    void adminWritesMapZeroAffectedRowsToOperationError() {
        given(appMapper.selectById(APP_ID)).willReturn(existingApp(OWNER_ID));
        given(appMapper.updateById(any(App.class))).willReturn(0);
        given(appMapper.deleteById(APP_ID)).willReturn(0);
        AppAdminUpdateDTO request = adminUpdateRequest();
        request.setPriority(99);

        expectBusinessException(() -> appService.updateAppByAdmin(request),
                ErrorCode.OPERATION_ERROR);
        expectBusinessException(() -> appService.deleteAppByAdmin(APP_ID),
                ErrorCode.OPERATION_ERROR);
    }

    @Test
    void adminDeleteRemovesExistingApplicationWithoutOwnerCondition() {
        given(appMapper.selectById(APP_ID)).willReturn(existingApp(9999L));
        given(appMapper.deleteById(APP_ID)).willReturn(1);

        assertThat(appService.deleteAppByAdmin(APP_ID)).isTrue();

        verify(appMapper).deleteById(APP_ID);
        verify(chatHistoryService).deleteByAppId(APP_ID);
        verify(appMapper, never()).delete(any(Wrapper.class));
        verify(deploymentLocalServer).revokePreview(APP_ID);
    }

    @Test
    void adminDeleteAlsoCommitsUndeploymentForAnotherUsersApp() {
        String deployKey = "ADM001";
        App app = existingApp(9999L);
        app.setDeployKey(deployKey);
        Undeployment undeployment = mock(Undeployment.class);
        given(appMapper.selectById(APP_ID)).willReturn(app);
        given(deploymentFileManager.prepareUndeployment(deployKey)).willReturn(undeployment);
        given(appMapper.deleteById(APP_ID)).willReturn(1);

        assertThat(appService.deleteAppByAdmin(APP_ID)).isTrue();

        verify(deploymentFileManager).prepareUndeployment(deployKey);
        verify(appMapper).deleteById(APP_ID);
        verify(undeployment).commit();
        verify(undeployment, never()).rollback();
        verify(deploymentLocalServer).revokePreview(APP_ID);
    }

    @Test
    void myQueryForcesOwnerAndUsesContainsNameFilter() {
        AppNameQueryDTO request = new AppNameQueryDTO();
        request.setAppName("portal");

        QueryWrapper<App> wrapper = appService.getMyAppQueryWrapper(request, OWNER_ID);

        assertThat(compactSql(wrapper)).contains("userid=", "appnamelike");
        assertThat(wrapper.getParamNameValuePairs()).containsValue(OWNER_ID).containsValue("%portal%");
    }

    @Test
    void featuredQueryForcesExactFeaturedPriorityAndUsesContainsNameFilter() {
        AppNameQueryDTO request = new AppNameQueryDTO();
        request.setAppName("portal");

        QueryWrapper<App> wrapper = appService.getGoodAppQueryWrapper(request);

        assertThat(compactSql(wrapper)).contains("priority=", "appnamelike");
        assertThat(wrapper.getParamNameValuePairs())
                .containsValue(AppConstant.GOOD_APP_PRIORITY)
                .containsValue("%portal%");
    }

    @Test
    void adminQuerySupportsEveryNonTimeBusinessFilterWithExpectedOperators() {
        AppQueryDTO request = new AppQueryDTO();
        request.setId(APP_ID);
        request.setAppName("portal");
        request.setCover("cdn.example");
        request.setInitPrompt("dashboard");
        request.setCodeGenType("html");
        request.setDeployKey("deploy-001");
        request.setPriority(99);
        request.setUserId(OWNER_ID);

        QueryWrapper<App> wrapper = appService.getQueryWrapper(request);
        String sql = compactSql(wrapper);
        Map<String, Object> parameters = wrapper.getParamNameValuePairs();

        assertThat(sql).contains(
                "id=", "appnamelike", "coverlike", "initpromptlike",
                "codegentype=", "deploykey=", "priority=", "userid=");
        assertThat(parameters).containsValues(
                APP_ID, "%portal%", "%cdn.example%", "%dashboard%", "html",
                "deploy-001", 99, OWNER_ID);
        assertThat(Arrays.stream(AppQueryDTO.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName))
                .doesNotContain("deployedTime", "editTime", "createTime", "updateTime", "isDelete");
    }

    @Test
    void allQueryTypesUseStableDefaultOrdering() {
        String expectedOrder = "orderbycreatetimedesc,iddesc";

        assertThat(compactSql(appService.getMyAppQueryWrapper(new AppNameQueryDTO(), OWNER_ID)))
                .contains(expectedOrder);
        assertThat(compactSql(appService.getGoodAppQueryWrapper(new AppNameQueryDTO())))
                .contains(expectedOrder);
        assertThat(compactSql(appService.getQueryWrapper(new AppQueryDTO())))
                .contains(expectedOrder);
    }

    @Test
    void knownSortFieldUsesRequestedDirectionInsteadOfDefaultOrdering() {
        AppNameQueryDTO request = new AppNameQueryDTO();
        request.setSortField("priority");
        request.setSortOrder("ascend");

        String sql = compactSql(appService.getGoodAppQueryWrapper(request));

        assertThat(sql).contains("orderbypriorityasc");
        assertThat(sql).doesNotContain("createtime", "iddesc");
    }

    @Test
    void untrustedSortFieldIsNotInterpolatedAndFallsBackToDefaultOrdering() {
        AppQueryDTO request = new AppQueryDTO();
        request.setSortField("id desc; drop table app");
        request.setSortOrder("ascend");

        String sql = compactSql(appService.getQueryWrapper(request));

        assertThat(sql).doesNotContain("drop", "tableapp", ";");
        assertThat(sql).contains("orderbycreatetimedesc,iddesc");
    }

    @Test
    void queryWrappersRelyOnMyBatisPlusLogicalDeleteMetadata() throws NoSuchFieldException {
        assertThat(App.class.getDeclaredField("isDelete").getAnnotation(TableLogic.class)).isNotNull();

        QueryWrapper<App> myWrapper = appService.getMyAppQueryWrapper(new AppNameQueryDTO(), OWNER_ID);
        QueryWrapper<App> goodWrapper = appService.getGoodAppQueryWrapper(new AppNameQueryDTO());
        QueryWrapper<App> adminWrapper = appService.getQueryWrapper(new AppQueryDTO());

        assertThat(compactSql(myWrapper)).doesNotContain("isdelete");
        assertThat(compactSql(goodWrapper)).doesNotContain("isdelete");
        assertThat(compactSql(adminWrapper)).doesNotContain("isdelete");
    }

    private static AppUpdateDTO updateRequest(String appName) {
        AppUpdateDTO request = new AppUpdateDTO();
        request.setId(APP_ID);
        request.setAppName(appName);
        return request;
    }

    private static AppAdminUpdateDTO adminUpdateRequest() {
        AppAdminUpdateDTO request = new AppAdminUpdateDTO();
        request.setId(APP_ID);
        return request;
    }

    private DeploymentHandles stubPublication(
            CodeGenTypeEnum codeGenType,
            long appId,
            String deployKey,
            boolean replacement
    ) {
        StagedDeployment staged = mock(StagedDeployment.class);
        PublishedDeployment published = mock(PublishedDeployment.class);
        given(deploymentFileManager.stage(codeGenType, appId)).willReturn(staged);
        if (replacement) {
            given(staged.publishReplacement(deployKey)).willReturn(published);
        } else {
            given(staged.publishNew(deployKey)).willReturn(published);
        }
        return new DeploymentHandles(staged, published);
    }

    private static App generatedApp(
            long appId,
            long ownerId,
            CodeGenTypeEnum codeGenType
    ) {
        App app = existingApp(appId, ownerId);
        app.setCodeGenType(codeGenType.getValue());
        return app;
    }

    private static App existingApp(long ownerId) {
        return existingApp(APP_ID, ownerId);
    }

    private static App existingApp(long appId, long ownerId) {
        App app = new App();
        app.setId(appId);
        app.setUserId(ownerId);
        return app;
    }

    private static User user(long userId) {
        User user = new User();
        user.setId(userId);
        return user;
    }

    private static void assertSuccessfulGeneration(
            List<AppGenerationEvent> events,
            String... expectedChunks
    ) {
        assertThat(events).hasSize(expectedChunks.length + 1);
        assertThat(events.subList(0, expectedChunks.length))
                .containsExactlyElementsOf(Arrays.stream(expectedChunks)
                        .map(AppGenerationEvent.Content::new)
                        .toList());
        assertThat(events.getLast()).isInstanceOf(AppGenerationEvent.Completed.class);
        AppPreviewVO preview = ((AppGenerationEvent.Completed) events.getLast()).preview();
        assertThat(preview.getPreviewUrl()).isEqualTo(PREVIEW_URL);
        assertThat(preview.getExpiresAt()).isEqualTo(PREVIEW_EXPIRES_AT);
    }

    private static CodeGenerationSession mockGenerationSession(Flux<String> stream) {
        CodeGenerationSession session = mock(CodeGenerationSession.class);
        given(session.stream()).willReturn(stream);
        org.mockito.Mockito.doAnswer(invocation -> {
                    Supplier<?> finalization = invocation.getArgument(0);
                    return finalization.get();
                })
                .when(session)
                .commitAfter(any());
        return session;
    }

    private AppServiceImpl createAppService(TransactionTemplate template) {
        AppServiceImpl service = new AppServiceImpl(
                aiCodeGeneratorFacadeProvider,
                deploymentFileManager,
                deployKeyGenerator,
                deploymentProperties,
                deploymentLocalServer,
                chatHistoryService,
                template
        );
        ReflectionTestUtils.setField(service, "baseMapper", appMapper);
        return service;
    }

    private static void assertOwnerConstrained(Wrapper<App> wrapper) {
        assertThat(compactSql(wrapper)).contains("id=", "userid=");
        assertThat(wrapperParameters(wrapper)).containsValue(APP_ID).containsValue(OWNER_ID);
    }

    private static String compactSql(Wrapper<App> wrapper) {
        return wrapper.getSqlSegment().replaceAll("\\s+", "").toLowerCase();
    }

    private static Map<String, Object> wrapperParameters(Wrapper<App> wrapper) {
        if (wrapper instanceof QueryWrapper<?> queryWrapper) {
            return queryWrapper.getParamNameValuePairs();
        }
        if (wrapper instanceof UpdateWrapper<?> updateWrapper) {
            return updateWrapper.getParamNameValuePairs();
        }
        throw new AssertionError("Unsupported wrapper type: " + wrapper.getClass().getName());
    }

    private static BusinessException expectBusinessException(
            ThrowingCallable action, ErrorCode errorCode) {
        BusinessException exception = catchThrowableOfType(action, BusinessException.class);
        assertThat(exception).isNotNull();
        assertThat(exception.getCode()).isEqualTo(errorCode.getCode());
        return exception;
    }

    private static ArgumentCaptor<UpdateWrapper<App>> updateWrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(UpdateWrapper.class);
    }

    private static ArgumentCaptor<QueryWrapper<App>> queryWrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(QueryWrapper.class);
    }

    private record DeploymentHandles(
            StagedDeployment staged,
            PublishedDeployment published
    ) {
    }

    private static PreviewAccess previewAccess() {
        return new PreviewAccess(
                PREVIEW_URL,
                Instant.ofEpochMilli(PREVIEW_EXPIRES_AT)
        );
    }
}
