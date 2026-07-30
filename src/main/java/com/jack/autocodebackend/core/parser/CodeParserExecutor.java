package com.jack.autocodebackend.core.parser;

import com.jack.autocodebackend.ai.model.CodeResult;
import com.jack.autocodebackend.ai.model.enums.CodeGenTypeEnum;
import com.jack.autocodebackend.exception.BusinessException;
import com.jack.autocodebackend.exception.ErrorCode;

/**
 * 根据代码生成类型选择解析策略。
 */
public final class CodeParserExecutor {

    private static final HtmlCodeParser HTML_CODE_PARSER = new HtmlCodeParser();
    private static final MultiFileCodeParser MULTI_FILE_CODE_PARSER = new MultiFileCodeParser();
    private static final VueProjectCodeParser VUE_PROJECT_CODE_PARSER = new VueProjectCodeParser();

    private CodeParserExecutor() {
    }

    public static CodeResult executeParser(String codeContent, CodeGenTypeEnum codeGenType) {
        if (codeGenType == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "代码生成类型为空");
        }
        return switch (codeGenType) {
            case HTML -> HTML_CODE_PARSER.parseCode(codeContent);
            case MULTI_FILE -> MULTI_FILE_CODE_PARSER.parseCode(codeContent);
            case VUE_PROJECT -> VUE_PROJECT_CODE_PARSER.parseCode(codeContent);
        };
    }
}
