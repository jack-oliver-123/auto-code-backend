package com.jack.autocodebackend.ai.model.enums;

import cn.hutool.core.util.ObjUtil;
import lombok.Getter;

import java.util.List;

@Getter
public enum CodeGenTypeEnum {

    HTML("原生 HTML 模式", "html", "", List.of("index.html")),
    MULTI_FILE("原生多文件模式", "multi_file", "",
            List.of("index.html", "style.css", "script.js")),
    VUE_PROJECT("Vue 3 工程模式", "vue_project", "dist", List.of("index.html"));

    private final String text;
    private final String value;

    private final String staticRootDirectory;

    private final List<String> requiredStaticFiles;

    CodeGenTypeEnum(
            String text,
            String value,
            String staticRootDirectory,
            List<String> requiredStaticFiles
    ) {
        this.text = text;
        this.value = value;
        this.staticRootDirectory = staticRootDirectory;
        this.requiredStaticFiles = List.copyOf(requiredStaticFiles);
    }

    /**
     * 根据 value 获取枚举
     *
     * @param value 枚举值的value
     * @return 枚举值
     */
    public static CodeGenTypeEnum getEnumByValue(String value) {
        if (ObjUtil.isEmpty(value)) {
            return null;
        }
        for (CodeGenTypeEnum anEnum : CodeGenTypeEnum.values()) {
            if (anEnum.value.equals(value)) {
                return anEnum;
            }
        }
        return null;
    }
}
