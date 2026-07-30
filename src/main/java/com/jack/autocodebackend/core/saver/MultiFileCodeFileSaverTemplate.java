package com.jack.autocodebackend.core.saver;

import cn.hutool.core.util.StrUtil;
import com.jack.autocodebackend.ai.model.MultiFileCodeResult;
import com.jack.autocodebackend.ai.model.enums.CodeGenTypeEnum;
import com.jack.autocodebackend.core.validator.HtmlDocumentValidator;
import com.jack.autocodebackend.exception.BusinessException;
import com.jack.autocodebackend.exception.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * HTML、CSS、JavaScript 多文件保存器。
 */
@Component
public final class MultiFileCodeFileSaverTemplate extends CodeFileSaverTemplate<MultiFileCodeResult> {

    @Override
    public Class<MultiFileCodeResult> resultType() {
        return MultiFileCodeResult.class;
    }

    @Override
    protected CodeGenTypeEnum getCodeType() {
        return CodeGenTypeEnum.MULTI_FILE;
    }

    @Override
    protected void saveFiles(MultiFileCodeResult result, String baseDirPath) {
        writeToFile(baseDirPath, "index.html", result.getHtmlCode());
        writeToFile(baseDirPath, "style.css", result.getCssCode());
        writeToFile(baseDirPath, "script.js", result.getJsCode());
    }

    @Override
    protected void validateInput(MultiFileCodeResult result) {
        super.validateInput(result);
        requireCode(result.getHtmlCode(), "HTML");
        requireCode(result.getCssCode(), "CSS");
        requireCode(result.getJsCode(), "JavaScript");
        if (!HtmlDocumentValidator.isCompleteDocument(result.getHtmlCode())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "HTML 代码不是完整的 HTML 文档");
        }
    }

    private void requireCode(String code, String codeType) {
        if (StrUtil.isBlank(code)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, codeType + " 代码内容不能为空");
        }
    }
}
