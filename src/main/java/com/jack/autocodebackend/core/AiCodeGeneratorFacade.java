package com.jack.autocodebackend.core;

import com.jack.autocodebackend.ai.AiCodeGeneratorService;
import com.jack.autocodebackend.ai.model.CodeResult;
import com.jack.autocodebackend.ai.model.enums.CodeGenTypeEnum;
import com.jack.autocodebackend.core.parser.CodeParserExecutor;
import com.jack.autocodebackend.core.saver.CodeFilePublication;
import com.jack.autocodebackend.core.saver.CodeFileSaverExecutor;
import com.jack.autocodebackend.exception.BusinessException;
import com.jack.autocodebackend.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.util.function.Supplier;

/**
 * AI 代码生成外观类，组合生成、解析和保存功能。
 */
public class AiCodeGeneratorFacade {

    private static final Logger log = LoggerFactory.getLogger(AiCodeGeneratorFacade.class);

    private final AiCodeGeneratorService aiCodeGeneratorService;

    public AiCodeGeneratorFacade(AiCodeGeneratorService aiCodeGeneratorService) {
        this.aiCodeGeneratorService = aiCodeGeneratorService;
    }

    /**
     * 统一入口：根据类型生成并保存代码。
     *
     * @param userMessage     用户提示词
     * @param codeGenTypeEnum 生成类型
     * @param appId           应用 ID
     * @return 保存的目录
     */
    public File generateAndSaveCode(String userMessage, CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        requireCodeGenType(codeGenTypeEnum);
        requireAppId(appId);
        CodeResult result = switch (codeGenTypeEnum) {
            case HTML -> aiCodeGeneratorService.generateHtmlCode(userMessage);
            case MULTI_FILE -> aiCodeGeneratorService.generateMultiFileCode(userMessage);
        };
        return CodeFileSaverExecutor.executeSaver(result, appId);
    }

    /**
     * 统一入口：流式返回代码，并在上游正常完成后解析和保存。
     * 解析或保存失败会作为流错误传递给订阅者。
     *
     * @param userMessage     用户提示词
     * @param codeGenTypeEnum 生成类型
     * @param appId           应用 ID
     * @return AI 原始代码流
     */
    public Flux<String> generateAndSaveCodeStream(
            String userMessage,
            CodeGenTypeEnum codeGenTypeEnum,
            Long appId
    ) {
        requireCodeGenType(codeGenTypeEnum);
        requireAppId(appId);
        return Flux.defer(() -> {
            CodeGenerationSession session = startCodeGeneration(
                    userMessage, codeGenTypeEnum, appId);
            return Flux.using(
                    () -> session,
                    activeSession -> activeSession.stream().concatWith(Flux.defer(() -> {
                        activeSession.commit();
                        return Flux.empty();
                    })),
                    CodeGenerationSession::rollback
            );
        });
    }

    public CodeGenerationSession startCodeGeneration(
            String userMessage,
            CodeGenTypeEnum codeGenTypeEnum,
            Long appId
    ) {
        requireCodeGenType(codeGenTypeEnum);
        requireAppId(appId);
        CodeGenerationSession session = new CodeGenerationSession();
        Flux<String> stream = switch (codeGenTypeEnum) {
            case HTML -> processCodeStream(
                    () -> aiCodeGeneratorService.generateHtmlCodeStream(userMessage),
                    CodeGenTypeEnum.HTML,
                    appId,
                    session
            );
            case MULTI_FILE -> processCodeStream(
                    () -> aiCodeGeneratorService.generateMultiFileCodeStream(userMessage),
                    CodeGenTypeEnum.MULTI_FILE,
                    appId,
                    session
            );
        };
        session.initialize(stream);
        return session;
    }

    public File generateAndSaveHtmlCode(String userMessage, Long appId) {
        return generateAndSaveCode(userMessage, CodeGenTypeEnum.HTML, appId);
    }

    public File generateAndSaveMultiFileCode(String userMessage, Long appId) {
        return generateAndSaveCode(userMessage, CodeGenTypeEnum.MULTI_FILE, appId);
    }

    private Flux<String> processCodeStream(
            Supplier<Flux<String>> codeStreamSupplier,
            CodeGenTypeEnum codeGenType,
            Long appId,
            CodeGenerationSession session
    ) {
        return Flux.defer(() -> {
            Flux<String> codeStream = codeStreamSupplier.get();
            if (codeStream == null) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 代码流不能为空");
            }

            StringBuilder codeBuilder = new StringBuilder();
            Flux<String> saveStage = Mono.fromCallable(
                            () -> parsePublishAndAttach(
                                    codeBuilder.toString(), codeGenType, appId, session)
                    )
                    .subscribeOn(Schedulers.boundedElastic())
                    .doOnNext(savedDirectory -> log.info(
                            "代码保存成功，路径：{}", savedDirectory.getAbsolutePath()))
                    .thenMany(Flux.<String>empty());

            return codeStream
                    .doOnNext(codeBuilder::append)
                    .concatWith(saveStage);
        });
    }

    private CodeFilePublication parseAndPublishCode(
            String completeCode,
            CodeGenTypeEnum codeGenType,
            Long appId
    ) {
        CodeResult parsedResult = CodeParserExecutor.executeParser(completeCode, codeGenType);
        return CodeFileSaverExecutor.executeSaverPublication(parsedResult, appId);
    }

    private File parsePublishAndAttach(
            String completeCode,
            CodeGenTypeEnum codeGenType,
            Long appId,
            CodeGenerationSession session
    ) {
        CodeFilePublication publication = parseAndPublishCode(completeCode, codeGenType, appId);
        session.attach(publication);
        return publication.directory();
    }

    private void requireCodeGenType(CodeGenTypeEnum codeGenType) {
        if (codeGenType == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "生成类型为空");
        }
    }

    private void requireAppId(Long appId) {
        if (appId == null || appId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 ID 必须为正数");
        }
    }
}
