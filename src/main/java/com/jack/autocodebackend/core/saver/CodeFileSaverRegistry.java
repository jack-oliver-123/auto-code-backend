package com.jack.autocodebackend.core.saver;

import com.jack.autocodebackend.ai.model.CodeResult;
import com.jack.autocodebackend.ai.model.enums.CodeGenTypeEnum;
import com.jack.autocodebackend.exception.BusinessException;
import com.jack.autocodebackend.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public final class CodeFileSaverRegistry {

    private final Map<CodeGenTypeEnum, CodeResultSaver> savers;

    public CodeFileSaverRegistry(List<CodeResultSaver> savers) {
        EnumMap<CodeGenTypeEnum, CodeResultSaver> indexed =
                new EnumMap<>(CodeGenTypeEnum.class);
        for (CodeResultSaver saver : savers) {
            CodeResultSaver previous = indexed.putIfAbsent(saver.codeGenType(), saver);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate code saver for " + saver.codeGenType().getValue());
            }
        }
        this.savers = Map.copyOf(indexed);
    }

    public static CodeFileSaverRegistry legacy() {
        return new CodeFileSaverRegistry(List.of(
                new HtmlCodeFileSaverTemplate(),
                new MultiFileCodeFileSaverTemplate()
        ));
    }

    public CodeFilePublication publish(
            CodeResult result,
            CodeGenTypeEnum codeGenType,
            Long appId
    ) {
        if (result == null || codeGenType == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "Code result and generation type are required");
        }
        CodeResultSaver saver = savers.get(codeGenType);
        if (saver == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "No code saver is available for " + codeGenType.getValue());
        }
        if (!saver.resultType().isInstance(result)) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "AI result does not match " + codeGenType.getValue());
        }
        return saver.publish(result, appId);
    }
}
