# base 核心值契约

本批实现依据顶层设计「公共数据契约」及共用协议约定，供新 AI 与后续领域模块独立使用。生产代码只依赖 JDK；JUnit 仅为测试依赖。

## 已实现范围

| 契约                    | 必填与缺省                                                                       | 结构及语义约束                                                         |
|-------------------------|----------------------------------------------------------------------------------|------------------------------------------------------------------------|
| PrincipalRef            | principalId、type 必填                                                           | 只有主体标识及种类，不包含在线会话、密码或 token                       |
| ExecutionScope          | tenantId、workspaceId、principal 必填                                            | 没有隐式系统主体或默认租户／空间                                       |
| ResourceRef             | resourceType、resourceId 必填；其他字段缺省为 null                               | 正式版本是不透明标识；草稿必须带摘要，范围必须绑定版本或草稿           |
| SourceRef               | resource 必填；citationId 可为空                                                 | 来源必须指明正式版本或带摘要的草稿，不能记录可变的当前资源引用         |
| SchemaRef               | schemaId、version 必填                                                           | 必须指向版本化 Schema；具体格式和内容校验由提供者完成                  |
| SecretRef               | secretId 必填；version 可为空                                                    | 空版本由凭据提供者解析当前版本，值对象不解析凭据                       |
| IdempotencyKey          | key、operation、requestDigest 必填                                               | 摘要算法及输入规范化由所属操作确定，不把相同键、不同摘要视作另一查找键 |
| IdempotencyIdentity     | scope、operation、key 必填                                                       | 用作去重查找身份，包含租户、空间、主体及主体种类，故意不包含摘要       |
| CancellationRef         | executionId 必填                                                                 | 引用取消状态的权威执行，不存储本地取消标志                             |
| ExecutionContext        | scope、traceId、authorizationScopes 必填；其余可为空                             | 防御性复制授权范围；允许加载过期历史，由执行入口检查期限               |
| ExecutionError          | code、failureStage、sideEffectStatus、resultCertainty 必填；correlationId 可为空 | 潜在可重试与无需核对可重试分开，结果未知或存在副作用时需要核对         |
| AcceptedExecution       | 所有字段必填                                                                     | 查询地址为站内绝对路径或 HTTP(S) URL，禁止 user-info、查询串及片段     |
| ExecutionEvent&lt;T&gt; | executionId、eventType、occurredAt 必填；attemptId 可为空                        | 序号非负；内联负载与负载引用必须且只能提供一个                         |

所有标识保持原值，不修剪、不改大小写；null、空白及含空白／控制字符的标识被拒绝。缺省字段使用 null，不能用空字符串替代。构造参数不合法统一抛出
IllegalArgumentException，消息只包含字段名和规则，不回显输入内容。

## 资源与来源

- ResourceRef.current 表达读取当前内容，不能直接作为 SourceRef。
- ResourceRef.saved 固定正式版本，版本不被假设为整数、时间或 SemVer。
- ResourceRef.draft 记录草稿标识、摘要及可选基准版本；新建未保存草稿允许没有基准版本。
- 草稿引用同时携带 version 与 draftId 时，version 表示草稿的基准正式版本，不把草稿当成已保存内容。
- rangeRef 是领域解释的范围引用标识，不是正文或任意补丁。
- 来源提供者仍需检查草稿摘要、范围有效性、内容版本和当前访问权限；值对象只能验证结构。

## 执行上下文与期限

接入层从已认证身份、已验证租户／空间关系及任务授权构建 ExecutionContext，并显式传给异步任务、Worker
和领域调用。值对象的公开构造方法不证明主体已认证，也不自行验证成员关系；这些能力在后续身份／授权接入中实现。

authorizationScopes 为不可修改的防御性副本，空集合表示没有声明的任务授权。它不替代实时资源授权、外发策略或权限撤销检查。create
工厂不自动补主体、范围、预算、期限或发布信息。

isExpiredAt 使用调用方传入的 Instant，达到 deadline 即过期。构造不读取系统时钟，因此历史重放和结果查询不会因上下文已过期而无法读取。没有
deadline 表示没有显式截止时间，后续执行入口仍可按策略施加期限。

## 幂等与错误

存储以 IdempotencyKey.identityIn (scope) 返回的 IdempotencyIdentity 查找，命中后再比较 requestDigest：

1. 同一作用域／操作／键、同一摘要：复用原受理记录或结果。
2. 同一作用域／操作／键、不同摘要：返回幂等冲突。
3. 不同主体、主体种类、租户、空间或操作：不同的查找身份。

这里提供值身份，不实现唯一约束、原子去重或摘要算法；这些由后续执行存储及领域事务完成。业务应用的 applicationKey 与生成幂等分开。

ErrorCode 允许各领域通过枚举扩展，CommonErrorCode 只定义通用机制错误，不绑定 HTTP 状态或国际化。ExecutionError 的
canRetryWithoutReconciliation 仅在允许重试、已确认结果且没有副作用时返回 true；实际重试仍需检查授权、幂等、预算、期限及次数。

BaseException 持有 ExecutionError，异常消息使用稳定错误码。对外响应只映射 error ()，cause 仅供内部诊断；不从 Throwable
消息自动推断安全文案、副作用或重试性。

## 受理与事件

AcceptedExecution 构造成功不证明可靠受理；只有耐久受理成功后才能返回回执。此处只允许不带查询串的基础查询地址，游标及其他订阅参数由后续事件
API 单独添加，凭据不能拼入地址。

ExecutionEvent 的泛型负载由所属模块保证不可变性；base 不深拷贝任意对象，也不负责序号的单调分配、事件持久化、保留期或重放。后续存储实现必须兑现这些保证。

## core 保留与迁移

| 旧类型                                     | 本批处理                                                        | 新代码路径                                                  |
|--------------------------------------------|-----------------------------------------------------------------|-------------------------------------------------------------|
| com.arte.core.pojo.UserContext             | 仅加 @Deprecated 和说明，ThreadLocal、默认用户及已有 API 不变   | 显式传递 ExecutionContext；认证和作用域验证后续由接入层提供 |
| com.arte.core.exception.CommonException    | 仅加 @Deprecated 和说明，构造方法、结果码、国际化及继承关系不变 | BaseException + ExecutionError + ErrorCode                  |
| UserOnlineInfo                             | 保留                                                            | 登录会话资料，不整体复制进新执行上下文                      |
| ResultContext／IResult／PageView           | 保留                                                            | 属于响应包装及框架适配，不作为本批公共执行值契约迁移        |
| ResultCodeEnum、领域异常、缓存及数据库代码 | 保留                                                            | 通用码与领域码、技术适配需后续按所属模块分别迁移            |

新代码不依赖 core；core 不新增对 base 的依赖，也不自动把旧错误码映射到新错误。现有调用方不改引用、不改响应、不改运行接线；真实身份、授权或错误适配在新功能完成后逐步接入。

上述为核心值契约的最初范围。产物值与生命周期已在后续 [最小执行支撑](MINIMUM_EXECUTION_SUPPORT.md) 中补齐； 变更、租约、Job
的值类型及其他公共服务／端口继续按阶段实现。
后续授权与外发操作已定义，详见 [授权与资料外发契约](AUTHORIZATION_AND_EGRESS.md)，真实身份及策略提供者接入仍待实现。

## 验证

在 backend 下执行：

```bash
mvn -o -pl arte-base,arte-ai-new -am test
mvn -o -pl arte-core -am -DskipTests -Dmaven.compiler.proc=full compile
```

测试覆盖防御性复制、缺省与非法标识、草稿及版本来源、期限边界、幂等身份隔离、结果未知的重试判断、查询地址、事件负载二义性及错误诊断分离。

当前本机 Java 24 下，core 的默认编译未生成 Lombok 成员；通过命令行显式启用注解处理后编译通过。本批未修改旧模块构建配置。
