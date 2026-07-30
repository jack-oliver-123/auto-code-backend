package com.jack.autocodebackend.service.impl;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import com.jack.autocodebackend.core.lock.AppProcessingLeaseLostException;
import com.jack.autocodebackend.core.lock.AppProcessingLeaseManager;
import com.jack.autocodebackend.core.parser.VueProjectCodeParser;
import com.jack.autocodebackend.core.vue.VueProjectSourceContextLoader;
import com.jack.autocodebackend.core.vue.VueProjectSourceSnapshot;
import com.jack.autocodebackend.ai.model.VueProjectFile;
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
import com.jack.autocodebackend.model.enums.AppGenerationStatusEnum;
import com.jack.autocodebackend.model.vo.AppDeployVO;
import com.jack.autocodebackend.model.vo.AppDetailVO;
import com.jack.autocodebackend.model.vo.AppGenerationEvent;
import com.jack.autocodebackend.model.vo.AppPreviewVO;
import com.jack.autocodebackend.model.vo.AppVO;
import com.jack.autocodebackend.model.vo.PublicAppDetailVO;
import com.jack.autocodebackend.service.ChatHistoryService;
import com.jack.autocodebackend.service.ChatMemoryService;
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
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.io.Serializable;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
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

    private final PreviewPublication previewPublication = mock(PreviewPublication.class);

    private final ChatHistoryService chatHistoryService = mock(ChatHistoryService.class);

    private final ChatMemoryService chatMemoryService = mock(ChatMemoryService.class);

    private final VueProjectSourceContextLoader vueProjectSourceContextLoader =
            mock(VueProjectSourceContextLoader.class);

    private final TestLeaseManager appProcessingLeaseManager = new TestLeaseManager();

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
        given(deploymentLocalServer.preparePreview(anyLong(), any(CodeGenTypeEnum.class)))
                .willReturn(previewPublication);
        given(previewPublication.access()).willReturn(previewAccess());
        given(chatHistoryService.addChatMessage(
                anyLong(), anyLong(), any(), any(ChatHistoryMessageTypeEnum.class)))
                .willAnswer(invocation -> historyIdSequence.incrementAndGet());
        given(chatHistoryService.addAiFailureMessage(anyLong(), anyLong(), any()))
                .willAnswer(invocation -> historyIdSequence.incrementAndGet());
        given(chatHistoryService.addAiCancellationMessage(anyLong(), anyLong()))
                .willAnswer(invocation -> historyIdSequence.incrementAndGet());
        given(chatMemoryService.buildPrompt(
                anyLong(), anyLong(), any(), anyBoolean()))
                .willAnswer(invocation -> invocation.getArgument(2));
        given(chatMemoryService.buildPrompt(
                anyLong(), anyLong(), any(), anyBoolean(), any(VueProjectSourceSnapshot.class)))
                .willAnswer(invocation -> invocation.getArgument(2));
        given(chatHistoryService.removeById(anyLong())).willReturn(true);
        given(appMapper.startGenerationAttempt(
                anyLong(), anyLong(), any(), any(), any(), any())).willReturn(1);
        given(appMapper.completeGenerationAttempt(
                anyLong(), anyLong(), any(), any())).willReturn(1);
        given(appMapper.failGenerationAttempt(
                anyLong(), anyLong(), any(), any(), any(), any())).willReturn(1);
        given(transactionTemplate.execute(any())).willAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
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
        assertThat(inserted.getGenerationStatus())
                .isEqualTo(AppGenerationStatusEnum.PENDING.getValue());
        assertThat(inserted.getGenerationAttemptId()).isNull();
        assertThat(inserted.getGenerationFailureCode()).isNull();
        assertThat(inserted.getGenerationFailureMessage()).isNull();
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
                    generationCommitted.run();
                    return null;
                })
                .when(generationSession)
                .commit();
        given(appMapper.selectById(APP_ID)).willReturn(app);
        org.mockito.Mockito.doReturn(generationSession)
                .when(aiCodeGeneratorFacade)
                .startCodeGeneration(
                        "build the initial website", CodeGenTypeEnum.VUE_PROJECT, APP_ID);
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
                .isEqualTo(CodeGenTypeEnum.VUE_PROJECT.getValue());
        assertThat(compactSql(wrapperCaptor.getValue())).contains("codegentypeisnull");
        assertOwnerConstrained(wrapperCaptor.getValue());
        InOrder generationOrder = inOrder(
                chatHistoryService,
                deploymentLocalServer,
                aiCodeGeneratorFacade,
                generationCompleted,
                appMapper,
                chatMemoryService,
                previewPublication,
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
                "build the initial website", CodeGenTypeEnum.VUE_PROJECT, APP_ID);
        generationOrder.verify(generationCompleted).run();
        generationOrder.verify(deploymentLocalServer)
                .preparePreview(APP_ID, CodeGenTypeEnum.VUE_PROJECT);
        generationOrder.verify(chatHistoryService).addChatMessage(
                APP_ID,
                OWNER_ID,
                " leading space and trailing ",
                ChatHistoryMessageTypeEnum.AI
        );
        generationOrder.verify(appMapper).update(any(App.class), any(UpdateWrapper.class));
        generationOrder.verify(previewPublication).commit();
        generationOrder.verify(generationCommitted).run();
        generationOrder.verify(chatMemoryService).refresh(APP_ID);
        generationOrder.verify(completedEventEmitted).run();
        verify(generationSession).commit();
        verify(generationSession, org.mockito.Mockito.timeout(1_000)).rollback();
    }

    @Test
    void generationUsesOneOpaqueAttemptForStartAndTerminalCas() {
        App app = generatedApp(APP_ID, OWNER_ID, CodeGenTypeEnum.HTML);
        given(appMapper.selectById(APP_ID)).willReturn(app);
        given(aiCodeGeneratorFacade.generateAndSaveCodeStream(
                "refine", CodeGenTypeEnum.HTML, APP_ID))
                .willReturn(Flux.just("complete"));
        ArgumentCaptor<String> attemptCaptor = ArgumentCaptor.forClass(String.class);

        assertSuccessfulGeneration(appService.chatToGenCode(
                APP_ID, "refine", user(OWNER_ID)).collectList().block(), "complete");

        InOrder order = inOrder(appMapper, chatHistoryService);
        order.verify(appMapper).startGenerationAttempt(
                eq(APP_ID),
                eq(OWNER_ID),
                eq(AppGenerationStatusEnum.SUCCEEDED.getValue()),
                isNull(),
                attemptCaptor.capture(),
                any(Date.class)
        );
        order.verify(chatHistoryService).addChatMessage(
                APP_ID, OWNER_ID, "refine", ChatHistoryMessageTypeEnum.USER);
        assertThat(attemptCaptor.getValue()).hasSize(36);
        verify(appMapper).completeGenerationAttempt(
                eq(APP_ID), eq(OWNER_ID), eq(attemptCaptor.getValue()), any(Date.class));
        verify(appMapper, never()).failGenerationAttempt(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void failedAttemptUsesSameOpaqueIdentityAndNeverRunsSuccessCas() {
        App app = generatedApp(APP_ID, OWNER_ID, CodeGenTypeEnum.HTML);
        IllegalStateException providerFailure = new IllegalStateException("closed");
        given(appMapper.selectById(APP_ID)).willReturn(app);
        given(aiCodeGeneratorFacade.generateAndSaveCodeStream(
                "refine", CodeGenTypeEnum.HTML, APP_ID))
                .willReturn(Flux.error(providerFailure));
        ArgumentCaptor<String> attemptCaptor = ArgumentCaptor.forClass(String.class);

        assertFailedGeneration(appService.chatToGenCode(
                APP_ID, "refine", user(OWNER_ID)).collectList().block());

        verify(appMapper).startGenerationAttempt(
                eq(APP_ID), eq(OWNER_ID), any(), any(),
                attemptCaptor.capture(), any(Date.class));
        verify(appMapper).failGenerationAttempt(
                eq(APP_ID),
                eq(OWNER_ID),
                eq(attemptCaptor.getValue()),
                eq("GENERATION_FAILED"),
                eq("生成失败，请稍后重试"),
                any(Date.class)
        );
        verify(appMapper, never()).completeGenerationAttempt(
                any(), any(), any(), any());
    }

    @Test
    void failedStartCasCreatesNoCurrentTurnHistoryOrProviderCall() {
        App app = generatedApp(APP_ID, OWNER_ID, CodeGenTypeEnum.HTML);
        given(appMapper.selectById(APP_ID)).willReturn(app);
        given(appMapper.startGenerationAttempt(
                anyLong(), anyLong(), any(), any(), any(), any())).willReturn(0);

        expectBusinessException(
                () -> appService.chatToGenCode(
                        APP_ID, "refine", user(OWNER_ID)).blockLast(),
                ErrorCode.OPERATION_ERROR);

        verifyNoInteractions(chatHistoryService);
        verify(aiCodeGeneratorFacadeProvider, never()).getIfAvailable();
    }

    @Test
    void failedSuccessTransactionRollsBackBeforeRecordingTheAttemptFailure() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus successStatus = mock(TransactionStatus.class);
        TransactionStatus failureStatus = mock(TransactionStatus.class);
        given(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .willReturn(successStatus, failureStatus);
        AppServiceImpl transactionalService = createAppService(
                new TransactionTemplate(transactionManager));
        App app = generatedApp(APP_ID, OWNER_ID, CodeGenTypeEnum.HTML);
        CodeGenerationSession generationSession = mockGenerationSession(Flux.just("candidate"));
        given(appMapper.selectById(APP_ID)).willReturn(app);
        given(appMapper.completeGenerationAttempt(
                anyLong(), anyLong(), any(), any())).willReturn(0);
        org.mockito.Mockito.doReturn(generationSession)
                .when(aiCodeGeneratorFacade)
                .startCodeGeneration("refine", CodeGenTypeEnum.HTML, APP_ID);

        List<AppGenerationEvent> events = transactionalService.chatToGenCode(
                APP_ID, "refine", user(OWNER_ID)).collectList().block();

        assertFailedGeneration(events, "candidate");
        InOrder transactionOrder = inOrder(transactionManager);
        transactionOrder.verify(transactionManager).rollback(successStatus);
        transactionOrder.verify(transactionManager).commit(failureStatus);
        verify(previewPublication, never()).commit();
        verify(generationSession, never()).commit();
        verify(previewPublication).close();
        verify(generationSession).rollback();
        verify(appMapper).failGenerationAttempt(
                eq(APP_ID), eq(OWNER_ID), any(), eq("GENERATION_FAILED"),
                eq("生成失败，请稍后重试"), any(Date.class));
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
        verify(chatMemoryService).buildPrompt(APP_ID, 10_001L, " add a footer ", false);
        verify(chatMemoryService).refresh(APP_ID);
        verify(appMapper, never()).update(any(App.class), any(Wrapper.class));
        verify(deploymentLocalServer).preparePreview(APP_ID, CodeGenTypeEnum.HTML);
        verify(previewPublication).commit();
    }

    @Test
    void laterVueGenerationLoadsStableSourceBeforeInvokingProvider() {
        App app = generatedApp(APP_ID, OWNER_ID, CodeGenTypeEnum.VUE_PROJECT);
        VueProjectSourceSnapshot source = sourceSnapshot("stable source");
        String request = "修改标题";
        String composedPrompt = "history + source + current";
        given(appMapper.selectById(APP_ID)).willReturn(app);
        given(vueProjectSourceContextLoader.load(APP_ID)).willReturn(source);
        given(chatMemoryService.buildPrompt(APP_ID, 10_001L, request, false, source))
                .willReturn(composedPrompt);
        given(aiCodeGeneratorFacade.generateAndSaveCodeStream(
                composedPrompt, CodeGenTypeEnum.VUE_PROJECT, APP_ID))
                .willReturn(Flux.just("project response"));

        List<AppGenerationEvent> events = appService.chatToGenCode(
                APP_ID, request, user(OWNER_ID)).collectList().block();

        assertSuccessfulGeneration(events, "project response");
        InOrder order = inOrder(
                chatHistoryService,
                vueProjectSourceContextLoader,
                chatMemoryService,
                deploymentLocalServer,
                aiCodeGeneratorFacade);
        order.verify(chatHistoryService).addChatMessage(
                APP_ID, OWNER_ID, request, ChatHistoryMessageTypeEnum.USER);
        order.verify(vueProjectSourceContextLoader).load(APP_ID);
        order.verify(chatMemoryService).buildPrompt(
                APP_ID, 10_001L, request, false, source);
        order.verify(deploymentLocalServer).requirePreviewAvailable();
        order.verify(aiCodeGeneratorFacade).startCodeGeneration(
                composedPrompt, CodeGenTypeEnum.VUE_PROJECT, APP_ID);
        verify(appMapper, never()).update(any(App.class), any(Wrapper.class));
    }

    @Test
    void unsafeVueSourceFailsBeforeProviderAndRecordsBoundedFailure() {
        App app = generatedApp(APP_ID, OWNER_ID, CodeGenTypeEnum.VUE_PROJECT);
        BusinessException sourceFailure = new BusinessException(
                ErrorCode.OPERATION_ERROR, "Current Vue project source is unavailable or unsafe");
        given(appMapper.selectById(APP_ID)).willReturn(app);
        given(vueProjectSourceContextLoader.load(APP_ID)).willThrow(sourceFailure);

        List<AppGenerationEvent> events = appService.chatToGenCode(
                APP_ID, "修改", user(OWNER_ID)).collectList().block();

        assertFailedGeneration(events);
        verify(chatHistoryService).addAiFailureMessage(APP_ID, OWNER_ID, sourceFailure);
        verify(aiCodeGeneratorFacadeProvider, never()).getIfAvailable();
        verify(deploymentLocalServer, never()).requirePreviewAvailable();
    }

    @Test
    void laterVueOrdinaryAnswerCompletesButMalformedEnvelopeFails() {
        App app = generatedApp(APP_ID, OWNER_ID, CodeGenTypeEnum.VUE_PROJECT);
        VueProjectSourceSnapshot source = sourceSnapshot("stable source");
        given(appMapper.selectById(APP_ID)).willReturn(app);
        given(vueProjectSourceContextLoader.load(APP_ID)).willReturn(source);
        AiCodeGeneratorFacade.CodeResponseFormatException ordinaryFailure =
                new AiCodeGeneratorFacade.CodeResponseFormatException(
                        new VueProjectCodeParser.NoProjectEnvelopeException());
        CodeGenerationSession ordinarySession = mockGenerationSession(Flux.concat(
                Flux.just("当前页面使用哈希路由。"), Flux.error(ordinaryFailure)));
        org.mockito.Mockito.doReturn(ordinarySession)
                .when(aiCodeGeneratorFacade)
                .startCodeGeneration(any(), eq(CodeGenTypeEnum.VUE_PROJECT), eq(APP_ID));

        assertSuccessfulGeneration(appService.chatToGenCode(
                APP_ID, "使用什么路由？", user(OWNER_ID)).collectList().block(),
                "当前页面使用哈希路由。");
        verify(ordinarySession, never()).commitAfter(any());

        AiCodeGeneratorFacade.CodeResponseFormatException malformedFailure =
                new AiCodeGeneratorFacade.CodeResponseFormatException(
                        new VueProjectCodeParser.VueProjectProtocolException("incomplete"));
        CodeGenerationSession malformedSession = mockGenerationSession(Flux.concat(
                Flux.just("<<<AUTO_CODE_PROJECT_V1>>>"), Flux.error(malformedFailure)));
        org.mockito.Mockito.doReturn(malformedSession)
                .when(aiCodeGeneratorFacade)
                .startCodeGeneration(any(), eq(CodeGenTypeEnum.VUE_PROJECT), eq(APP_ID));

        List<AppGenerationEvent> malformedEvents = appService.chatToGenCode(
                APP_ID, "现在修改", user(OWNER_ID)).collectList().block();
        assertFailedGeneration(malformedEvents, "<<<AUTO_CODE_PROJECT_V1>>>");
        verify(malformedSession, never()).commitAfter(any());
        verify(malformedSession).rollback();
    }

    @Test
    void chatMemoryRefreshFailureDoesNotChangeSuccessfulChunksOrCompletion() {
        App app = generatedApp(APP_ID, OWNER_ID, CodeGenTypeEnum.HTML);
        given(appMapper.selectById(APP_ID)).willReturn(app);
        given(aiCodeGeneratorFacade.generateAndSaveCodeStream(
                "question", CodeGenTypeEnum.HTML, APP_ID))
                .willReturn(Flux.just(" leading ", "trailing\n"));
        org.mockito.Mockito.doThrow(new IllegalStateException("Redis unavailable"))
                .when(chatMemoryService).refresh(APP_ID);

        List<AppGenerationEvent> events = appService.chatToGenCode(
                APP_ID, "question", user(OWNER_ID)).collectList().block();

        assertSuccessfulGeneration(events, " leading ", "trailing\n");
        verify(chatMemoryService).invalidate(APP_ID);
        verify(chatHistoryService).addChatMessage(
                APP_ID, OWNER_ID, " leading trailing\n", ChatHistoryMessageTypeEnum.AI);
    }

    @Test
    void leaseLossCancelsProviderRecordsFailureAndNeverCompletes() throws Exception {
        App app = generatedApp(APP_ID, OWNER_ID, CodeGenTypeEnum.HTML);
        Sinks.Many<String> codeSink = Sinks.many().unicast().onBackpressureBuffer();
        CodeGenerationSession generationSession = mockGenerationSession(codeSink.asFlux());
        given(appMapper.selectById(APP_ID)).willReturn(app);
        org.mockito.Mockito.doReturn(generationSession)
                .when(aiCodeGeneratorFacade)
                .startCodeGeneration("message", CodeGenTypeEnum.HTML, APP_ID);
        List<AppGenerationEvent> received = new ArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch terminated = new CountDownLatch(1);

        appService.chatToGenCode(APP_ID, "message", user(OWNER_ID))
                .subscribe(received::add, error -> {
                    failure.set(error);
                    terminated.countDown();
                }, terminated::countDown);
        codeSink.tryEmitNext("partial ");
        appProcessingLeaseManager.lose(APP_ID);

        assertThat(terminated.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(failure.get()).isNull();
        assertFailedGeneration(received, "partial ");
        assertThat(received).noneMatch(AppGenerationEvent.Completed.class::isInstance);
        verify(generationSession, org.mockito.Mockito.timeout(1_000)).rollback();
        verify(generationSession, never()).commitAfter(any());
        verify(chatHistoryService).addAiFailureMessage(
                eq(APP_ID), eq(OWNER_ID), any(AppProcessingLeaseLostException.class));
        verify(chatMemoryService).refresh(APP_ID);
        verify(deploymentLocalServer, never()).issuePreview(anyLong(), any());

        codeSink.tryEmitComplete();
        verify(chatHistoryService, never()).addChatMessage(
                APP_ID, OWNER_ID, "partial ", ChatHistoryMessageTypeEnum.AI);
    }

    @Test
    void subsequentPlainConversationKeepsCurrentCodeAndCompletesNormally() {
        App app = generatedApp(APP_ID, OWNER_ID, CodeGenTypeEnum.MULTI_FILE);
        User loginUser = user(OWNER_ID);
        String question = "还记得我们刚才做了什么吗？";
        String reply = "记得，我们刚才做了一个简单的留言板。";
        AiCodeGeneratorFacade.CodeResponseFormatException parseFailure =
                new AiCodeGeneratorFacade.CodeResponseFormatException(
                        new IllegalArgumentException("未找到 HTML 代码块"));
        CodeGenerationSession generationSession = mockGenerationSession(
                Flux.concat(Flux.just(reply), Flux.error(parseFailure)));
        String memoryPrompt = "历史: 留言板和 <html></html>; 当前: " + question;
        given(appMapper.selectById(APP_ID)).willReturn(app);
        given(chatMemoryService.buildPrompt(APP_ID, 10_001L, question, false))
                .willReturn(memoryPrompt);
        org.mockito.Mockito.doReturn(generationSession)
                .when(aiCodeGeneratorFacade)
                .startCodeGeneration(any(), eq(CodeGenTypeEnum.MULTI_FILE), eq(APP_ID));

        List<AppGenerationEvent> events = appService.chatToGenCode(
                APP_ID, question, loginUser).collectList().block();

        assertSuccessfulGeneration(events, reply);
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiCodeGeneratorFacade).startCodeGeneration(
                promptCaptor.capture(), eq(CodeGenTypeEnum.MULTI_FILE), eq(APP_ID));
        assertThat(promptCaptor.getValue()).isEqualTo(memoryPrompt);
        verify(chatMemoryService).buildPrompt(APP_ID, 10_001L, question, false);
        verify(chatHistoryService).addChatMessage(
                APP_ID, OWNER_ID, question, ChatHistoryMessageTypeEnum.USER);
        verify(chatHistoryService).addChatMessage(
                APP_ID, OWNER_ID, reply, ChatHistoryMessageTypeEnum.AI);
        verify(chatHistoryService, never()).addAiFailureMessage(anyLong(), anyLong(), any());
        verify(chatMemoryService).refresh(APP_ID);
        verify(generationSession, never()).commitAfter(any());
        verify(deploymentLocalServer).preparePreview(APP_ID, CodeGenTypeEnum.MULTI_FILE);
        verify(previewPublication).commit();
        verify(appMapper, never()).update(any(App.class), any(Wrapper.class));
    }

    @Test
    void subsequentMalformedCodeResponseStillFailsStrictly() {
        App app = generatedApp(APP_ID, OWNER_ID, CodeGenTypeEnum.MULTI_FILE);
        String incompleteCode = "index.html\n```html\n<html></html>\n```";
        AiCodeGeneratorFacade.CodeResponseFormatException parseFailure =
                new AiCodeGeneratorFacade.CodeResponseFormatException(
                        new IllegalArgumentException("未找到 CSS 代码块"));
        CodeGenerationSession generationSession = mockGenerationSession(
                Flux.concat(Flux.just(incompleteCode), Flux.error(parseFailure)));
        given(appMapper.selectById(APP_ID)).willReturn(app);
        org.mockito.Mockito.doReturn(generationSession)
                .when(aiCodeGeneratorFacade)
                .startCodeGeneration("修改页面", CodeGenTypeEnum.MULTI_FILE, APP_ID);
        List<AppGenerationEvent> received = new ArrayList<>();

        appService.chatToGenCode(APP_ID, "修改页面", user(OWNER_ID))
                .doOnNext(received::add)
                .blockLast();

        assertFailedGeneration(received, incompleteCode);
        verify(chatHistoryService).addAiFailureMessage(APP_ID, OWNER_ID, parseFailure);
        verify(chatMemoryService).refresh(APP_ID);
        verify(chatHistoryService, never()).addChatMessage(
                APP_ID, OWNER_ID, incompleteCode, ChatHistoryMessageTypeEnum.AI);
        verify(deploymentLocalServer, never()).issuePreview(anyLong(), any());
        verify(generationSession, never()).commitAfter(any());
        verify(generationSession).rollback();
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
                () -> appService.chatToGenCode(
                        APP_ID, "message", user(OWNER_ID)).blockLast(),
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

        assertFailedGeneration(appService.chatToGenCode(
                APP_ID, "message", user(OWNER_ID)).collectList().block());
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

        appService.chatToGenCode(APP_ID, null, user(OWNER_ID))
                .doOnNext(received::add)
                .blockLast();

        assertFailedGeneration(received);
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
                "build a site", CodeGenTypeEnum.VUE_PROJECT, APP_ID))
                .willReturn(Flux.concat(Flux.just("partial "), Flux.error(generationFailure)));

        List<AppGenerationEvent> events = appService.chatToGenCode(
                APP_ID, null, user(OWNER_ID)).collectList().block();

        assertFailedGeneration(events, "partial ");
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
        given(deploymentLocalServer.preparePreview(APP_ID, CodeGenTypeEnum.HTML))
                .willThrow(previewFailure);

        List<AppGenerationEvent> events = appService.chatToGenCode(
                APP_ID, "refine", user(OWNER_ID)).collectList().block();

        assertFailedGeneration(events, "new version");
        verify(generationSession, never()).commit();
        verify(generationSession).rollback();
        verify(appMapper, never()).update(any(App.class), any(Wrapper.class));
    }

    @Test
    void failedInitialTypePersistenceRollsBackPreparedPreviewAndOmitsCompletedEvent() {
        App app = existingApp(OWNER_ID);
        app.setInitPrompt("build a site");
        given(appMapper.selectById(APP_ID)).willReturn(app);
        given(aiCodeGeneratorFacade.generateAndSaveCodeStream(
                "build a site", CodeGenTypeEnum.VUE_PROJECT, APP_ID))
                .willReturn(Flux.just("complete code"), Flux.just("retry code"));
        given(appMapper.update(any(App.class), any(UpdateWrapper.class))).willReturn(0, 1);
        given(chatHistoryService.addChatMessage(
                APP_ID, OWNER_ID, "complete code", ChatHistoryMessageTypeEnum.AI))
                .willReturn(7001L);
        given(chatHistoryService.addChatMessage(
                APP_ID, OWNER_ID, "retry code", ChatHistoryMessageTypeEnum.AI))
                .willReturn(7002L);
        List<AppGenerationEvent> received = new ArrayList<>();

        appService.chatToGenCode(APP_ID, null, user(OWNER_ID))
                .doOnNext(received::add)
                .blockLast();

        assertFailedGeneration(received, "complete code");
        InOrder failureOrder = inOrder(
                deploymentLocalServer, previewPublication, chatHistoryService, appMapper);
        failureOrder.verify(deploymentLocalServer)
                .preparePreview(APP_ID, CodeGenTypeEnum.VUE_PROJECT);
        failureOrder.verify(chatHistoryService).addChatMessage(
                APP_ID, OWNER_ID, "complete code", ChatHistoryMessageTypeEnum.AI);
        failureOrder.verify(appMapper).update(any(App.class), any(UpdateWrapper.class));
        failureOrder.verify(previewPublication).close();
        failureOrder.verify(chatHistoryService).addAiFailureMessage(
                eq(APP_ID), eq(OWNER_ID), any(BusinessException.class));

        assertSuccessfulGeneration(appService.chatToGenCode(
                APP_ID, "ignored again", user(OWNER_ID)).collectList().block(), "retry code");
        verify(aiCodeGeneratorFacade, times(2)).generateAndSaveCodeStream(
                "build a site", CodeGenTypeEnum.VUE_PROJECT, APP_ID);
        verify(appMapper, times(2)).update(any(App.class), any(UpdateWrapper.class));
        verify(deploymentLocalServer, times(2))
                .preparePreview(APP_ID, CodeGenTypeEnum.VUE_PROJECT);
        verify(previewPublication, times(2)).close();
        verify(previewPublication).commit();
    }

    @Test
    void initialPreviewFailureDoesNotPersistTypeOrEmitCompletedAndRetryUsesInitPrompt() {
        App app = existingApp(OWNER_ID);
        app.setInitPrompt("build a site");
        IllegalStateException previewFailure = new IllegalStateException("preview failed");
        given(appMapper.selectById(APP_ID)).willReturn(app);
        given(aiCodeGeneratorFacade.generateAndSaveCodeStream(
                "build a site", CodeGenTypeEnum.VUE_PROJECT, APP_ID))
                .willReturn(Flux.just(" complete chunk "), Flux.just(" retry chunk "));
        given(appMapper.update(any(App.class), any(UpdateWrapper.class))).willReturn(1);
        given(deploymentLocalServer.preparePreview(APP_ID, CodeGenTypeEnum.VUE_PROJECT))
                .willThrow(previewFailure);
        List<AppGenerationEvent> received = new ArrayList<>();

        appService.chatToGenCode(APP_ID, "ignored", user(OWNER_ID))
                .doOnNext(received::add)
                .blockLast();

        assertFailedGeneration(received, " complete chunk ");
        verify(appMapper, never()).update(any(App.class), any(Wrapper.class));
        verify(deploymentLocalServer, never()).revokePreview(anyLong());

        org.mockito.BDDMockito.willReturn(previewPublication)
                .given(deploymentLocalServer)
                .preparePreview(APP_ID, CodeGenTypeEnum.VUE_PROJECT);
        assertSuccessfulGeneration(appService.chatToGenCode(
                APP_ID, "still ignored", user(OWNER_ID)).collectList().block(), " retry chunk ");
        verify(aiCodeGeneratorFacade, times(2)).generateAndSaveCodeStream(
                "build a site", CodeGenTypeEnum.VUE_PROJECT, APP_ID);
        verify(appMapper).update(any(App.class), any(UpdateWrapper.class));
        verify(deploymentLocalServer, times(2))
                .preparePreview(APP_ID, CodeGenTypeEnum.VUE_PROJECT);
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
                .startCodeGeneration("build a site", CodeGenTypeEnum.VUE_PROJECT, APP_ID);
        ArgumentCaptor<String> attemptCaptor = ArgumentCaptor.forClass(String.class);

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
        verify(chatMemoryService).refresh(APP_ID);
        verify(chatHistoryService, never()).addAiFailureMessage(anyLong(), anyLong(), any());
        verify(chatHistoryService, never()).addChatMessage(
                APP_ID, OWNER_ID, "partial", ChatHistoryMessageTypeEnum.AI);
        verify(appMapper).startGenerationAttempt(
                eq(APP_ID), eq(OWNER_ID), eq(AppGenerationStatusEnum.PENDING.getValue()),
                isNull(), attemptCaptor.capture(), any(Date.class));
        verify(appMapper).failGenerationAttempt(
                eq(APP_ID), eq(OWNER_ID), eq(attemptCaptor.getValue()),
                eq("GENERATION_CANCELLED"), eq("生成已取消"), any(Date.class));
    }

    @Test
    void completeAttemptDeadlineEmitsHeartbeatsThenOneSafeFailure() {
        App app = generatedApp(APP_ID, OWNER_ID, CodeGenTypeEnum.HTML);
        CodeGenerationSession generationSession = mockGenerationSession(Flux.never());
        given(appMapper.selectById(APP_ID)).willReturn(app);
        org.mockito.Mockito.doReturn(generationSession)
                .when(aiCodeGeneratorFacade)
                .startCodeGeneration("message", CodeGenTypeEnum.HTML, APP_ID);
        AppGenerationProperties tightLimits = new AppGenerationProperties(
                Duration.ofSeconds(2),
                Duration.ofMillis(4_500),
                Duration.ofSeconds(5),
                Duration.ofSeconds(1),
                Duration.ofSeconds(6)
        );
        AppServiceImpl serviceWithTightDeadline =
                createAppService(transactionTemplate, tightLimits);

        StepVerifier.withVirtualTime(() -> serviceWithTightDeadline.chatToGenCode(
                        APP_ID, "message", user(OWNER_ID)))
                .expectSubscription()
                .thenAwait(Duration.ofSeconds(1))
                .expectNext(new AppGenerationEvent.Heartbeat())
                .thenAwait(Duration.ofSeconds(1))
                .expectNext(new AppGenerationEvent.Heartbeat())
                .thenAwait(Duration.ofSeconds(1))
                .expectNext(new AppGenerationEvent.Heartbeat())
                .thenAwait(Duration.ofSeconds(1))
                .expectNext(new AppGenerationEvent.Heartbeat())
                .thenAwait(Duration.ofMillis(500))
                .assertNext(event -> {
                    assertThat(event).isInstanceOf(AppGenerationEvent.Failed.class);
                    AppGenerationEvent.Failed failed = (AppGenerationEvent.Failed) event;
                    assertThat(failed.message()).isEqualTo("生成超时，请重试");
                })
                .verifyComplete();

        verify(appMapper).failGenerationAttempt(
                eq(APP_ID), eq(OWNER_ID), any(), eq("GENERATION_TIMEOUT"),
                eq("生成超时，请重试"), any(Date.class));
        verify(generationSession).rollback();
        verify(chatHistoryService, never()).addChatMessage(
                eq(APP_ID), eq(OWNER_ID), any(), eq(ChatHistoryMessageTypeEnum.AI));
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

        List<AppGenerationEvent> firstEvents = appService.chatToGenCode(
                APP_ID, "message", user(OWNER_ID)).collectList().block();

        assertFailedGeneration(firstEvents);
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

        appService.chatToGenCode(APP_ID, "message", user(OWNER_ID))
                .doOnNext(received::add)
                .blockLast();

        assertFailedGeneration(received, "first");
        verify(previewPublication).close();
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

        List<AppGenerationEvent> events = appService.chatToGenCode(
                APP_ID, "message", user(OWNER_ID)).collectList().block();

        assertFailedGeneration(events);
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
        verify(deploymentLocalServer).preparePreview(APP_ID, CodeGenTypeEnum.HTML);
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
        app.setGenerationStatus(AppGenerationStatusEnum.FAILED.getValue());
        app.setGenerationAttemptId("private-attempt-id");
        app.setGenerationFailureCode("INVALID_AI_RESPONSE");
        app.setGenerationFailureMessage("AI 返回内容不完整，请重试");
        app.setGenerationStartedTime(new Date(500));
        app.setGenerationFinishedTime(new Date(750));
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
        assertThat(summary.getGenerationStatus())
                .isEqualTo(AppGenerationStatusEnum.FAILED.getValue());
        assertThat(ReflectionUtils.findField(AppVO.class, "generationAttemptId")).isNull();
        assertThat(ReflectionUtils.findField(AppVO.class, "generationFailureMessage")).isNull();
        assertThat(ReflectionTestUtils.getField(detail, "initPrompt"))
                .isEqualTo("Create a private analytics dashboard");
        assertThat(detail.getDeployUrl()).isNull();
        assertThat(detail.getGenerationFailureCode()).isEqualTo("INVALID_AI_RESPONSE");
        assertThat(detail.getGenerationFailureMessage())
                .isEqualTo("AI 返回内容不完整，请重试");
        assertThat(ReflectionUtils.findField(AppDetailVO.class, "generationAttemptId")).isNull();
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
        assertThat(detail.getGenerationStatus())
                .isEqualTo(AppGenerationStatusEnum.PENDING.getValue());
        assertThat(ReflectionUtils.findField(PublicAppDetailVO.class, "initPrompt")).isNull();
        assertThat(ReflectionUtils.findField(PublicAppDetailVO.class, "userId")).isNull();
        assertThat(ReflectionUtils.findField(PublicAppDetailVO.class, "deployKey")).isNull();
        assertThat(ReflectionUtils.findField(
                PublicAppDetailVO.class, "generationAttemptId")).isNull();
        assertThat(ReflectionUtils.findField(
                PublicAppDetailVO.class, "generationFailureMessage")).isNull();
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
        verify(chatMemoryService).purge(APP_ID);
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
                chatMemoryService,
                undeployment,
                deploymentLocalServer
        );
        order.verify(appMapper).selectById(APP_ID);
        order.verify(deploymentFileManager).prepareUndeployment(deployKey);
        order.verify(chatMemoryService).purge(APP_ID);
        order.verify(appMapper).delete(any(QueryWrapper.class));
        order.verify(undeployment).commit();
        order.verify(deploymentLocalServer).revokePreview(APP_ID);
        verify(undeployment, never()).rollback();
    }

    @Test
    void ownerDeleteRestoresDeploymentWhenMemoryPurgeFailsBeforeTransaction() {
        String deployKey = "DELM01";
        App app = existingApp(OWNER_ID);
        app.setDeployKey(deployKey);
        Undeployment undeployment = mock(Undeployment.class);
        BusinessException purgeFailure = new BusinessException(
                ErrorCode.OPERATION_ERROR, "清理对话记忆失败");
        given(appMapper.selectById(APP_ID)).willReturn(app);
        given(deploymentFileManager.prepareUndeployment(deployKey)).willReturn(undeployment);
        org.mockito.Mockito.doThrow(purgeFailure)
                .when(chatMemoryService).purge(APP_ID);

        BusinessException thrown = expectBusinessException(
                () -> appService.deleteAppByUser(APP_ID, user(OWNER_ID)),
                ErrorCode.OPERATION_ERROR);

        assertThat(thrown.getCause()).isSameAs(purgeFailure);
        verify(undeployment).rollback();
        verify(appMapper, never()).delete(any(Wrapper.class));
        verify(chatHistoryService, never()).deleteByAppId(anyLong());
        verify(transactionTemplate, never()).execute(any());
        verify(deploymentLocalServer, never()).revokePreview(anyLong());
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
        InOrder order = inOrder(
                chatMemoryService, appMapper, chatHistoryService,
                transactionManager, undeployment);
        order.verify(chatMemoryService).purge(APP_ID);
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
        verify(chatMemoryService).purge(APP_ID);
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
        request.setGenerationStatus(AppGenerationStatusEnum.FAILED.getValue());
        request.setDeployKey("deploy-001");
        request.setPriority(99);
        request.setUserId(OWNER_ID);

        QueryWrapper<App> wrapper = appService.getQueryWrapper(request);
        String sql = compactSql(wrapper);
        Map<String, Object> parameters = wrapper.getParamNameValuePairs();

        assertThat(sql).contains(
                "id=", "appnamelike", "coverlike", "initpromptlike",
                "codegentype=", "generationstatus=", "deploykey=", "priority=", "userid=");
        assertThat(parameters).containsValues(
                APP_ID, "%portal%", "%cdn.example%", "%dashboard%", "html",
                AppGenerationStatusEnum.FAILED.getValue(), "deploy-001", 99, OWNER_ID);
        assertThat(Arrays.stream(AppQueryDTO.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName))
                .doesNotContain("deployedTime", "editTime", "createTime", "updateTime", "isDelete");
    }

    @Test
    void adminQueryRejectsUnknownGenerationStatus() {
        AppQueryDTO request = new AppQueryDTO();
        request.setGenerationStatus("success");

        expectBusinessException(
                () -> appService.getQueryWrapper(request),
                ErrorCode.PARAMS_ERROR);
    }

    @Test
    void adminQueryAllowsGenerationStatusAndLifecycleTimeSorting() {
        AppQueryDTO request = new AppQueryDTO();
        request.setGenerationStatus(AppGenerationStatusEnum.GENERATING.getValue());
        request.setSortField("generationStartedTime");
        request.setSortOrder("ascend");

        String sql = compactSql(appService.getQueryWrapper(request));

        assertThat(sql).contains(
                "generationstatus=",
                "orderbygenerationstartedtimeasc");
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
        app.setGenerationStatus(AppGenerationStatusEnum.SUCCEEDED.getValue());
        return app;
    }

    private static App existingApp(long ownerId) {
        return existingApp(APP_ID, ownerId);
    }

    private static App existingApp(long appId, long ownerId) {
        App app = new App();
        app.setId(appId);
        app.setUserId(ownerId);
        app.setGenerationStatus(AppGenerationStatusEnum.PENDING.getValue());
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

    private static void assertFailedGeneration(
            List<AppGenerationEvent> events,
            String... expectedChunks
    ) {
        assertThat(events).isNotNull().hasSize(expectedChunks.length + 1);
        assertThat(events.subList(0, expectedChunks.length))
                .containsExactlyElementsOf(Arrays.stream(expectedChunks)
                        .map(AppGenerationEvent.Content::new)
                        .toList());
        assertThat(events.getLast()).isInstanceOf(AppGenerationEvent.Failed.class);
        AppGenerationEvent.Failed failed =
                (AppGenerationEvent.Failed) events.getLast();
        assertThat(failed.code()).isEqualTo(ErrorCode.OPERATION_ERROR.getCode());
        assertThat(failed.status()).isEqualTo(AppGenerationStatusEnum.FAILED.getValue());
        assertThat(failed.message()).isNotBlank();
        assertThat(events).noneMatch(AppGenerationEvent.Completed.class::isInstance);
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
        return createAppService(template, AppGenerationProperties.defaults());
    }

    private AppServiceImpl createAppService(
            TransactionTemplate template,
            AppGenerationProperties generationProperties
    ) {
        AppServiceImpl service = new AppServiceImpl(
                aiCodeGeneratorFacadeProvider,
                deploymentFileManager,
                deployKeyGenerator,
                deploymentProperties,
                deploymentLocalServer,
                chatHistoryService,
                chatMemoryService,
                appProcessingLeaseManager,
                template,
                vueProjectSourceContextLoader,
                generationProperties
        );
        ReflectionTestUtils.setField(service, "baseMapper", appMapper);
        return service;
    }

    private static VueProjectSourceSnapshot sourceSnapshot(String content) {
        return new VueProjectSourceSnapshot(List.of(
                new VueProjectFile("src/App.vue", content)), content.length());
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

    private static final class TestLeaseManager implements AppProcessingLeaseManager {

        private final Map<Long, TestLease> active = new ConcurrentHashMap<>();

        @Override
        public AppProcessingLease acquire(Long appId) {
            TestLease lease = new TestLease(appId, this);
            if (active.putIfAbsent(appId, lease) != null) {
                throw new BusinessException(
                        ErrorCode.OPERATION_ERROR, "应用正在处理中，请稍后重试");
            }
            return lease;
        }

        void lose(long appId) {
            TestLease lease = active.get(appId);
            if (lease != null) {
                lease.lose();
            }
        }

        private void release(TestLease lease) {
            active.remove(lease.appId, lease);
        }
    }

    private static final class TestLease
            implements AppProcessingLeaseManager.AppProcessingLease {

        private final long appId;

        private final TestLeaseManager manager;

        private final Sinks.Empty<Void> loss = Sinks.empty();

        private boolean closed;

        private boolean lost;

        private TestLease(long appId, TestLeaseManager manager) {
            this.appId = appId;
            this.manager = manager;
        }

        @Override
        public long appId() {
            return appId;
        }

        @Override
        public synchronized boolean isLost() {
            return lost;
        }

        @Override
        public synchronized void assertHeld() {
            if (closed || lost) {
                throw new AppProcessingLeaseLostException();
            }
        }

        @Override
        public Mono<Void> lossSignal() {
            return loss.asMono();
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            manager.release(this);
        }

        private synchronized void lose() {
            if (!lost) {
                lost = true;
                loss.tryEmitError(new AppProcessingLeaseLostException());
            }
        }
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
