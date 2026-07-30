create database if not exists auto_code;

use auto_code;

-- 用户表
create table if not exists user
(
    id           bigint auto_increment comment 'id' primary key,
    userAccount  varchar(256)                           not null comment '账号',
    userPassword varchar(512)                           not null comment '密码',
    userName     varchar(256)                           null comment '用户昵称',
    userAvatar   varchar(1024)                          null comment '用户头像',
    userProfile  varchar(512)                           null comment '用户简介',
    userRole     varchar(256) default 'user'            not null comment '用户角色：user/admin',
    editTime     datetime     default CURRENT_TIMESTAMP not null comment '编辑时间',
    createTime   datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime   datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete     tinyint      default 0                 not null comment '是否删除',
    UNIQUE KEY uk_userAccount (userAccount),
    INDEX idx_userName (userName)
) comment '用户' collate = utf8mb4_unicode_ci;

-- 应用表
create table if not exists app
(
    id           bigint auto_increment comment 'id' primary key,
    appName      varchar(256)                       null comment '应用名称',
    cover        varchar(512)                       null comment '应用封面',
    initPrompt   text                               not null comment '应用初始化的 prompt',
    codeGenType  varchar(64)                        null comment '代码生成类型（枚举）',
    generationStatus         varchar(32)  default 'PENDING' not null comment '最新生成状态',
    generationAttemptId      varchar(64)                    null comment '最新生成尝试标识',
    generationFailureCode    varchar(64)                    null comment '安全的生成失败分类',
    generationFailureMessage varchar(256)                   null comment '安全的生成失败信息',
    generationStartedTime    datetime(3)                    null comment '最新生成开始时间',
    generationFinishedTime   datetime(3)                    null comment '最新生成结束时间',
    deployKey    varchar(64)                        null comment '部署标识',
    deployedTime datetime                           null comment '部署时间',
    priority     int      default 0                 not null comment '优先级',
    userId       bigint                             not null comment '创建用户id',
    editTime     datetime default CURRENT_TIMESTAMP not null comment '编辑时间',
    createTime   datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime   datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete     tinyint  default 0                 not null comment '是否删除',
    UNIQUE KEY uk_deployKey (deployKey), -- 确保部署标识唯一
    INDEX idx_appName (appName),         -- 提升基于应用名称的查询性能
    INDEX idx_userId (userId),           -- 提升基于用户 ID 的查询性能
    INDEX idx_generationStatus_startedTime_id
        (generationStatus, generationStartedTime, id)
) comment '应用' collate = utf8mb4_unicode_ci;

-- 已有数据库迁移步骤（在部署依赖生命周期字段的新版本前执行一次）：
-- ALTER TABLE app
--     ADD COLUMN generationStatus varchar(32) DEFAULT 'PENDING' NULL AFTER codeGenType,
--     ADD COLUMN generationAttemptId varchar(64) NULL AFTER generationStatus,
--     ADD COLUMN generationFailureCode varchar(64) NULL AFTER generationAttemptId,
--     ADD COLUMN generationFailureMessage varchar(256) NULL AFTER generationFailureCode,
--     ADD COLUMN generationStartedTime datetime(3) NULL AFTER generationFailureMessage,
--     ADD COLUMN generationFinishedTime datetime(3) NULL AFTER generationStartedTime;
-- UPDATE app
-- SET generationStatus = CASE
--     WHEN codeGenType IS NOT NULL THEN 'SUCCEEDED'
--     ELSE 'PENDING'
-- END
-- WHERE generationStatus IS NULL;
-- ALTER TABLE app
--     MODIFY COLUMN generationStatus varchar(32) DEFAULT 'PENDING' NOT NULL;
-- CREATE INDEX idx_generationStatus_startedTime_id
--     ON app (generationStatus, generationStartedTime, id);


-- 对话历史表
create table if not exists chat_history
(
    id          bigint auto_increment comment 'id' primary key,
    message     mediumtext                         not null comment '消息',
    messageType varchar(32)                        not null comment 'user/ai',
    appId       bigint                             not null comment '应用id',
    userId      bigint                             not null comment '创建用户id',
    createTime  datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime  datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete    tinyint  default 0                 not null comment '是否删除',
    INDEX idx_appId_isDelete_id (appId, isDelete, id),
    INDEX idx_isDelete_createTime_id (isDelete, createTime, id)
) comment '对话历史' collate = utf8mb4_unicode_ci;

-- 若旧版 chat_history 草案已执行，需在部署新版后端前执行等价迁移：
-- ALTER TABLE chat_history MODIFY COLUMN message MEDIUMTEXT NOT NULL;
-- 并将旧索引替换为 idx_appId_isDelete_id 与 idx_isDelete_createTime_id。
