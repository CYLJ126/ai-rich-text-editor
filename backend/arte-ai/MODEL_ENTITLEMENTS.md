# AI 模型使用资格端口：第 2 批

日期：2026-10-30。对应顶层设计详细设计第 5.2～5.3 节，在第 1 批发布目录／预算定义端口基础上，将模型资格的发现、绑定解析、能力及连接使用解析收敛到同一套规则。保持
FIXED／DATABASE 模式、现有 HTTP 和持久化格式，不执行数据迁移。

## 契约

`spi.control.AiResourceEntitlementService` 提供有界 `discover(kind, limit, context)` 和精确引用
`resolve(binding, capability, action, context)`；当前支持 `model:use`，不授予管理或凭据读取资格。发现最多 256
项，同一绑定的不同固定版本分别保留；未知、无使用权或未选入当前来源的绑定不能借其他版本或旧来源取得资格。

`pojo.control.AiEntitlement` 是本次可信读取中的业务视图，包含原执行归属、精确绑定、对应连接、发布标识、资格依据、来源和允许动作。执行归属不表示模型资源所有者；资格依据目前为既有
Grant／空间 AI 配置版本，不伪造公共模型资源 ID 或完整 ACL 版本。该对象不进入 HTTP、执行快照、账本或幂等摘要，不持久化凭据明文。

预算资格由 `BudgetDefinitionProvider` 独立提供，实际网关／协议支持继续由 `ModelGateway`
判断。模型使用资格、兼容预算、网关支持共同决定聊天可执行选项；模型资格本身不表示已预留资金、已经外发或已通过全部执行政策。

## 来源与共用规则

- FIXED：`FixedAiResourceEntitlementService` 保留当前身份解析器复核，以及原固定 Grant 的绑定 ID 授权语义。
- DATABASE：`SourceAiResourceEntitlementService` 通过 `AiControlConfigurationSource`
  获取已核验公共成员、空间策略及当前配置的快照。公共空间的绑定列表仍按精确引用过滤；只选 v2 时，相同绑定 ID 的 v1 不因 ID
  相同而重新开放。已导入 legacy 范围保留其原有授权语义。
- 两种适配共用 `ConfigurationAiResourceEntitlementService`，统一检查原范围／权限上限、期限、精确能力与绑定、能力可执行状态及连接启用状态。配置依赖／Schema
  校验提取为 `ControlDefinitionChecks`，保留原契约约束。
- 新来源不从旧配置合并允许项；来源失败不回退固定 Grant。`ControlConfigurationReader` 的完整上下文、来源绑定及快照优先级沿用第
  1 批；当前受理／派发准备优先于外层旧展示读取。

## 生产链路

`NewAiAdmissionConfiguration` 按 SPI 注册默认资格提供者，支持独立替换。该同一提供者同时注入模型发现服务和兼容控制面门面，替换提供者后不能出现列表已过滤但执行仍读旧
Grant 的分叉。

1. `ChatConfigurationQueryService` 直接通过资格端口发现模型，使用资格结果中同一次解析的连接判断网关支持，并与预算候选取交集；不读取原始绑定
   Grant。
2. `FixedControlCatalog` 保留 `BindingManager`／`CapabilityCatalog` 兼容入口，将模型发现、绑定、能力及连接解析委托资格端口，不再自行解析模型
   Grant。
3. 普通聊天、上下文校验、可靠受理、派发及模型网关沿既有接口进入同一资格规则。请求 Schema、预算、发布依赖和限额校验继续保留；预算校验使用第
   1 批端口。
4. `ToolChatService`、`ToolChatBudgetSelector` 直接注入资格端口；工具聊天使用一次资格解析返回的绑定和连接，不分开读取两份可能变化的资格。
5. 发送前仍由 `AdmissionAuthorization.revalidate` 和 `ConnectionManager.resolveCurrent`
   重新准备／复核，比较实际调用绑定与当前绑定；发现结果不是外发许可。只读快照仍不能授权写入。

`AdmissionAuthorization.requireConfigurationScope` 仅验证已经由可信身份／数据库来源取得的同次配置是否覆盖原权限上限及期限，不替代当前身份核验、写事务门控或发送前重检。业务模型使用方不调用
`grant().bindingIds()`；迁移桥中的原 Grant 仍由来源适配层解释。

## 后续边界

本批没有实现通用模型资源 ACL、个人模型管理、凭据用途治理或新的政策／受理事务端口。当前 DATABASE 资格仍受现有空间 AI
配置白名单控制，不据此宣称已完成设计中的私有模型分享、跨空间复用或成员治理闭环。`AiExecutionPolicyProvider`
、受理事务门控、同步事务中的配置读取及完整动态来源仍留待后续批次。

`FixedControlCatalog` 的名称、旧构造器及配置读取方法继续作为兼容入口保留，生产模型资格路径已委托新 SPI；原执行归属、固定发布引用、费用与
UNKNOWN 语义不变。文章、前端和数据库结构均未调整。

## 验证范围

新增资格专项覆盖发现／解析一致、未知或无授权绑定、连接／能力停用、多版本、有界发现、动作隔离、过期及只读上下文、同次快照和新操作刷新、撤权及来源失败不回退。数据库集成另外覆盖当前空间精确版本白名单、发现后撤权阻止后续解析，以及替换
SPI 后同时影响发现和真实聊天受理，拒绝时不产生 Invocation／Turn。

其他定向回归覆盖操作快照、发布／预算读取、当前身份及公共契约兼容、模型网关及外发、HTTP 受理、派发、重新生成／核对、工具聊天与计费。2026-10-30
最终定向套件共 261 项：258 项通过、3 项跳过，无失败／错误。其中 arte-ai 242 项（239 通过、3 跳过），arte-app 19 项通过；3 项 Redis
广播／提示／实时文字用例因未提供独立 Redis 测试端点而跳过。新增资格专项 9 项、数据库资格／替换 SPI 场景 2 项，另扩展既有精确版本及装配断言。

测试使用 JDK 21、Mockito 显式 Java Agent、隔离 H2、模拟网关和本地 HTTP 服务；端口测试在允许绑定回环端口的环境执行。数据库只读快照及聊天准备
SQL 上限回归均通过。没有执行业务库迁移、重启应用或调用真实供应商，不据此宣称 Redis、多节点或生产验收完成。

## 后续实施记录

第 3 批已增加执行政策和原事务受理／派发门控，并覆盖真实模型发送前复核；上文“后续边界”记录的是第 2 批交付时点。当前范围、同步
SPI 兼容要求及验证见 [执行政策与受理门控](EXECUTION_POLICY_GUARDS.md)。

## 第 4 批装配收口记录

以上记录保留第 2 批交付时点；生产装配现已移除旧固定控制面 Bean
依赖，改走显式业务端口，并收口付费工具的原事务发布／预算读取。当前兼容入口、来源选择及验收范围见 [装配收口与兼容验收](CONTROL_PORTS_COMPATIBILITY.md)。
