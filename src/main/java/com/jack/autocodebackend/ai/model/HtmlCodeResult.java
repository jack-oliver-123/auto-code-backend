package com.jack.autocodebackend.ai.model;


import dev.langchain4j.model.output.structured.Description;
import lombok.Data;

@Description("生成 HTML 代码文件的结果")
@Data
public class HtmlCodeResult implements CodeResult {

    @Description("HTML 代码")
    private String htmlCode;

    @Description("生成代码描述")
    private String description;
}
