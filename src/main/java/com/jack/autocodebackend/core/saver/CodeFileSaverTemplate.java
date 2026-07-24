package com.jack.autocodebackend.core.saver;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.jack.autocodebackend.ai.model.CodeResult;
import com.jack.autocodebackend.ai.model.enums.CodeGenTypeEnum;
import com.jack.autocodebackend.exception.BusinessException;
import com.jack.autocodebackend.exception.ErrorCode;

import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * 抽象代码文件保存器，定义校验、建目录和写文件的标准流程。
 */
public abstract class CodeFileSaverTemplate<T extends CodeResult> {

    protected static final String FILE_SAVE_ROOT_DIR =
            System.getProperty("user.dir") + File.separator + "tmp" + File.separator + "code_output";

    public final File saveCode(T result) {
        validateInput(result);
        String baseDirPath = buildUniqueDir();
        try {
            saveFiles(result, baseDirPath);
            return new File(baseDirPath);
        } catch (RuntimeException exception) {
            try {
                FileUtil.del(baseDirPath);
            } catch (RuntimeException cleanupException) {
                exception.addSuppressed(cleanupException);
            }
            throw exception;
        }
    }

    protected void validateInput(T result) {
        if (result == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "代码结果对象不能为空");
        }
    }

    protected final void writeToFile(String dirPath, String filename, String content) {
        if (StrUtil.isBlank(content)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, filename + " 文件内容不能为空");
        }
        String filePath = dirPath + File.separator + filename;
        FileUtil.writeString(content, filePath, StandardCharsets.UTF_8);
    }

    protected abstract CodeGenTypeEnum getCodeType();

    protected abstract void saveFiles(T result, String baseDirPath);

    private String buildUniqueDir() {
        String uniqueDirName = StrUtil.format(
                "{}_{}",
                getCodeType().getValue(),
                IdUtil.getSnowflakeNextIdStr()
        );
        String dirPath = FILE_SAVE_ROOT_DIR + File.separator + uniqueDirName;
        FileUtil.mkdir(dirPath);
        return dirPath;
    }
}
