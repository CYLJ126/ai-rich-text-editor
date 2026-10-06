# 第 1～3 步：固定配置、字节存储与可靠受理

准入业务异常统一使用 CommonException.resultCode；枚举、标准响应数字码及国际化文案见 [准入错误码说明](ADMISSION_ERROR_CODES.md)。

当前可以通过 Service 集成测试验证：创建会话 → 组装单条用户文本 → 保存实际快照 → 可靠受理 → 读取 Invocation／Turn／派发 Outbox。成功受理后状态为 ACCEPTED。第 4 步的模型网关可独立启用，详见 [单次模型交互](GENERATION_IMPLEMENTATION.md)；尚无 Worker 或对外 HTTP API。

## TODO

1. 后续把 arte.ai-new.grants 配置改到数据库配置，当前在配置文件中只是为了验证最小链路（不过配置文件也是能支持多主体的）。

## 1. 启用与装配

NewAiAdmissionConfiguration 通过 Boot 自动配置元数据注册，只有 `arte.ai-new.enabled=true` 时装配。默认不启用，不修改旧 AI 包及认证链路，所有新 Bean 使用 `newAi` 名称前缀。

参考 [固定配置示例](examples/ainew-admission.yml)，替换为实际服务器维护的主体、稳定 ID、空间、固定能力／绑定／连接版本、SecretRef、费率和预算定义，再显式导入。示例文件不在运行时 classpath，不会自动启用或连接 example.invalid。

- 持久化绑定 `data-source-bean` 指定的物理数据源，默认 appDataSource；不要填需要旧 ThreadLocal 选择目标的路由数据源。
- 独立 SqlSessionFactory 只加载新 AI Mapper，不继承旧插件，不作为全局工厂注册；所有阻塞事务进入有界工作调度器。
- 当前授权来自受信固定 grants，不从 Authentication 角色或请求体推算 subjectId。停用 grant、范围或引用不符均拒绝；这套 allowlist 不自动同步旧用户表，生产身份生命周期应接入真实 ExecutionAuthorizationResolver，当前配置授权只适用于明确维护的固定授权源。
- 权限包括 ai:invoke、ai:conversation、ai:read；预算初始化独立要求 ai:budget:admin。固定绑定／预算资格按主体和空间限制。自定义授权解析器仍须保持配置中稳定主体及使用范围一致。
- 未知配置字段、引用缺失、重复定义、绑定契约不一致、费率／币种／预算归属不匹配会导致启用失败，不以默认空配置冒充可用。
- 首批只受理单条 USER 文本、TextOutput、一个 Attempt、无工具。历史、记忆、资料、目标资源、ChatProfile、追问／编辑分支和重新生成明确拒绝。

## 2. 数据库部署与预算准备

应用启动不连接新存储、不执行 DDL、不创建或重置预算账户。

- 新数据库部署执行 [完整 DDL](scripts/arte-ai-new-ddl-mysql.sql)。
- 已经执行原 DDL 的数据库，只执行一次 [本次增量 DDL](scripts/arte-ai-new-admission-ddl-mysql.sql)。新增 arte_ai_conversation_creation、arte_ai_context_snapshot、arte_ai_result 三张表；无需重建原表。

预算由管理入口在验证身份后调用 `BudgetAccountInitializer.initialize(budgetRef, context)`。它只采用服务器固定预算定义，首次建立零 held／charged 账户；重复调用读取原账本，不清零余额、用量、版本或已发生费用，配置不符明确冲突。测试中在临时 H2 数据库显式部署和初始化，不操作用户当前数据库。

## 3. 受理入口与幂等

1. 使用 ExecutionContextFactory 从已认证 Authentication 构建上下文；会话创建和提交调用使用不同的操作幂等键。
2. ConversationService.create 返回真实持久化会话；ConversationService.find 验证归属，turn 读取所属轮次。创建命令的摘要和原会话引用单独耐久保存，会话版本变化后仍可重放。
3. EntryRequests.Chat 指定会话及 expectedVersion、固定 capability／binding、ContextRequest、GenerationOptions 和 ExecutionOptions。
4. ContextService.assemble 校验实际绑定、角色、UTF-8 请求上限及容量，返回规范化输入快照；不静默裁剪或忽略未支持的资料。utf8-estimate-v1 是明确标记的保守启发式估算，不是供应商精确 tokenizer，也不作为计费用量。真实供应商发送阶段仍须落实适用的容量检查。
5. Coordinator.submit 重新授权并检查发布、绑定、Schema、快照事实、输出预留及预算账户；先按耐久受理键检查摘要，再检查新调用期限／提交会话 CAS。不会在受理时预留预算，原子预留归第 5 步的 Attempt 派发。
6. 新请求先保存快照字节，然后通过现有 ExecutionStore.accept 同事务提交 Invocation、Turn、会话版本、首事件及派发 Outbox，成功提交后才返回 AcceptedExecution。

### 相对期限的重放规则

ExecutionOptions 增加可选 requestedTimeout，保留原五参数构造器和旧 JSON 的读取：

```java
Duration requestedTimeout = Duration.ofMinutes(1);
// 上下文工厂也使用同一 requestedTimeout；实际 deadline 由服务端分配。
ExecutionOptions options = new ExecutionOptions(
    context.deadline(), 1, 1_048_576, 0, 0, requestedTimeout);
```

- requestedTimeout 非空：摘要记录相对时长，排除重新接收时分配的绝对 deadline；同键重放沿用原 Invocation 和原期限，不能延长执行。更改 requestedTimeout 会冲突。
- requestedTimeout=null：兼容原绝对期限语义，deadline 进入摘要；重复提交必须保留同一个绝对期限。需要跨新身份／新期限重放的入口应显式使用相对时长。
- 摘要固定发布、归属及权限上限、预算、能力／绑定版本、实际文本、生成选项、容量和会话版本／分支语义。排除新 executionId、messageId、Turn ID、snapshotId、traceId、grant 刷新引用及组装时间。
- 不在 ChatService 先拒绝“当前会话版本已变化”的请求；同键同摘要先返回原身份，真正的新请求由数据库版本和活跃调用门闩仲裁。

## 4. 字节存储

ContextSnapshot 和封闭 InvocationResult 已注册到隔离 Jackson 白名单编码器。对象键、精确数字规范化后保存 UTF-8 JSON 及 SHA-256；读取验证归属、固定别名／版本、内容摘要和结果完整性。

上下文按 owner＋snapshotId 防重。结果按 owner＋invocationId＋attemptId＋resultKey 防重，写入前核对实际调用和尝试归属，并限制编码后的 UTF-8 字节数不超过调用的 maxOutputBytes；同键异内容拒绝，不覆盖已经保存的字节。ResultRef.resultId 是存储分配的操作作用域哈希，ModelResult.resultId 是逻辑结果 ID。

字节事务和执行权威事务分开；结果字节先保存，再提交终态引用。受理／终态失败可能留下孤立字节，不能据此返回成功；孤立数据回收和保留任务后续接入，当前不自动删除或裁剪。构造对象、快照写入和 ACK 都不代表模型执行完成。

## 5. 验证与后续

从项目根目录运行：

```bash
mvn -o -f backend/pom.xml -pl arte-ai -am \
  -Dmaven.compiler.proc=full -Dmaven.compiler.release=21 \
  -Dslf4j.provider=ch.qos.logback.classic.spi.LogbackServiceProvider \
  '-Dtest=com.arte.ainew.admission.*Test,com.arte.ainew.persistence.*Test,com.arte.ainew.contract.*Test,com.arte.ainew.context.*Test,com.arte.core.interceptor.*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

测试覆盖跨实例受理及会话创建竞争、版本推进后的重放、改变输入／期限的冲突、跨主体拒绝、真实事务回滚、字节完整性、账本不重置、Spring 配置装配，以及异常结果码和跨线程国际化响应。本次 109 项相关测试与 arte-app 编译通过。实际 MySQL 部署仍须单独验证，本次测试使用 H2 MySQL 模式。

第 4 步的单个供应商适配、协议交换、受控连接和 ModelGateway 已实现，见 [单次模型交互](GENERATION_IMPLEMENTATION.md)。后续实现第 5～6 步的 Worker／dispatch、Attempt 及预算生命周期、输出与终态提交、状态／结果服务和完整生成测试。目前 dispatch／reconcile／control 明确返回未启用错误；不会自行启动调用或伪造完成。
