# 个人模型管理 4-02：发布与执行闭环

> 后继设计（2026-11-03）：[模型配置重构 M0](../../../ARTE%20AI%20模型配置需求及设计.md) 已定稿，M1～M5 尚未编码。本文保留当批实现与验证事实；后续
> DATABASE 新建模型采用审核方案／实际资源分离及独立空间预算契约，不按旧目录直供或分散创建流程实施新批次。

日期：2026-10-31。

2026-11-01 数据库模型管理[第四批](MODEL_DATABASE_AUTHORITY_BATCH4.md)在保留个人密钥本人限制的基础上，新增租户模型和独立审批的租户凭据用途。

本批在 [4-01 配置准备](MODEL_PERSONAL_PREPARATION.md) 上增加普通用户发布、当前运行状态，以及真实聊天执行中的个人凭据解析。
复用原 Invocation／Attempt、SSE、预算预留、结果与结算流程，不修改文章，不包含 4-03 管理页面。

后续 [4-03 管理页面与场景验收](MODEL_PERSONAL_MANAGEMENT_UI.md) 已接入普通用户页面；下文保留本批后端交付及当时验证边界。

arte-ai 提供发布 API、默认应用服务、值对象及 SPI；arte-app 提供审核目录、同空间运行适配、MyBatis、受控凭据解析、Spring 及 HTTP。
arte-core 沿用公共身份、授权、事务及治理能力，本批没有引入模型专有依赖。

## 发布规则

普通用户持有当前 `model:publish` 即可发布，不要求 `aiConfiguration:manage`。本人还必须是该模型个人凭据的责任主体。
发布事务锁定当前身份、租户及主空间，比较模型管理版本、草稿版本、凭据代次、验证记录和审核目录修订。

发布只接受本人的 SUCCEEDED 验证，且资源、草稿、凭据和模板摘要一致，checkedAt 距业务当前时间不足十分钟。 4-01 的 expiresAt
仍是探测处理期限；十分钟是本批发布对验证证据新鲜度的要求，不保证供应商能力或可用性。 发布本身不访问供应商。

发布方案取自当前 DATABASE 审核目录，与草稿的精确连接模板和 remoteModel 同时匹配，并要求当前生成适配器支持。
用户不能自报费率，也不能将低价模型的费率移植给任意高价模型。平台未审核的远端模型可以保存草稿和探测，暂不能发布；须先由受信目录登记正确模型与费率。
2026-11-01 已增加[独立审核模型与费率页面](MODEL_DATABASE_AUTHORITY_BATCH3.md)，平台管理员可从已登记数据库连接创建全新模型方案，无需文件模型种子。
上下文不能超过审核模型容量，输出必须小于上下文；首版能力仅保留审核的文本、流式及可选用量报告，不宣称元数据探测证明了工具调用。

服务端以稳定模型资源 ID 派生独立 `personal-…` 绑定族，为每次发布分配固定 binding／capability／connection 版本。 个人凭据引用使用
`personal-model-…@v<凭据代次>`，不复用平台密钥。基础地址与完整 `/chat/completions` 模板均可验证；发布时转换成运行时需要的基础地址。

不可变发布、附加证据、模型状态、空间授权 epoch、回执、审计和 Outbox 在同一物理事务提交。并发以模型 expectedVersion
决胜，幂等重放核对原字段及当前发布权限，只返回原证据。 旧版本不被草稿修改或新发布覆盖；重放不能恢复凭据。停用作用于稳定模型资源的全部版本，重新发布后仍需逐版本满足当前凭据与政策条件。

## 发现、权限与执行

DATABASE 控制面仍是唯一运行配置来源，按本空间合并个人不可变定义，不改变原 Grant、releaseRef、预算定义或 ExecutionOwner。
相同精确引用的内容冲突直接拒绝。同空间个人发布记录最多 256 项，合并后的各类目录仍受既有 256 项限制；发布提交前检查容量，不静默截断。

个人候选独立接受公共 `model:use` 和 `workspace:ai:use` 检查，无需普通用户先执行旧目录迁移。 旧 FIXED／DATABASE 模型继续按原迁移清单选择
ACL 或兼容 Grant。个人命名空间拒绝旧 Grant 兜底，包括发布证据缺失或当前拒绝的情况。 保留命名空间精确为 personal- 加 64
位小写十六进制摘要，普通 personal 前缀旧模型名保持兼容。 模型发布内容权威增加 PERSONAL（存储编码 2），原执行配置来源仍为
DATABASE；两者含义不同。

既有聊天发现响应保留原字段，并补充安全 resourceId／source；公共模型采用当前管理展示名称，兼容旧 Grant 项仍显示远端模型名。
不返回凭据、内部授权依据或连接地址，前端既有 displayName 渲染可以直接显示个人模型名称。

受理、派发事务及外发前继续按既有门控复核模型、精确内容、当前身份、成员和政策。个人发布额外检查模板摘要、凭据未撤销及代次一致、责任主体用途资格，并将发布的
maxOutputTokens 纳入发现上限和实际请求检查。 不会仅凭旧发现结果继续执行，也不会将当前目录 head 当作模型 latest。

实际外发的 ConnectionCredentialResolver 先读取原归属下的真实 Invocation，要求 RUNNING，核对执行
ID、原范围、权限上限、期限、预算、发布及精确能力／绑定。 随后在新的短事务及治理门控内检查当前公共模型资格、原执行完整权限、兼容预算、凭据用途和精确代次，再用
SM4-GCM／AAD 解密。 提交后才向受控 HTTP 运行时提供短暂 BearerCredential，不持数据库锁等待网络。普通登记凭据继续使用原环境变量映射；个人引用失败不回退环境变量。
凭据读取或解密失败发生在发送之前，按已知未发送终态处理并释放预留，不伪造供应商调用或未知费用。

首版个人凭据仅允许责任主体本人执行。分享 model:read／model:use／model:publish 不等于允许消费他人的个人密钥；共享者可看到安全原因
CREDENTIAL_USE_DENIED。 责任转交、团队凭据使用授权、跨空间复用另行实施。撤销或轮换后，旧凭据代次固定的发布不能再发起调用；须重新验证并发布新版本。
外发已经发生的请求无法通过本地撤销撤回，既有取消、未知结果和费用核对语义保持原样。

## 预算和状态

A1 已另增[模型使用准备后端](MODEL_USAGE_PREPARATION_A1.md)：用户可按目标空间批准规则显式准备兼容预算；发布本身仍不分配账户或开通空间
AI。前端入口由 A2 接续。

发布不新增预算、不提高额度、不重置账户或预留。使用当前 `tenantId + workspaceId + subjectId` 的独立预算，费率引用和币种继续由既有执行链路核验。
没有兼容预算也可保留发布记录；状态接口报告 NO_COMPATIBLE_BUDGET，聊天发现过滤该项。空间 AI 未准备或不可用时报告
WORKSPACE_AI_UNAVAILABLE，发布不暗中调用 ensure。

| 状态原因                             | 含义                                                                   |
|--------------------------------------|------------------------------------------------------------------------|
| READY                                | 当前模型、适配器、凭据用途、政策及兼容预算齐备；不保证余额或供应商成功 |
| MODEL_DISABLED                       | 稳定模型资源当前已停用                                                 |
| ADAPTER_UNAVAILABLE                  | 当前生成适配器不能执行该固定发布                                       |
| MODEL_USE_DENIED                     | 当前主体没有所需空间或模型使用资格                                     |
| CREDENTIAL_STALE                     | 凭据缺失、撤销或代次不匹配                                             |
| CREDENTIAL_USE_DENIED                | 当前主体不是个人凭据责任主体                                           |
| TEMPLATE_STALE／TEMPLATE_UNAVAILABLE | 模板摘要变化，或连接受当前租户／空间政策、模板状态或平台出口白名单限制 |
| NO_COMPATIBLE_BUDGET                 | 当前主体没有获准使用且费率匹配的预算                                   |
| WORKSPACE_AI_UNAVAILABLE             | 当前空间 AI 尚未准备、关闭或没有操作资格                               |

这些是当前准备提示，不能作为以后执行许可。权威读取、数据完整性或数据库故障继续传播错误，不伪装为普通缺预算。

## HTTP

全部 POST，前缀 `/arte/ai-new/model-resource`。沿用 Bearer、严格字段、ResultContext、平台错误、X-Trace-Id 及 Idempotency-Key。
发布响应复用安全 Version 视图，不返回 endpoint、SecretRef、密文或内部授权证明。

| 端点                                | 请求字段                                                                                                            | data                                                                   |
|-------------------------------------|---------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------|
| listPersonalModelPublicationOptions | scope、resourceId                                                                                                   | templateBinding、rate、catalogRevision、contextWindowTokens 的方案列表 |
| publishPersonalModel                | scope、resourceId、expectedVersion、draftVersion、credentialVersion、validationId、templateBinding、catalogRevision | CommandReceipt&lt;Version&gt;                                          |
| getPersonalModelPublicationStatus   | scope、resourceId、精确 binding                                                                                     | binding、ready、reason、budgetRefs、maxOutputTokens                    |

流程：4-01 保存及验证 → 查询审核方案 → 按三个独立版本号发布 → 查询当前状态 → 既有聊天配置发现 → 原聊天提交／SSE。
修改发布配置需重新验证并发布；客户端不提交 endpoint、API Key 或自报执行身份到聊天入口。

## 数据库与启用

新增 [个人发布表 DDL](../scripts/arte-ai-model-personal-publication-ddl-mysql.sql)，表
`arte_ai_model_personal_publication` 保存不可变附加证据及摘要。 模型精确内容仍由原 `arte_ai_model_version` 保存；密文仍在
4-01 独立凭据表。

新装：执行公共平台、ACL、当前模型资源、模型 ACL 清单、4-01 草稿凭据表，再执行个人发布表 DDL。
既有旧模型资源表：停写并备份，先显式执行 [来源约束升级](../scripts/arte-ai-model-personal-source-migration-mysql.sql)，将
source 上限 1 扩为 2，再执行个人发布表 DDL。 来源升级唯一确认原 CHECK 后，以单条 ALTER 替换约束；无法确认或重复执行报错，不猜测、不自动跳过。新装基线已有
source=2，不执行该升级脚本。

需要全部节点具备本批协议，再显式启用：

```properties
arte.ai-new-model-management.enabled=true
arte.ai-new-model-publication.enabled=true
arte.ai-new.configuration-mode=DATABASE
```

沿用 4-01 的 SM4 密钥环、公共身份／持久化／空间及 AI enabled，并启用原生成、执行及 Worker 配置才能自动处理聊天任务。
平台审核目录、当前空间 AI 准备及租户／空间个人凭据和出口政策须已有效；本批不自动导入目录、激活旧空间或开放默认政策。
个人发布开关默认关闭，不要求旧部署安装新表。显式开启则缺表或不兼容配置拒绝启动；已有个人发布记录时，关闭开关或丢失个人门控／凭据解析器同样拒绝启动。
默认解析器保留环境变量能力；已有自定义凭据 Bean 的部署需要显式整合用途解析，不能同时安装多个模糊来源。

## 验证边界

专项覆盖实际 Spring／MyBatis／ACL／严格 HTTP、普通用户发布、CAS 并发、幂等、审计回滚、验证及目录依赖、凭据轮换／撤销、分享不转交凭据用途、预算过滤和受理／外发撤权。
回环服务验证认证 GET /models、个人密钥 POST /chat/completions、SSE、真实 Invocation／Attempt、结果和 PROVIDER_BILL 结算。
MySQL 专项只连接本批单独创建的一次性 8.4 实例，逐例新建删除随机库，包含来源约束升级及重复执行拒绝。
未调用真实供应商、迁移业务库、重启现有服务或实施前端／文章改造。

测试命令（backend，JDK 21、离线 Maven；受限 JVM 沿用 Mockito javaagent）：

```shell
mvn -o -pl arte-app -am -Dtest=PersonalModelPublicationIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

MySQL 专项通过专用隔离实例的 `platform.test.mysql` 运行，沿用平台测试随机库及固定测试账户约定，不能指向业务库。

2026-10-31 验证结果（JDK 21、离线 Maven、显式 Mockito javaagent；各批包含重复用例，不相加作为独立用例数）：

- 定向兼容回归 377 项：core 64、AI 158、app 155，无失败／错误；2 个 MySQL 专用场景在 H2 跳过。
- 发现元数据及兼容 HTTP 调整后，受影响专项 87 项：AI 38、app 49，无失败／错误；1 项来源约束升级在 H2 跳过。
- 一次性 MySQL 8.4 专项 29 项：个人发布 13、旧 ACL 执行及当前读竞争 16，全部通过，无跳过；覆盖上述两个 MySQL
  专用场景。测试后已删除本批临时容器。
- 最终个人发布专项 14 项，无失败／错误；H2 跳过的 1 项来源升级已在 MySQL 通过。新增解密密钥缺失场景，确认零生成请求、已知失败和
  RELEASED 结算；正常路径使用真实 Spring 生成自动装配完成 SUCCEEDED 与 SETTLED。
- 主代码及测试编译、关键发布／凭据类 IDE 检查、git diff --check 通过。没有访问或执行业务库 DDL，没有重启现有节点。
