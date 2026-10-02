# 现有身份与权限接入

本批将 arte-base 的授权端口接入当前登录会话、用户表、文章／目录以及继承分享关系。实现位于
`com.arte.app.security.bridge`，作为 app 组合层使用；base 和 ai-new 没有新增 Spring、core、文章或数据库依赖。
旧认证过滤器、UserContext、PermissionValidator、控制器和旧 AI 的调用及引用不变。

后续 [最小模型调用](../arte-ai-new/MINIMUM_MODEL_CALL.md) 已通过新入口组合账号、任务、应用策略、受控连接和明确外发同意；本说明中“本批”指身份接入阶段。

## 职责与操作

| 类型                         | 职责                                                                                             |
|------------------------------|--------------------------------------------------------------------------------------------------|
| ExistingIdentityAdapter      | 核对 Spring Security 身份、Token 签名、当前在线会话与数据库启用状态，交叉核对用户名和稳定用户 ID |
| ExecutionContextFactory      | 验证空间成员与应用／绑定动作策略，生成服务端 traceId 和期限，原子保存任务与动作／资料范围        |
| ExistingShareQueries         | 复用既有 ShareMapper 的目录递归、用户分享与角色关系 SQL，显式传入用户名并直接使用 JDBC 执行      |
| LegacyResourcePermissions    | 将旧阅读、批注、编辑、共享管理权限转换为新动作，核对资源、分享资源和角色的启用状态               |
| ExistingAuthorizationService | 实现 AuthorizationService，逐次检查主体、成员、任务、受限执行者、资源作用域和当前领域权限的交集  |
| ExistingEgressPolicy         | 实现 EgressPolicy，检查全部来源的 READ／AI_PROCESS／EGRESS、当前连接、用途规则和完整请求同意     |
| EgressConsentService         | 在用户明确确认后，以当前会话核对发起者并保存本次完整实际外发请求的同意记录                       |
| JdbcSecurityRepository       | 使用显式主体参数查询旧账号及新策略记录，提供事务内的任务／同意写入                               |
| NewSecurityConfiguration     | 为新入口提供上述 Bean；配置开启后可以按 base 端口注入                                            |

公共契约仍只表达结构；这些实现补上真实数据检查。新入口不得接收客户端反序列化的 ExecutionContext、执行者、最终内容摘要或完整
EgressRequest 作为可信内部对象。服务端从已验证会话生成身份，从业务资料提供者验证版本／草稿／范围，再准备实际外发资料与摘要。

入口已有的 Spring Security 方法级权限检查继续适用；应用／绑定策略不代替新控制器应配置的入口权限。
平台管理菜单权限没有内容权限兜底；匿名身份、未启用账号、未注册任务、缺失成员或任务范围均不能通过。

## 持久化作用域

增量 DDL 创建成员、资源归属、独立资源动作、应用／绑定策略、任务、任务执行者动作、任务资源动作、服务主体、连接、用途规则及同意记录。
新表默认拒绝；没有在程序启动或账号登录时自动授权。

个人映射脚本以当前稳定用户 ID 生成 `personal-{id}` / `workspace-{id}`，只为正常账号和其已有自有资料登记作用域。 INSERT
IGNORE 使脚本可重复执行，并保留既有撤销和人工迁移后的资源映射。账号后来禁用时，即使成员记录还在，实时账号检查也会拒绝。
新账号及新资源须由后续创建流程登记，当前可重复运行回填脚本；本批未改旧注册／资源创建入口。

这个映射是当前个人阶段的明确实现选择。旧表没有租户／空间信息，不能推断团队成员关系。
新链路访问他人分享或公共资料时，仍需登记该资源所在空间的成员关系；旧公开标志和分享记录不会自动成为跨空间成员资格。
团队迁移需要显式调整成员和资源归属，并确保目录继承链上的分享资源处于同一租户／空间。旧入口的分享及公开行为保持原样。 无法解析旧
create_by 对应账号的资料不会自动放入默认空间，须先整理归属。

## 任务和权限交集

`ExecutionContextFactory.create` 接受入口选定的 applicationId、bindingId、动作集合，以及 `Map<ResourceRef, Set<String>>`
资料动作范围。 只传动作的重载登记空资料范围，可用于仅处理用户消息；不会隐式允许读取所有自有资料。
资料动作不能超出任务动作，完整资料引用参与存储键：正式版本、草稿摘要和范围变化须重新准备任务范围。
范围登记不代表已经通过领域授权，实际操作还会实时检查账号、资源和共享权限。

完整 ExecutionContext 的规范化摘要固定发起者、租户／空间、traceId、期限、权限集合和其他上下文字段。
篡改主体、追加动作或删除期限后不能命中原任务。任务表的 context_key 是策略关联键，不是业务受理记录或 ExecutionStore 的替代品。
新执行存储之后仍需保存实际上下文用于恢复；base 的 AcceptedExecution 受理语义尚未在此实现。

阅读、批注／评注、编辑、共享管理分别对应既有权限枚举；不存在或已删除的资源不会按“默认 READ”处理。 目录 CREATE_CHILD
不扩大为目录编辑／共享管理。角色分享逐次检查当前 user_to_role 关系和角色启用状态；继承分享还核对其资源作用域。
AI_PROCESS、EGRESS、EXPORT、COPY、SUBSCRIBE_EVENTS 和 DOWNLOAD 均需独立的新资源动作记录，旧可读或完全控制不会自动开放这些动作。
本实现目前只支持 ARTICLE／CATALOG；其他领域须提供自身策略，未知动作／领域不会默认允许。

直接执行时任务动作属于发起用户。委托执行还须具有已启用的 SERVICE 登记及该任务的对应服务动作，且继续核对发起用户动作和领域权限。
任务创建不会自动给 Worker 授权。后续 Worker 组合层须从受控服务身份构建 executor，不能从 HTTP 请求体或模型输出取得服务身份；当前没有新增
Worker、服务认证协议或委托管理入口。

## 外发与同意

受控连接记录将租户／空间内的完整固定连接引用映射到规范化 origin。用途规则进一步按当前应用、绑定、连接和 purpose 限定。
仅有用户消息也需通过这些检查；不能因来源集合为空绕过外发策略。允许 inference 不隐含允许 training 或其他用途。
连接和用途规则由可信配置／控制面写入，本批没有开放任意用户修改策略的 REST 接口。

用户明确确认时，`EgressConsentService.confirm(httpRequest, preparedRequest)` 校验当前会话属于任务发起者，重查外发前提，再生成
`egress-consent` 的正式引用。preparedRequest 来自服务端已准备的实际资料；客户端只能确认该已准备执行，不能自报摘要覆盖服务端内容。
采纳内容或生成成功不能自动触发此操作。

同意记录保存规范化请求摘要和任务有效期，覆盖完整上下文、执行者、按序来源引用及 citationId、连接版本／origin、用途和最终业务内容摘要。
摘要采用 v1 长度编码再计算 SHA-256；没有持久化正文、密码或 Token。consentRef 本身单独验证，其余字段固定在同意摘要内。
换用户、任务、范围、内容、用途或目的地后须重新确认。引用存在也不能覆盖当前资源权限、连接、用途或同意撤销。
一个来源不允许时拒绝整个集合。相同请求可以在有效任务内再次评估；真实调用的幂等及副作用去重仍由后续执行层负责。

## 当前策略与事务

权限检查不缓存允许结果。分享查询复用原 XML 生成 BoundSql，通过 JDBC 执行，避免旧 ThreadLocal 拦截器和 MyBatis 会话缓存。
两个端口显式覆写强制入口，保证 Spring 事务代理覆盖接口 default 方法。 新策略提供者使用 REQUIRES_NEW / READ_COMMITTED
短读事务，避免沿用外层业务长事务的旧快照；任务及同意写入使用事务。 必须注入配置中的 Spring Bean，生产代码不得绕过代理手工
new 提供者。

允许决策携带实际检查版本／旧权限内容快照，期限取五秒、上下文期限、任务期限和适用同意期限中的最早值。
每次强制操作重新核对数据库启用状态；策略写入方变更记录时须递增 revision。数据库故障由 base 强制检查转为
POLICY_UNAVAILABLE。 5 秒是此 app 提供者的初始租约选择，base 没有全局硬编码。

检查与业务保存／实际网络发送仍存在时间窗口；后续领域事务和连接运行时必须在提交／发送边界落实一致性。 本批未发送网络请求，未实现
DNS／重定向／SSRF 出口检查，未实现多实例失效通知、审计落库或流输出批次检查。 已发出的内容不能因数据库撤销而收回。

## 启用与新入口调用

按顺序执行 `scripts/arte-security-bridge-ddl-mysql.sql`、`scripts/arte-security-bridge-personal-backfill-mysql.sql`
，再为实际新入口配置所需 应用／绑定动作策略；AI／外发还需独立资源动作、受控连接和用途规则。SQL 默认不生成这些允许记录。
脚本是待部署的增量材料，本次未对运行数据库执行，也没有自动挂入旧部署流程。

然后配置：

```yaml
arte:
  security:
    bridge:
      enabled: true
      task-lifetime: PT30M
```

默认不开启，避免未迁移数据库的旧应用启动需要新表。开启后为新入口注入 `ExecutionContextFactory`、`AuthorizationService`、
`EgressConsentService` 和 `EgressPolicy`。旧入口继续使用旧接口。

最小资料调用示例（入口先通过认证和适用的方法级权限）：

```java
Set<String> actions = Set.of("resource.read", "resource.ai_process", "resource.egress");
// source 来自已验证版本／范围的业务资料提供者，入口选定受控应用和绑定。
ExecutionContext context = contextFactory.create(httpRequest, tenantId, workspaceId,
        applicationId, bindingId, actions, Map.of(source.resource(), actions));
authorizationService.requireAuthorized(AuthorizationRequest.of(context, source.resource(), CommonResourceAction.READ));
// AI_PROCESS／EGRESS、用途、连接以及实际完整来源会在外发前重查。
// prepared 由服务端基于上述 context、受控目的地、最终业务内容和来源准备。
ResourceRef consent = consentService.confirm(httpRequest, prepared); // 仅在用户明确确认后调用
EgressRequest confirmed = new EgressRequest(prepared.context(), prepared.executor(), prepared.sources(),
        prepared.destination(), prepared.purpose(), prepared.contentDigest(), consent);
egressPolicy.requireAllowed(confirmed);
// 之后才能进入具有真实出口检查的连接运行时；本批未实现发送。
```

策略登记是可信管理边界。新 AI 的应用／绑定／连接控制面及新资源创建流程完成后，应通过受控管理操作同步这些策略记录，不能让模型或请求体直接写库。
旧用户名字段仅在适配旧资源和分享关系时使用，base 上下文不携带旧完整 UserOnlineInfo；后续旧资源迁移可将 create_by /
target_user 改为稳定主体引用。

## 验证

在 backend 中执行：

```bash
mvn -o -pl arte-app -am -Dmaven.compiler.proc=full \
  -Dtest='IdentityAndContextIntegrationTest,AuthorizationIntegrationTest,EgressIntegrationTest,SecurityWiringIntegrationTest,PersonalScopeBackfillIntegrationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
mvn -o -pl arte-base,arte-ai-new -am test
```

集成测试使用 H2 和真实策略表／既有分享 SQL，只替代 Token 签名／Redis 在线会话依赖。 H2 测试为旧递归 CTE 补等价显式列名，为
MySQL 无长度 CHAR 转换使用 VARCHAR，并去除引擎／字符集声明；生产 SQL 不修改。
覆盖身份不一致、停用账号、跨空间访问、目录和角色分享、实时撤销、任务资料越界、委托权限收窄、完整同意范围、部分来源拒绝、短期权限窗口、
Spring 配置开关、任务原子回滚、外层事务内的权限重查，以及回填重复执行不会恢复撤销。 尚未在真实 MySQL、Redis 或运行中的新 AI
入口做端到端验证。
