package com.jack.autocodebackend.constant;

import java.io.File;

public interface AppConstant {

    /**
     * 应用生成代码的输出根目录
     */
    String CODE_OUTPUT_ROOT_DIR = System.getProperty("user.dir")
            + File.separator + "tmp" + File.separator + "code_output";

    /**
     * 默认应用优先级
     */
    int DEFAULT_APP_PRIORITY = 0;

    /**
     * 精选应用优先级
     */
    int GOOD_APP_PRIORITY = 99;

}
