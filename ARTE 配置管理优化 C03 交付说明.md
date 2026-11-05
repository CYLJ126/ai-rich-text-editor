# ARTE 配置管理优化 C03 交付说明

日期：2026-11-05。范围：租户独立读取、工作空间目录的服务端租户过滤、对应前端服务。租户列表、概览和政策页签由 C04 接入。

## C04 接入契约

所有端点均为 `POST /arte/workspace/<端点>`，沿用现有认证和统一响应。前端服务返回 `body.data` 中的类型化数据。

| 端点                         | 请求                                                                                                                     | 成功结果                                                                                                           |
|------------------------------|--------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------|
| `browseAccessibleTenants`    | 可选 `cursor`、`size`（1～100，默认 20）、`search`（最长 100，按名称做不区分大小写的包含匹配）、`type`（0 个人、1 组织） | `records`、`nextCursor`、`exhausted`                                                                               |
| `getTenant`                  | `{tenantId}`                                                                                                             | `id/name/type/state/currentRole/version/isPersonal`，加 `viewLevel` 和 `actions`                                   |
| `getTenantCreationOptions`   | `{}`                                                                                                                     | 当前为 `{available:false, organizationAllowed:false, reason:"CAPABILITY_NOT_ENABLED"}`；C10 再接入真实组织创建资格 |
| `browseAccessibleWorkspaces` | 保留原 `cursor/size/search`，增加可选 `tenantId`                                                                         | 原分页结构；仅返回本人在所选租户下有访问资格的空间                                                                 |

- 租户目录从本人租户成员关联读取，不依赖已加入空间或个人空间偏好。只显示 ACTIVE 租户下的有效正式成员资格：OWNER（0）、ADMIN（1）及
  MEMBER（2）。`isPersonal` 由当前稳定主体与个人托管关联比较得出。GUEST 仍可通过既有空间目录看到已获权的空间。
- 详情中，MEMBER 为 `BASIC`；OWNER、ADMIN 为 `GOVERNANCE`。`actions` 只报告已接通的 `tenant:read`、`tenant:policy:write`、
  `tenant:workspace:create`，其中 MEMBER 创建空间仍取决于租户政策。详情不带政策正文、主体 ID 或授权内部字段。操作提交时仍需重新校验。
- 目录分页无默认总数；即使 `records` 为空，也以 `exhausted`
  判断是否结束。切换搜索词、类型、页大小或空间目录的租户条件时，从无游标的首批开始。游标绑定当前主体和目录，继续浏览会重新核验资格。
- `400 PLATFORM_INVALID_ARGUMENT` 表示参数不合法或游标与请求条件不符；`404 PLATFORM_NOT_FOUND` 统一处理不可见租户；
  `410 PLATFORM_CURSOR_EXPIRED` 提示重新从首批加载；`503 PLATFORM_AUTHORITY_UNAVAILABLE` 表示权威读取不可用，不能当作空目录或永久无权处理。
- 前端租户入口见 [tenant.ts](frontend/src/services/platform/tenant.ts)
  ；在 [workspace.ts](frontend/src/services/platform/workspace.ts) 调用
  `browseTenantWorkspaces(tenantId, cursor, search, options)`。现有 `browseAccessibleWorkspaces` 调用方式不变。请求均可传
  `AbortSignal`。

## 持久化与兼容

租户成员查询按稳定租户 ID 升序推进，使用现有 `ix_platform_tenant_member_subject(subject_id,tenant_id)`；空间按现有排序键扫描，
`tenant_id` 条件在 SQL 的 `LIMIT` 之前生效。两类候选都由核心服务复核。租户游标独立存储；旧的不带租户条件的空间 v3
游标保持兼容。目录和详情不会初始化个人空间、修改偏好或切换当前聊天空间。

现有 `arte.platform.workspace.enabled` 开关覆盖新接口；未增加数据库迁移、Redis 服务或菜单项。本批不变更业务数据库或部署环境。

## 验证结果

- JDK 21 后端回归：核心 89 项通过，应用 82 项通过、5 项按条件跳过（4 项 MySQL 迁移用例和 1 项性能压测）。使用隔离 H2 与临时
  Redis 7.4；验证了服务装配、权限和失权、空页续扫、空间租户过滤、目录游标隔离及旧空间游标兼容。
- 隔离 MySQL 8.4：工作空间及租户 HTTP 套件 21 项通过。对每张成员表 6,000 条合成候选做 `EXPLAIN`：租户续页使用
  `ix_platform_tenant_member_subject` 做覆盖范围读取；筛选空间使用 `ix_platform_workspace_member_subject`
  先定位主体与租户，再对该范围内的候选排序。本批无需增加索引；该证据仅适用于本次合成数据，不代表大租户生产负载。
- 前端配置服务和工作空间回归 151 项通过。扩大到聊天模块时，`ChatWorkspaceLayout.test.tsx` 有 3 个尺寸断言失败，在独立的
  HEAD 基线上也复现。全局 TypeScript 检查仍有 107 条既有诊断，C03 文件没有新增诊断。
- 额外运行的旧 MySQL 空间时间迁移套件中有 3 项失败，失败点是该套件的 JDBC 脚本执行器无法处理 MySQL 客户端 `DELIMITER`
  指令。C03 未改动此迁移脚本，也不依赖这次迁移。
