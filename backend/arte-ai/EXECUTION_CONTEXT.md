# ExecutionContext 接入说明

**TODO**

待迁移。

实现暂放在 `com.arte.ainew`，保持与旧 AI 链路隔离。`common.execution` 是不依赖 Spring、Reactor 或 AI SDK 的共用数据契约，未来可迁移公共契约模块。现有 `UserContext`、认证过滤器、旧聊天和 SSE 控制器均未修改。

## 数据与运行对象

- `ExecutionPrincipal`：稳定主体 ID、名称及 USER／SERVICE 类型，不包含密码、Token 或在线会话对象。
- `ExecutionAuthorization`：已验证的租户、空间、权限上限和可重新校验的授权引用；集合防御性复制。
- `ExecutionContext`：执行 ID、traceId、授权、绝对 deadline、父执行、预算／发布引用和操作幂等键。可持久化，子执行不能扩大权限或延长期限。
- `ExecutionRuntimeContext`：上述数据及进程内 `ExecutionCancellation`。运行对象不能持久化。

权限快照只限定任务上限，不是永久授权。资源读取、资料外发、写入等关键边界仍应验证当前权限。预算／发布引用和幂等键分别由对应服务验证，构建上下文不等于可靠受理或已取得执行租约。

## 可信入口构建

显式注册 `ExecutionContextFactory`，传入 `Clock` 和 `ExecutionAuthorizationResolver`。解析器由身份／空间模块或组合模块实现：根据认证后的名称解析稳定主体 ID，验证当前 Tenant／Workspace 归属、请求的任务权限并返回授权引用。不能使用复制请求体的空实现；尚无权威空间实现时不启用入口。

工厂不自动注册 Bean，不修改旧 Spring Security 配置。

```java
ExecutionContextFactory factory = new ExecutionContextFactory(resolver, Clock.systemUTC());

// MVC：必须在请求线程调用，立即捕获已认证名称，再异步解析空间授权。
// options 由服务端构造，timeout 按平台上限限制；引用不能直接相信客户端。
Mono<ExecutionContext> execution = factory.createCurrent(options);

// WebFlux：从当前订阅的 ReactiveSecurityContextHolder 读取身份。
Mono<ExecutionContext> reactiveExecution = factory.createReactive(options);
```

匿名或未认证身份返回 Spring Security 的 `AuthenticationCredentialsNotFoundException`；解析为空或解析后的身份／空间／权限范围不一致返回 `AccessDeniedException`。授权等待计入总 deadline。一次 MVC `create` 调用固定执行 ID 和期限，重复订阅不生成新身份；幂等受理仍由执行存储保证。WebFlux 工厂按订阅读取身份，入口应避免重复订阅命令。

## 响应式传播

```java
return factory.createCurrent(options).flatMap(execution -> {
    ExecutionRuntimeContext runtime = ExecutionRuntimeContext.start(execution);
    Mono<Result> work = Mono.deferContextual(view -> {
        ExecutionContext ctx = ReactiveExecutionContext.require(view).execution();
        return applicationService.execute(command, ctx); // 核心服务显式传递 ctx
    });
    return ReactiveExecutionContext.withContext(
            ReactiveExecutionContext.guard(work, clock), runtime);
});
```

`withContext` 只添加自己的键，保留 SecurityContext 等其他键；`current()` 可在 `publishOn`／`subscribeOn` 后读取当前订阅上下文。写入应置于需要读取上下文的整个链路下游，嵌套链路可以设置自己的上下文并在返回父链路后恢复。

缺失上下文直接失败，不回退到旧 `UserContext`、默认 system 用户或全局变量。不安装全局 Reactor Hook。不要在内部独立 `subscribe()`，也不要将含用户身份的结果通过 `cache()`／`share()` 跨主体复用。

普通异步 API 应显式携带不可变数据，不能在其他线程里调用 `current().block()` 寻找调用者身份：

```java
return Mono.deferContextual(view -> {
    ExecutionContext ctx = ReactiveExecutionContext.require(view).execution();
    return Mono.fromCompletionStage(() -> asyncService.execute(command, ctx));
});
```

## 旧阻塞服务适配

```java
// 由组合配置创建并关闭；线程数、队列上限应按连接池容量与负载确定。
Scheduler legacyIo = Schedulers.newBoundedElastic(8, 64, "ainew-legacy-io");
BlockingExecutionBridge bridge = new BlockingExecutionBridge(legacyIo, clock);

Mono<Article> read = bridge.call(ctx -> legacyArticleService.read(articleId));
return ReactiveExecutionContext.withContext(read, runtime);
```

桥接器延迟调用，在专用执行资源上检查期限／取消，临时映射 `UserContext` 和 MDC traceId；正常返回、异常和响应式取消后，只要同步操作退出，作用域就会恢复原值。嵌套作用域必须按相反顺序关闭且不能跨线程关闭。

整个同步服务及事务都必须在 callback 内执行；不能在其中返回 Publisher、启动未来工作或切换事务线程。旧 ID 当前是正整数；SERVICE 和无法映射的主体明确拒绝。桥接器只填 ID／名称，不复制菜单、角色、Token 或密码，因此仅适用于按身份重新校验资源权限的旧服务；依赖菜单权限的旧服务需要独立的当前授权适配。

取消订阅或中断线程不保证旧数据库／远端操作已经停止或回滚，不能据此认定副作用未发生。任务占用的资源在真实操作退出前仍需纳入执行配额。

## 取消、期限与 Worker 重建

```java
runtime.cancellation().cancel("用户请求停止");

// persisted 只能来自可信执行存储；先验证 Worker 身份、任务租约及记录完整性。
return factory.restore(persisted).map(ExecutionRuntimeContext::start);
```

`guard` 在执行器订阅边界使用，超出绝对 deadline 返回 `TimeoutException`，显式取消返回 `CancellationException`；持续输出不会延长总期限。第一次取消生效，重复取消不覆盖原因，晚订阅者可收到取消信号。

取消 HTTP／SSE 观看订阅不会调用 `cancel`。观看流使用自己的访问上下文与连接生命周期，不应绑定生成任务的运行对象。这里的取消对象只是进程内信号：跨实例取消、实际取消完成、节点接管和结果未知仍由执行控制与存储契约管理。

Worker `restore` 保留执行身份、关联、幂等键和总期限，重新解析当前授权；主体 ID／类型变化、授权撤销或期限已过均拒绝。随后创建新的运行对象，并按持久化控制记录恢复取消意图。不要把“新的本地取消对象尚未取消”当作持久化任务仍可执行的证据，也不要因重建上下文而重发结果未知的外部操作。

## 验证

从 `backend` 运行新增测试：

```bash
mvn -o -pl arte-ai -am \
  -Dmaven.compiler.proc=full \
  -Dmaven.compiler.release=21 \
  -Dslf4j.provider=ch.qos.logback.classic.spi.LogbackServiceProvider \
  '-Dtest=com.arte.ainew.context.*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

本机 JDK 24 默认不自动发现 Lombok 注解处理器，命令显式开启处理。现有测试 classpath 同时有 Logback、Log4j2 SLF4J provider 及 Log4j→SLF4J 桥，默认选择会导致 MDC 递归；命令仅为这次测试选择 Logback，未改变生产日志依赖或配置。该环境问题仍需单独统一日志依赖。

测试覆盖跨调度器与并发身份隔离、其他 Context 键保留、嵌套作用域、空身份拒绝、空间和权限一致性、子执行约束、可持久化数据往返、Worker 重新授权、期限／取消，以及旧阻塞线程复用与异常／取消后的清理。
