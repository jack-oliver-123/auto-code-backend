package com.jack.autocodebackend.core.saver;

import com.jack.autocodebackend.ai.model.CodeResult;
import com.jack.autocodebackend.ai.model.HtmlCodeResult;
import com.jack.autocodebackend.ai.model.MultiFileCodeResult;
import com.jack.autocodebackend.exception.BusinessException;
import com.jack.autocodebackend.exception.ErrorCode;

import java.io.File;

/**
 * 根据结果类型选择文件保存策略。
 */
public final class CodeFileSaverExecutor {

    private static final HtmlCodeFileSaverTemplate HTML_CODE_FILE_SAVER = new HtmlCodeFileSaverTemplate();
    private static final MultiFileCodeFileSaverTemplate MULTI_FILE_CODE_FILE_SAVER =
            new MultiFileCodeFileSaverTemplate();

    private CodeFileSaverExecutor() {
    }

    public static File executeSaver(CodeResult codeResult, Long appId) {
        CodeFilePublication publication = executeSaverPublication(codeResult, appId);
        publication.commit();
        return publication.directory();
    }

    public static CodeFilePublication executeSaverPublication(CodeResult codeResult, Long appId) {
        if (codeResult == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "代码结果对象不能为空");
        }
        if (codeResult instanceof HtmlCodeResult htmlCodeResult) {
            return HTML_CODE_FILE_SAVER.publishCode(htmlCodeResult, appId);
        }
        if (codeResult instanceof MultiFileCodeResult multiFileCodeResult) {
            return MULTI_FILE_CODE_FILE_SAVER.publishCode(multiFileCodeResult, appId);
        }
        throw new BusinessException(
                ErrorCode.SYSTEM_ERROR,
                "不支持的代码结果类型: " + codeResult.getClass().getName()
        );
    }
}
