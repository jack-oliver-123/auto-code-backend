package com.jack.autocodebackend.ai.model;


import dev.langchain4j.model.output.structured.Description;
import lombok.Data;

@Description("生成 HTML 代码文件的结果")
@Data
public class HtmlCodeResult implements CodeResult {

    @Description("可直接保存为 index.html 的完整 HTML 源码，不包含 Markdown 代码块标记")
    private String htmlCode;

    @Description("生成结果的简短描述")
    private String description;
}
