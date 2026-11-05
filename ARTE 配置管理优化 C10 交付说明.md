# ARTE 配置管理优化 C10 交付说明

2026-11-05。C10 已完成，下一批为 C11-A 租户成员治理。

- 概览按服务端 `tenant:update` 资格开放名称编辑。最终提交同时核验当前租户负责人或管理员角色、显式 RBAC
  操作和租户版本。名称变更推进记录版本，与审计、事件和回执同事务保存。已打开的租户政策草稿保留，继续保存前需重新核对版本。个人租户允许有资格的名称编辑，托管主体及系统默认空间关系保持固定。
- 组织创建要求部署显式开启、有效的组织初始政策模板及独立 RBAC
  操作。创建在同一事务中写入组织、首位负责人、初始政策、审计、事件和回执。创建事件记录组织模板版本。创建成功后租户配置页定位到该组织的概览，工作空间目录初始为空。
- `POST /arte/workspace/createOrganization` 接收名称，`POST /arte/workspace/updateTenant` 接收租户 ID、预期租户版本及名称。两个写入口都使用
  `Idempotency-Key`。原操作未确认时保留请求与操作键，刷新后由用户显式确认。已确认操作可通过
  `POST /arte/workspace/getWorkspaceCommandReceipt` 按操作 ID 查询，服务端复核原操作者及当前结果可见性和对应操作资格。
-
数据库沿用已有租户、成员、政策、审计及回执表。先安装工作环境菜单，再执行 [租户治理 RBAC 操作登记脚本](backend/arte-app/scripts/arte-tenant-governance-rbac-dml-mysql.sql)
。脚本只登记操作，管理员按业务需要另行授予 `EnvironmentTenants:update` 或 `EnvironmentTenants:create`。租户角色或通用
`admin` 操作均不足以取得这些资格。

组织创建的部署配置示例。将规则按业务要求定稿，每次发布新的组织模板内容时提升版本。

```yaml
arte:
  platform:
    tenant-governance:
      organization-creation-enabled: true
      organization-policy-template: >-
        {"version":1,"policySchemaVersion":1,"rolePresetVersion":1,
        "membersMayCreateWorkspace":false,
        "rules":{"allowedCreationTypes":["article"],"extraCreationRoles":{},
        "guestAiAllowed":false,"personalCredentialsAllowed":false,
        "allowedProtocols":[],"allowedProviders":[],"allowedDestinations":[],
        "exportTypes":[],"egressTypes":[],"shareTargetKinds":[]}}
```

模板未安装、无法解析或采用不支持的结构版本时，组织创建入口关闭。个人初始化继续使用独立的个人政策模板。

70 项后端回归、两项隔离 MySQL 8.4 专项测试、276 项相关前端测试及生产构建通过。Chrome 窄屏夹具走通创建空组织、名称更新及回执查询。TypeScript
保留 C09 的 107 条既有诊断。
