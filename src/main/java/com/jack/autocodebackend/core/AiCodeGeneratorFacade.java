package com.jack.autocodebackend.core;

import com.jack.autocodebackend.ai.AiCodeGeneratorService;
import com.jack.autocodebackend.ai.model.CodeResult;
import com.jack.autocodebackend.ai.model.enums.CodeGenTypeEnum;
import com.jack.autocodebackend.core.parser.CodeParserExecutor;
import com.jack.autocodebackend.core.saver.CodeFileSaverExecutor;
import com.jack.autocodebackend.exception.BusinessException;
import com.jack.autocodebackend.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.util.function.Supplier;

/**
 * AI 代码生成外观类，组合生成、解析和保存功能。
 */
@Service
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
     * @return 保存的目录
     */
    public File generateAndSaveCode(String userMessage, CodeGenTypeEnum codeGenTypeEnum) {
        requireCodeGenType(codeGenTypeEnum);
        CodeResult result = switch (codeGenTypeEnum) {
            case HTML -> aiCodeGeneratorService.generateHtmlCode(userMessage);
            case MULTI_FILE -> aiCodeGeneratorService.generateMultiFileCode(userMessage);
        };
        return CodeFileSaverExecutor.executeSaver(result);
    }

    /**
     * 统一入口：流式返回代码，并在上游正常完成后解析和保存。
     * 解析或保存失败会作为流错误传递给订阅者。
     *
     * @param userMessage     用户提示词
     * @param codeGenTypeEnum 生成类型
     * @return AI 原始代码流
     */
    public Flux<String> generateAndSaveCodeStream(String userMessage, CodeGenTypeEnum codeGenTypeEnum) {
        requireCodeGenType(codeGenTypeEnum);
        return switch (codeGenTypeEnum) {
            case HTML -> processCodeStream(
                    () -> aiCodeGeneratorService.generateHtmlCodeStream(userMessage),
                    CodeGenTypeEnum.HTML
            );
            case MULTI_FILE -> processCodeStream(
                    () -> aiCodeGeneratorService.generateMultiFileCodeStream(userMessage),
                    CodeGenTypeEnum.MULTI_FILE
            );
        };
    }

    public File generateAndSaveHtmlCode(String userMessage) {
        return generateAndSaveCode(userMessage, CodeGenTypeEnum.HTML);
    }

    public File generateAndSaveMultiFileCode(String userMessage) {
        return generateAndSaveCode(userMessage, CodeGenTypeEnum.MULTI_FILE);
    }

    private Flux<String> processCodeStream(
            Supplier<Flux<String>> codeStreamSupplier,
            CodeGenTypeEnum codeGenType
    ) {
        return Flux.defer(() -> {
            Flux<String> codeStream = codeStreamSupplier.get();
            if (codeStream == null) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "AI 代码流不能为空");
            }

            StringBuilder codeBuilder = new StringBuilder();
            Flux<String> saveStage = Mono.fromCallable(() -> parseAndSaveCode(codeBuilder.toString(), codeGenType))
                    .subscribeOn(Schedulers.boundedElastic())
                    .doOnNext(savedDir -> log.info("代码保存成功，路径：{}", savedDir.getAbsolutePath()))
                    .thenMany(Flux.<String>empty());

            return codeStream
                    .doOnNext(codeBuilder::append)
                    .concatWith(saveStage);
        });
    }

    private File parseAndSaveCode(String completeCode, CodeGenTypeEnum codeGenType) {
        CodeResult parsedResult = CodeParserExecutor.executeParser(completeCode, codeGenType);
        return CodeFileSaverExecutor.executeSaver(parsedResult);
    }

    private void requireCodeGenType(CodeGenTypeEnum codeGenType) {
        if (codeGenType == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "生成类型为空");
        }
    }
}
