package com.jack.autocodebackend.core.lock;

import com.jack.autocodebackend.exception.BusinessException;
import com.jack.autocodebackend.exception.ErrorCode;

public class AppProcessingLeaseLostException extends BusinessException {

    public AppProcessingLeaseLostException() {
        super(ErrorCode.OPERATION_ERROR, "应用处理租约已失效");
    }
}
