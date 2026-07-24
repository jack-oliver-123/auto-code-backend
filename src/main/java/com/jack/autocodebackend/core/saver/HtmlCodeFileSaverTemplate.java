package com.jack.autocodebackend.core.saver;

import cn.hutool.core.util.StrUtil;
import com.jack.autocodebackend.ai.model.HtmlCodeResult;
import com.jack.autocodebackend.ai.model.enums.CodeGenTypeEnum;
import com.jack.autocodebackend.exception.BusinessException;
import com.jack.autocodebackend.exception.ErrorCode;

/**
 * HTML 单文件保存器。
 */
public final class HtmlCodeFileSaverTemplate extends CodeFileSaverTemplate<HtmlCodeResult> {

    @Override
    protected CodeGenTypeEnum getCodeType() {
        return CodeGenTypeEnum.HTML;
    }

    @Override
    protected void saveFiles(HtmlCodeResult result, String baseDirPath) {
        writeToFile(baseDirPath, "index.html", result.getHtmlCode());
    }

    @Override
    protected void validateInput(HtmlCodeResult result) {
        super.validateInput(result);
        if (StrUtil.isBlank(result.getHtmlCode())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "HTML 代码内容不能为空");
        }
    }
}
