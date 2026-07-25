## Why

项目已有应用表和初始 Mapper 生成物，但缺少与现有用户体系集成的应用管理业务层、接口层和权限边界，无法支持用户创建和维护应用，也无法支持精选展示及管理员治理。现在需要补齐完整的 App 模块，为后续代码生成、部署和应用展示能力提供稳定的数据与访问基础。

## What Changes

- 新增用户创建应用能力，要求提供非空 `initPrompt`，并由服务端绑定当前登录用户。
- 新增用户仅修改、删除自己应用的能力，其中普通用户更新只允许修改应用名称。
- 新增应用详情查询、我的应用分页查询和精选应用分页查询；两个用户侧列表均支持名称查询且每页最多 20 条。
- 新增管理员删除任意应用、更新应用名称/封面/优先级、按非时间业务字段分页查询以及查看任意应用详情的能力。
- 建立应用 DTO、VO、Service、Controller、MyBatis-Plus Mapper 和逻辑删除模型，并统一参数校验、异常响应、鉴权与安全排序规则。
- 调整应用表初始化定义，保证脚本幂等并在数据库层约束 `initPrompt` 必填。
- 新增 App Controller 与 Service 测试，覆盖权限、所有权、字段白名单、分页边界和查询安全。

## Capabilities

### New Capabilities

- `app-management`: 覆盖普通用户和管理员的应用创建、更新、删除、详情与分页查询行为，以及应用所有权、精选规则和分页限制。

### Modified Capabilities

无。

## Impact

- 受影响代码：`controller`、`service`、`service/impl`、`model/domain`、`model/dto`、`model/vo`、`mapper`、应用常量及对应测试。
- 受影响数据：`app` 表的必填约束、逻辑删除映射和初始化脚本幂等性。
- 受影响 API：新增 `/app/**` 用户接口和 `/app/admin/**` 管理员接口。
- 依赖保持不变，继续使用现有 Spring MVC、MyBatis-Plus、Hutool、Lombok 和测试工具链。
