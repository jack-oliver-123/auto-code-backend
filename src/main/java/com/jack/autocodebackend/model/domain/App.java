package com.jack.autocodebackend.model.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 应用
 * @TableName app
 */
@TableName(value = "app")
@Data
public class App {

    /**
     * id
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 应用名称
     */
    private String appName;

    /**
     * 应用封面
     */
    private String cover;

    /**
     * 应用初始化的 prompt
     */
    private String initPrompt;

    /**
     * 代码生成类型（枚举）
     */
    private String codeGenType;

    /**
     * Lifecycle of the latest generation attempt.
     */
    private String generationStatus;

    /**
     * Opaque identity used to guard terminal attempt updates.
     */
    private String generationAttemptId;

    /**
     * Bounded application-owned failure category.
     */
    private String generationFailureCode;

    /**
     * Safe failure information for the owner and administrators.
     */
    private String generationFailureMessage;

    private Date generationStartedTime;

    private Date generationFinishedTime;

    /**
     * 部署标识
     */
    private String deployKey;

    /**
     * 部署时间
     */
    private Date deployedTime;

    /**
     * 优先级
     */
    private Integer priority;

    /**
     * 创建用户 id
     */
    private Long userId;

    /**
     * 编辑时间
     */
    private Date editTime;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;

    /**
     * 是否删除
     */
    @TableLogic
    private Integer isDelete;
}
