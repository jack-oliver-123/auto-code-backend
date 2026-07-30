package com.jack.autocodebackend.core.saver;

import com.jack.autocodebackend.ai.model.CodeResult;
import com.jack.autocodebackend.ai.model.enums.CodeGenTypeEnum;

public interface CodeResultSaver {

    CodeGenTypeEnum codeGenType();

    Class<? extends CodeResult> resultType();

    CodeFilePublication publish(CodeResult result, Long appId);
}
