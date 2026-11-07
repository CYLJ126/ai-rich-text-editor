>
本文为历史设计记录。当前版本只支持数据库来源，已移除固定实现和兼容构造器；升级见 [数据库单一来源](../arte-app/docs/AI_DATABASE_ONLY.md)。

# AI 控制面读取端口：第 1 批

后续进度：模型使用资格已在 [第 2 批](MODEL_ENTITLEMENTS.md) 拆出；下文保留第 1 批的交付及验收边界。

日期：2026-10-30。对应顶层设计“详细设计”第 5.2～5.3 节，落实发布目录及预算定义读取，保持 FIXED／DATABASE 现有运行行为。端口属于
arte-ai，未调整文章模块、公共资源 ACL 或数据库结构。

## 端口与业务对象

| 端口                                   | 职责                                                                         | 返回对象                                                             |
|----------------------------------------|------------------------------------------------------------------------------|----------------------------------------------------------------------|
| `spi.control.PublishedAiCatalog`       | 按精确引用读取绑定、能力、连接、费率；提供同一次操作的发布视图和历史结算费率 | `PublishedAiDefinitions`、既有绑定／连接／能力对象、`RateDefinition` |
| `spi.control.BudgetDefinitionProvider` | 按当前执行主体及原空间、READ／INITIALIZE／EXECUTE 动作筛选预算定义           | `pojo.budget.BudgetDefinition`                                       |

发布目录不携带 Grant、预算账户或凭据明文，也不代表当前模型使用许可；绑定使用仍由现有控制面复核。预算定义与账户分开，读取不建账、不分配额度、不重置
held／charged。预算服务继续检查实际账户的归属、额度与固定费率，初始化仍需独立写授权。

新业务对象不替换 `NewAiProperties.Rate`／`Budget` 的配置绑定或持久化类型。原发布 JSON、历史快照、类型别名、HTTP DTO
及幂等摘要保持原格式，不需要 DDL、回填或改写历史记录。

## 来源与一致性

- `FixedControlReadAdapter` 读取既有固定配置。
- `SourceControlReadAdapter` 通过现有 `AiControlConfigurationSource` 桥接 DATABASE 来源，阻塞读取在专用数据库调度器执行。
- Spring 分别按两个接口注册 Bean，支持独立替换；生产读取服务按接口注入。`FixedControlCatalog.readAdapter()`
  及旧构造器只作为手工装配调用方的兼容桥。
- `ControlConfigurationReader` 优先复用与完整执行上下文匹配的受理／派发准备结果或 HTTP 只读快照。
  `PublishedAiCatalog.inSnapshot` 将目录和预算读取绑定到同一次操作，跨执行或不同来源的复用拒绝；新订阅重新读取。
- 该读取范围使用独立的只读上下文键，不创建写授权。新受理／派发的当前准备优先于外层旧读取范围，不能用只读数据跳过
  `requireWrite` 或发送前复核。

## 已改接的链路

1. `BudgetAccountQueryService`、`BudgetAccountInitializer` 通过预算定义端口取得授权定义。
2. `ChatConfigurationQueryService` 通过发布视图和预算端口组装模型选项，绑定资格仍由现有 `FixedControlCatalog` 判断。
3. `GenerationDispatcher` 的派发报价读取精确费率；终态结算通过独立的历史读取方法取得原 Reservation 固定的费率。
4. `ToolChatBudgetSelector` 的模型报价，以及 `ToolBillingService` 的按次工具预算候选与账户校验，使用同一发布／预算读取范围。

历史费率入口仅供先核对过耐久执行／预留记录的内部结算使用，不直接开放为 HTTP。DATABASE
实现读取已发布权威，利用发布只追加且旧费率不可覆盖／删除的约束保留精确版本；不要求原主体仍有新调用资格，未发布时禁止回退部署文件种子，缺失版本不能改用其他版本。

## 后续边界

本批没有实现 `AiResourceEntitlementService`、政策端口或新的受理事务门控。`AdmissionAuthorization`／
`AiControlConfigurationSource` 的迁移期快照仍使用 properties；`FixedControlCatalog` 的绑定授权、校验与兼容读取仍保留。受理事务以及
app 的付费工具事务授权适配仍有直接配置读取，应随资格／门控批次处理，不能把同步事务中的读取直接替换为跨线程异步调用。

没有开放新增供应商、个人凭据录入、完整动态配置或热更新，也没有切换 FIXED／DATABASE
运行模式。部署开关、线程、出口上限与连接池继续使用原部署参数。已有工作空间独立创建和 AI 按需准备行为保持不变。

## 验证

新增专项覆盖精确引用、不可变视图、预算动作分离、跨主体预算过滤、同次读取一致性、跨来源／执行拒绝、只读范围不能授权写入、新准备覆盖旧读取及撤权后的历史费率。数据库集成验证未发布时不回退文件费率、两个端口的
Spring 接线、发布追加版本和成员撤权后历史费率仍可读取。

2026-10-30 定向回归：arte-ai 176 项、arte-app 17 项，共 193 项通过，无失败／错误／跳过。包括新增 7 项读取端口专项，以及预算
HTTP、配置发现／装配、操作快照、36 项派发、重新生成与核对、工具聊天预算／循环、25 项独立工具计费、数据库控制面和公共契约／执行身份兼容样本。JDK
21、Mockito 显式 Java Agent；本地 HTTP 派发测试在允许绑定回环端口的环境执行。

数据库只读快照计数回归通过，本次隔离库记录的 prepared SQL 为模型发现 14、预算查询 15、会话详情 12，仍无展示路径 `FOR UPDATE`
；这些是该测试的 SQL 次数，不代表实际接口延迟或生产性能。

回归使用隔离 H2、模拟网关／本地 HTTP 供应商；未操作业务数据库、执行 DDL 迁移、重启应用或调用真实供应商。额外检查的既有
`ToolChatBudgetMigrationTest` 将当前基线中后续 S5 结构与较早增量脚本作逐字比较失败，相关 SQL 与测试均与 HEAD
一致，最终定向套件排除该项，不据此宣称全仓通过。

## 第 4 批装配收口记录

以上记录保留第 1 批交付时点；生产装配现已移除旧固定控制面 Bean
依赖，改走显式业务端口，并收口付费工具的原事务发布／预算读取。当前兼容入口、来源选择及验收范围见 [装配收口与兼容验收](CONTROL_PORTS_COMPATIBILITY.md)。
