# AI TOOL S3-03：当前工作空间信息写工具

更新：2026-10-20。完成一个真实业务写工具 `workspace.updateDetails@v1`，复用现有 `WorkspaceService.rename`
、公共当前授权、版本、事务、审计、Outbox 和命令回执。没有新增身份／空间系统、业务写接口或预算账户。本批不实现 S3-04
自动业务结果核对、S3-05 人工证据与核对界面；业务写工具默认关闭，核对闭环验收前仅供隔离开发验收，不宣称生产开放。

## 用户能做什么

修改本次选择的当前工作空间名称及说明。工具不接收另一个目标 tenantId／workspaceId，不修改负责人、成员、权限、归属、AI
配置或文章正文。说明为空字符串表示清空，而非保留旧值；预览和决定页面展示完整参数、目标 Scope、人工模式及风险。

参数全部必填，示例：

```json
{
  "expectedVersion": "0",
  "name": "产品研发空间",
  "description": "用于产品设计与研发协作"
}
```

`expectedVersion` 从现有 `workspace/getWorkspace` 的当前版本读取，使用非负十进制字符串，不含前导零、不超过 Java Long。不得先转成
JS Number。名称须为 1～100 个 UTF-16 单元且没有首尾空白；说明最多 1000 个 UTF-16 单元。Schema
沿用当前支持的子集，十进制版本及规范化一致性由可信宿主再次校验，不引入未支持的 pattern 关键字。额外目标字段、空白名称、溢出或非规范版本明确拒绝。

输出为 workspaceId、已保存 name、字符串 version 和领域回执 operationId。正常成功的副作用是
CONFIRMED；批准决定只代表排队，不能当成工具成功。版本冲突明确说明本次未修改，用户读取当前版本后重新预览；UNKNOWN
继续显示待核对，不能盲目重发。

## 授权、确认与版本

执行资格是当前 `aiTool:invoke`、空间 AI 使用资格、受信工具完整契约及当前领域 `WORKSPACE_UPDATE` 的交集。空间成员的普通 AI
调用权不自动获得改名资格；管理员菜单标记不绕过领域授权。预览、受理、原键重放、本人确认和 Worker
边界分别复核相关当前资格，独立审批者仍按既有规则决定，不能替换原执行主体或授予其资源权限。

默认最低人工模式 REQUESTER_CONFIRMATION，绑定只能收紧到 INDEPENDENT_APPROVAL，不能降为 NONE。总契约上限 16 分钟、最多 1 个
Attempt、输入上限 16384 字节、输出上限 4096 字节。现有服务器／请求上限继续求交；普通请求可使用 120 秒总期限，实际本人窗口约 90
秒，执行至少留 30 秒。独立审批仍需原总期限完整容纳 15 分钟加 30 秒，服务器上限不足时明确拒绝。

现有文本工具 v1 保持原 30 秒契约和只读行为。写工具的较长期限只属于新的独立契约，不能借此延长文本工具或旧 Invocation。

## 提交事务与领域防重

1. 按原 S3 流程固定快照、参数、审批人及绝对 deadline，等待期间没有 Attempt、执行槽或预算预留。
2. 获批后进入原分类队列，Worker 原子准备 FREE 派发并耐久标记发送事实；原发起者身份、scopes 和 deadline 不变。
3. 网关复核固定材料、当前资格、输入、批准及实际 Attempt；只有显式宿主写授权才能使用 RESOURCE_WRITE，旧只读 SPI 实现默认拒绝写入。
4. 适配器在同一个 appDataSource 物理写事务内先锁 Invocation／approval／Attempt，核验 RUNNING、活动
   Attempt、worker、fence、发送身份、活跃租约、原 deadline 与耐久取消标记。随后按既有治理顺序获取主体／空间锁，重新验证当前 AI 与
   WORKSPACE_UPDATE，再持有当前目录／绑定治理锁直到提交。
5. 领域操作键由 `CanonicalJson.key("tool-workspace-details-v1", invocationId)` 派生，不使用 Attempt
   ID、审批者或新随机键。现有领域命令身份还包含原主体、Scope 和命令类型 `workspace:rename`；名称、说明、expectedVersion
   参与领域请求摘要。
6. `WorkspaceService.rename` 在同一事务内校验 expectedVersion、更新空间、保存领域防重回执、审计及公共
   Outbox。同键同意图返回原领域回执，旧版本不会覆盖并发修改。固定操作重放仍要求当前写资格。
7. 成功 ToolResult
   的完整编码字节在领域提交前检查，再复核租约／取消／期限；失败使领域变更及回执一起回滚。极小结果限额连安全失败信封也放不下时，沿用通用保守结果处理，不承诺返回成功信封；业务不会先提交再因本次容量不足而留下写入。
8. 领域提交后，工具结果、Invocation 终态和事件按既有执行完成事务持久化。两者不假装成一个长跨域事务：领域已提交但工具回执丢失时保留
   UNKNOWN，不自动创建第二次尝试或更换操作键。S3-04 可按原 Invocation 派生的领域命令身份查询已有回执，查询不等于重新调用
   rename。

领域明确版本冲突／权限拒绝返回已知失败、NONE；结果提交故障、未知异常和不明回执保守为
UNKNOWN。没有证据时不宣称“取消即业务未生效”，也不通过新键绕过未知结果。

## 开发环境启用与发布

本批没有修改业务配置、运行 SQL、改名任何真实工作空间、发布目录、创建绑定或重启应用。已有 S3-01／02 审批表及 audience
必须先正确安装；旧同名审批表不能复用，见 [审批部署说明](TOOL_APPROVAL.md#先区分旧版同名审批表与-s3-01-审批表)。本批复用现有领域表和
TOOL 快照表，不新增 DDL／回填或 RBAC 操作定义。

仅在统一升级所有共享库节点／消费者后，为隔离开发验收显式设置：

```properties
arte.ai-new-tool-write.enabled=true
arte.ai-new-tool-approval.enabled=true
# 继续使用已有 AI／TOOL／执行、分类调度与 Worker 开关。
# 以下为本代码“文本计数 v1 + 空间信息更新 v1”的完整受信清单。
arte.ai-new-execution.scheduling.tool-manifest=80247d79a981503ef33677823d7a0f89983e667850e93ceb36b0a43d31985136
```

write 开关默认 false，关闭时不登记写工具适配器／来源，原文本工具清单摘要保持
`a6bb1c11e6f08fd0088d121a7cf6e5a6c7e755c1fc03cad73d252a5dacfea92a`
。开启／关闭写清单会改变同构池摘要，须按既有停写／排空／统一节点流程切换，不能让不同清单混用同一池；以当前成功完整发现的
nodeChecksum 为准，契约变更须重算而非照抄旧值。开关或发布不自动授予权限。

使用原管理接口完整刷新本地目录、审核并发布 `workspace.updateDetails@v1`。发布材料必须明确资源风险、领域操作键证据及最低本人确认；示例输入／输出仅验证
Schema，不执行工作空间变更。随后 `prepareDefaultBinding` 使用空配置 `{}`，原 previewInvocation／invokeTool 使用上述参数、120
秒请求期限及预览摘要；本人在 WAITING_APPROVAL 再核对并决定，之后查询原调用。

新领域写快照编码为 **tool-execution-snapshot v3**；只读无审批 v1、只读人工模式 v2 保持兼容，禁止仅改 schemaVersion 降级。已写入
v3 后不能直接回滚到不支持该形状的节点，也不能用关闭入口清理已有事实。已有调用继续按当前状态取消／恢复；UNKNOWN 等待后续核对，不重新执行。

## 验证范围

正式宿主 MVC、MyBatis／JDBC、审批、Worker 和真实领域命令在隔离 H2
中验证：成功与同键重放、并发版本冲突、重复进入适配器的领域防重、领域回执及变更回滚、领域执行后租约失效、提交后丢失结果保持
UNKNOWN、撤权、伪造直接适配调用、参数越界与结果容量不足。未在业务数据库执行，不代替 MySQL、多节点和生产性能验收。

完整结果见 [实施记录 §29](TOOL_IMPLEMENTATION.md)。S3-04／S3-05 与生产写工具开放仍待后续验收。
