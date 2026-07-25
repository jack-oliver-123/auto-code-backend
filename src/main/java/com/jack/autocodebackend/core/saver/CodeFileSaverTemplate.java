package com.jack.autocodebackend.core.saver;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.jack.autocodebackend.ai.model.CodeResult;
import com.jack.autocodebackend.ai.model.enums.CodeGenTypeEnum;
import com.jack.autocodebackend.constant.AppConstant;
import com.jack.autocodebackend.core.deploy.DirectoryPublisher;
import com.jack.autocodebackend.exception.BusinessException;
import com.jack.autocodebackend.exception.ErrorCode;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 抽象代码文件保存器，定义校验、建目录和写文件的标准流程。
 */
public abstract class CodeFileSaverTemplate<T extends CodeResult> {

    private static final Object PUBLISH_LOCK = new Object();
    private static final DirectoryPublisher DIRECTORY_PUBLISHER = new DirectoryPublisher();
    // 文件保存根目录
    protected static final String FILE_SAVE_ROOT_DIR = AppConstant.CODE_OUTPUT_ROOT_DIR;

    public final File saveCode(T result, Long appId) {
        CodeFilePublication publication = publishCode(result, appId);
        publication.commit();
        return publication.directory();
    }

    public final CodeFilePublication publishCode(T result, Long appId) {
        validateInput(result);
        String dirName = buildDirName(appId);
        Path outputRoot = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR).toAbsolutePath().normalize();
        Path targetDir = outputRoot.resolve(dirName);
        Path stagingDir = null;
        try {
            Files.createDirectories(outputRoot);
            stagingDir = Files.createTempDirectory(outputRoot, "." + dirName + ".staging-");
            saveFiles(result, stagingDir.toString());
            DirectoryPublisher.PublishedDirectory publication;
            synchronized (PUBLISH_LOCK) {
                publication = DIRECTORY_PUBLISHER.publishReplacement(stagingDir, targetDir);
            }
            return new CodeFilePublication(targetDir.toFile(), publication);
        } catch (IOException exception) {
            BusinessException saveException = new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "代码目录发布失败"
            );
            saveException.initCause(exception);
            cleanupStaging(stagingDir, saveException);
            throw saveException;
        } catch (RuntimeException exception) {
            cleanupStaging(stagingDir, exception);
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

    private String buildDirName(Long appId) {
        if (appId == null || appId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 ID 必须为正数");
        }
        return StrUtil.format(
                "{}_{}",
                getCodeType().getValue(),
                appId
        );
    }

    private void cleanupStaging(Path stagingDir, RuntimeException saveException) {
        if (stagingDir == null || Files.notExists(stagingDir)) {
            return;
        }
        try {
            if (!FileUtil.del(stagingDir.toFile())) {
                saveException.addSuppressed(new IOException("staging 目录清理失败: " + stagingDir));
            }
        } catch (RuntimeException cleanupException) {
            saveException.addSuppressed(cleanupException);
        }
    }

}
