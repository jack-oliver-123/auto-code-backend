## Context

仓库已经具备 User 模块、基于 Session 的登录态、`@AuthCheck` AOP、统一异常响应和 MyBatis-Plus 分页能力。当前 App 仅有未完成的生成器产物：实体属性使用全小写名称，Mapper 未继承 `BaseMapper`，XML 执行物理删除，且没有 Service、Controller、DTO、VO 或测试。`app` 表包含应用管理所需字段，但 `initPrompt` 仍可为空；全局分页拦截器还将每页数量限制为 100，与管理员列表的无业务上限要求冲突。

本设计复用项目中已经稳定的基础设施，但不机械复制 User 模块中重复接口、宽请求对象或 Controller 承担业务规则等做法。App 特有的所有权、精选规则和数据暴露边界由专门的服务与模型表达。

## Goals / Non-Goals

**Goals:**

- 提供规格中定义的 6 个用户侧接口和 4 个管理员接口。
- 在服务端强制应用所有权、更新字段白名单、精选规则和分页边界。
- 将 App 持久化层统一为可使用通用 CRUD、分页和逻辑删除的 MyBatis-Plus 模型。
- 让列表载荷保持精简，同时让详情接口返回完整业务信息。
- 通过 Controller slice 测试和 Service 单元测试覆盖权限、所有权、校验与查询安全。

**Non-Goals:**

- 不实现 AI 代码生成、代码类型自动路由、部署或封面自动生成。
- 不允许普通用户修改 `initPrompt`、`cover`、`priority`、`codeGenType`、`deployKey` 或所有者。
- 不提供已逻辑删除应用的恢复、审计或管理员查询能力。
- 不引入缓存、全文检索、外键、数据库测试容器或新的外部依赖。

## Decisions

### 1. 使用 MyBatis-Plus 持久化模型，但让 App ID 匹配数据库自增策略

`App` 将使用 camelCase Java 属性、`@TableName("app")`、`@TableId(type = IdType.AUTO)` 和 `@TableLogic Integer isDelete`。`AppMapper` 继承 `BaseMapper<App>`；XML 仅保留与 User Mapper 相同用途的 `BaseResultMap` 和列清单，不再保留手写 CRUD。

选择 `IdType.AUTO` 是因为现有 `app.id` 明确定义为 `auto_increment`，当前生成器 Mapper 也使用 generated keys。这里不复制 User 的 `ASSIGN_ID`，避免同时存在两个实际 ID 生成方。时间字段继续使用本项目实体通用的 `Date` 类型。

备选方案是保留传统 MyBatis 生成器方法；该方案无法自然复用 `IService`、分页与逻辑删除，并会产生两套 CRUD 风格，因此不采用。

### 2. 使用窄 DTO 表达不同信任边界

新增以下请求模型：

- `AppAddDTO`：仅包含 `initPrompt`。
- `AppUpdateDTO`：仅包含 `id` 和 `appName`。
- `AppAdminUpdateDTO`：仅包含 `id`、`appName`、`cover` 和 `priority`。
- `AppNameQueryDTO`：继承 `PageRequest`，仅增加 `appName`，供我的应用和精选应用使用。
- `AppQueryDTO`：继承 `PageRequest`，包含管理员可过滤的非时间业务字段。

用户侧列表不复用管理员查询 DTO，从类型层面避免客户端提交 `userId` 或 `priority` 改写服务端强制条件。所有 DTO 延续项目的 Lombok `@Data`、`Serializable` 和手工 `ThrowUtils` 校验风格。

备选方案是所有列表共用一个查询 DTO并在 Controller 覆盖字段；虽然可以工作，但扩大了公开 API 输入面，容易在后续维护中遗漏强制条件，因此不采用。

### 3. Controller 处理传输边界，Service 处理应用业务规则

Controller 负责请求体/id/分页形状校验、声明鉴权、获取当前用户和封装 `BaseResponse`。`AppService` 继承 `IService<App>`，并负责：

- 创建时校验和规范化 Prompt、绑定当前用户、生成初始名称并设置默认优先级。
- 查询目标、校验所有权以及执行用户更新和删除。
- 管理员更新字段校验与写入。
- 用户、精选和管理员 QueryWrapper 构造及安全排序。
- Domain 到摘要/详情 VO 的转换和分页 records 转换。

所有权判断集中在 Service，客户端传入的数据永远不能决定 owner。写入时使用带 `id` 与 `userId` 条件的更新/删除 Wrapper 作为最终保护；由于所有者在全部更新 DTO 中都是不可变字段，预查询后的权限判断不会被合法接口改变。

### 4. 登录写接口使用 AOP，公开读取接口不创建 Session

`/add`、`/update`、`/delete` 和 `/my/list/page/vo` 使用无角色参数的 `@AuthCheck`，从而复用未登录与“必须先修改初始密码”的拦截行为。管理员接口使用 `@AuthCheck(mustRole = UserConstant.ADMIN_ROLE)`。`/get/vo` 和 `/good/list/page/vo` 不加鉴权，允许匿名读取且不创建 Session。

需要当前用户的 Controller 在 AOP 之后调用 `userService.getLoginUser(request)` 并传入 Service。当前基础设施会因此再次读取一次当前用户；本变更接受这项小额开销，以避免扩大范围修改认证上下文传递方式。

备选方案是普通接口只手工调用 `getLoginUser`；这会绕过 AOP 对临时密码用户的限制，因此不采用。

### 5. 精选状态由固定优先级表达

新增 `AppConstant.DEFAULT_APP_PRIORITY = 0` 和 `GOOD_APP_PRIORITY = 99`。创建应用固定使用默认值，精选列表固定追加 `priority = 99`；客户端不能覆盖该条件。管理员通过更新 priority 在普通与精选状态之间切换应用。

备选方案是以任意正 priority 表示精选；这会让排序权重与布尔精选状态混在一起，且不符合本项目既有的 99 约定，因此不采用。

### 6. 列表和详情使用不同 VO

`AppVO` 作为列表摘要，包含列表展示和跳转所需字段，但不包含完整 `initPrompt` 或内部 `isDelete`。`AppDetailVO` 包含公开的完整应用业务字段（包括 `initPrompt`），仍不暴露 `isDelete` 和内部编辑时间。用户列表、精选列表和管理员列表均返回 `Page<AppVO>`；两个详情接口返回 `AppDetailVO`。

本变更只返回 `userId`，不额外关联 `UserVO`。需求没有要求创建者资料，避免为分页列表引入用户批量查询和额外耦合；后续展示需求可独立扩展。

备选方案是所有接口共用一个大 VO；这会让列表重复传输可能很长的 Prompt，因此不采用。

### 7. 查询条件和排序均采用白名单

用户侧列表只接受名称 contains 查询，并由服务端分别追加 owner 或 `priority = 99`。管理员查询规则为：

- `id`、`codeGenType`、`deployKey`、`priority`、`userId` 使用精确匹配。
- `appName`、`cover`、`initPrompt` 使用 contains 匹配。
- 时间字段与内部 `isDelete` 不作为过滤条件。

动态排序字段先映射到固定数据库列白名单，建议包含 `id`、`appName`、`codeGenType`、`priority`、`userId`、`createTime` 和 `updateTime`。未知排序字段被忽略；没有有效排序时默认按 `createTime DESC`、`id DESC`，保证分页稳定。

### 8. 管理员分页仅覆盖当前 Page 的上限

所有分页端点都要求 `pageNum` 和 `pageSize` 为正。用户和精选列表额外限制 `pageSize <= 20`。管理员列表构造 `Page<App>` 后调用 `setMaxLimit(Long.MAX_VALUE)`，使该 Page 覆盖全局 100 条限制，同时保留 User 等其他模块的全局保护。

备选方案是移除 `MyBatisPlusConfig` 的全局限制；这会扩大到所有分页接口，与变更范围不符，因此不采用。

### 9. 数据库初始化与业务校验双重保证 Prompt 必填

`sql/init.sql` 中的应用表改为 `create table if not exists app`，并将 `initPrompt` 定义为 `text not null`。Service 仍使用 `StrUtil.isBlank` 拒绝空白 Prompt，数据库约束只作为最终一致性保护。创建时存储去除首尾空白后的 Prompt，并以其前 12 个字符作为初始名称。

`codeGenType` 在本变更中保持可空；自动选择代码生成类型属于后续代码生成流程，而不是应用 CRUD 的职责。

## Risks / Trade-offs

- [管理员可请求极大页面，可能造成数据库和内存压力] → 仅管理员可访问，保持正数校验，并通过测试确保这是显式行为；生产环境应结合网关、权限审计和运维指标监控。
- [公开详情包含完整 `initPrompt`，可能暴露用户输入] → 这是当前详情契约的明确选择；列表不返回 Prompt。若未来 Prompt 被定义为私密信息，需要修改规格并增加所有权/管理员读取限制。
- [逻辑删除后唯一 `deployKey` 仍占用唯一索引] → 本变更不负责重新部署或复用 deploy key；后续部署能力必须处理键生成冲突。
- [初始化脚本的 `IF NOT EXISTS` 不会迁移已有表] → 对已有数据库使用显式迁移步骤，不依赖重新执行 CREATE 修改列约束。
- [Controller 在 AOP 后再次读取当前用户] → 接受当前实现的额外一次主键查询；未来可通过请求属性缓存认证主体统一优化。
- [Service 单元测试无法证明真实数据库分页和逻辑删除 SQL] → 使用 QueryWrapper SQL 片段、Mapper 交互和 Page 参数测试覆盖本变更；数据库集成测试作为后续基础设施工作。

## Migration Plan

1. 在已有环境中检查并清理 `app.initPrompt IS NULL` 的记录。
2. 对已有表执行等价迁移，将 `initPrompt` 修改为 `TEXT NOT NULL`；新环境直接使用更新后的 `init.sql`。
3. 部署新的 App 实体、Mapper、Service、Controller 和 DTO/VO。
4. 执行 App 专项测试及完整 Maven 测试，重点验证逻辑删除和管理员 `pageSize > 100` 参数未被改写。
5. 回滚时先回滚应用代码；如必须回滚数据库约束，可将 `initPrompt` 恢复为可空。逻辑删除数据无需物理恢复。

## Open Questions

无。当前产品口径已经固定；新增代码生成、部署、已删除数据治理或 Prompt 私密性要求时，应另建变更。
