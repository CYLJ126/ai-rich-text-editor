# AI TOOL 首次使用与 S2-09 验收

日期：2026-10-19。基线：[需求及设计](../../ARTE%20AI%20TOOL%20需求及设计.md)、[实施记录 §17](TOOL_IMPLEMENTATION.md#17-s2-09正式宿主接线与本机集成验收)。

本轮按用户确认限定为 **本机集成验收和性能基线**。正式参考环境双节点、规定数据量和 30 分钟 W1—W4／PF-01—08 留待上线前验收；本机通过不解除需求
§15.1 的生产开放门槛。

## 1. 正式交付的工具

| 项目         | 内容                                                                                                   |
|--------------|--------------------------------------------------------------------------------------------------------|
| 固定引用     | `{ "type": "tool", "id": "text.countCodePoints", "version": "v1" }`                                    |
| 名称／提供者 | 文本字符计数／arte.local-text@v1                                                                       |
| 参数／配置   | `{ "text": "A😀中\n" }`／`{}`                                                                          |
| 输出         | `{ "codePoints": 4 }`，接口以 outputJson 原文返回                                                      |
| 语义         | Unicode 码点，包含空格和换行；e＋组合音符为 2，家庭组合表情为 7，空文本为 0                            |
| 边界         | Schema 最多 65536 码点；还须满足服务器／工具／绑定的有效 JSON 字节上限，默认服务器上限 65536 字节      |
| 固定工具策略 | 只读、最多 5 秒、1 次尝试、工具输入上限 262144 字节／输出 4096 字节、无审批；有效上限可进一步收紧      |
| 费用／资源   | 明确受信 FREE，预算 NOT_RESERVED；不创建费用账户或 reservation，不调用模型、不访问网络／文件／业务资源 |

实现位于 arte-app 的 `platform/ai/tool/LocalTextTools`，登记来源和实际算法一起提供，不注册测试适配器。免费授权比较完整固定契约、本人绑定及空配置／无
Secret／无网络条件；新增工具必须独立接入资源授权和费用策略，不能因只读就免费放行。

受理和执行使用 `PlatformToolExecutionAccess`，共享 `PlatformToolManagementAccess` 的当前平台门控。提交及同键重放在原物理写事务中复核，Worker
和网关执行边界重新检查；稳定身份仍映射到现有 RBAC 账户，当前账户主键／名称／创建时间、租户／空间／成员／政策、AI 开关和 aiTool:
invoke 都须有效。

## 2. 业务环境部署前准备

本轮没有连接或改动 app.properties 指定的业务数据库、业务 Redis，没有执行正式授权或菜单授予。

1. 先备份并核对现有结构。全新 New-AI 库使用 `scripts/arte-ai-new-ddl-mysql.sql`，不要再重复执行 TOOL 增量。
2. 已有库按实际版本补齐：S1-04 `arte-ai-tool-catalog-migration.sql` → S1-06 `arte-ai-tool-release-migration.sql` → S1-07
   `arte-ai-tool-binding-migration.sql` → S1-08 `arte-ai-tool-management-migration.sql` → S2-03
   `arte-ai-tool-admission-migration.sql` → S2-06 `arte-ai-dispatch-scheduling-migration.sql`。此前的 New-AI
   平台、受理、身份、空间、AI 控制及 dispatch-limits 基础仍须已部署；不能以 TOOL 脚本代替。客户端遇错停止，禁止 `--force`。
3. S2-06 前停止新受理和全部旧消费者，完成／核对并排空未确认 DISPATCH；脚本自带拒绝未排空的检查。所有消费者统一版本、槽位、分类限额和清单，不能混跑旧模式。
4. 以既有 RBAC 流程登记 TOOL 操作及菜单：arte-app/scripts 下 `arte-ai-tool-rbac-dml-mysql.sql`、
   `arte-ai-tool-menu-dml-mysql.sql`。脚本不自动授予用户／角色；核对已有同码／同路径及停用状态后，再显式授予 `/AI/Tools`
   菜单、aiTool:list／invoke。发布管理员还需 aiTool:manage 和 aiConfiguration:manage。
5. 使用真实本人工作空间，在现有平台配置中启用 AI 使用资格；免费文本工具不要求为此新增模型、费率或预算。admin 同样不能绕过真实账户、空间和
   AI 资格。

## 3. app.properties 配置

```properties
arte.ai-new.enabled=true
arte.ai-new.data-source-bean=appDataSource
arte.ai-new-tool.enabled=true
arte.ai-new-tool.discovery.source-timeout=10s
arte.ai-new-tool.discovery.refresh-on-startup=true
arte.ai-new-execution.enabled=true
arte.ai-new-execution.worker-enabled=true
arte.ai-new-execution.concurrency=6
arte.ai-new-execution.scheduling.enabled=true
arte.ai-new-execution.scheduling.slots.generation=2
arte.ai-new-execution.scheduling.slots.tool-short=2
arte.ai-new-execution.scheduling.slots.tool-long=1
arte.ai-new-execution.scheduling.slots.outcome-check=1
arte.ai-new-execution.scheduling.tool-manifest=331756c89f707d42a1142275e1be4d4711b6ef76ef6ac7df4b996ce463cd6569
```

上述摘要仅适用于本版唯一的正式文本工具完整清单；增加／更改固定来源后须重新评审和计算，不能照抄旧值或填写工具
ID、revision、任意字符串。`LocalTextTools.manifest()` 与实际发现共用唯一算法，健康刷新回执 nodeChecksum 应与其一致。身份／空间／AI
配置模式沿用现有项目配置，不为 TOOL 新建身份系统。当前本机被忽略的 app.properties
已填入启动发现开关和此清单；common.properties／app.properties.example 的 TOOL、启动发现和分类调度默认仍关闭。

**不再需要先启动取值再填值重启**：本版预期值已知，可先配置再启动。显式启动发现由 SmartLifecycle 在 Worker 之前执行，最多等待
30 秒，发现失败终止启动；只写不可变部署事实及本节点健康视图，不执行 DDL、不自动发布／建绑／授权、不伪造管理员命令回执。所有
Worker 节点都需健康发现，不能只刷新负载均衡命中的一个节点。关闭启动发现时仍可使用显式管理刷新，但须逐节点完成并核对。

分类池已有固定配置记录时，改变策略或清单须停受理、停消费者并排空工作／预留／许可，按 §14 运维流程处理 pool
固定记录后统一重启；应用不自动清表或降低资格。多节点事件传输继续按既有方式配置 Redis；单机可使用 local。工具调用本身不依赖
Redis 计费，也不自动更改聊天配置。

## 4. 首次发现、发布和试用

启动发现成功只表示发现事实已登记，工具尚未发布。当前 `/AI/Tools` 页面用于目录、个人配置和调用，发布仍使用管理 API。

以真实管理员会话调用 `POST /arte/ai-new/tool/refreshLocalCatalog`，正文
`{ "scope": { "tenantId": "实际租户", "workspaceId": "实际空间" }, "reason": "核对本次部署清单" }`，请求头使用独立
Idempotency-Key。应看到 nodeDiscoveryHealthy=true、nodeChecksum 与上值一致。多节点按节点管理地址核对；手动刷新额外有真实管理授权、审计和幂等回执。

首次未发布时调用 `POST /arte/ai-new/tool/publishTool`，使用独立 Idempotency-Key，正文：

```json
{
  "scope": { "tenantId": "实际租户", "workspaceId": "实际空间" },
  "tool": { "type": "tool", "id": "text.countCodePoints", "version": "v1" },
  "expectedVersion": "0",
  "reason": "发布已评审的本地文本字符计数工具",
  "review": {
    "examples": [{ "input": { "text": "A😀中" }, "output": { "codePoints": 3 } }],
    "riskEvidence": "仅处理本次文本；无文件、网络、模型或业务资源访问；固定本地免费策略",
    "minimumApproval": "NONE"
  }
}
```

已发布对象先读取当前状态及版本，不重复用初始版本覆盖。超时或回执丢失保留原正文／原键确认；不要换键猜测是否已发布。

普通使用者进入现有 AI 工具菜单：目录 → 文本字符计数详情 → **准备默认配置**（空对象，无必填配置）→ 关闭详情 → 本人配置中点
**调用工具** → 输入 `A😀中` → **预览调用** → 核对 FREE、版本及限制 → **确认并执行工具**。应显示 SUCCEEDED、NOT_RESERVED 和
`{ "codePoints": 3 }`。刷新保留原调用编号，不能新增执行；输入变化须重新预览。浏览器关闭或切空间不等于服务端取消，停止须用明确的停止命令。

## 5. 本轮证据和边界

| 验证层           | 覆盖                                                                                                                                              | 不代表                                               |
|------------------|---------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------|
| 正式宿主集成     | Spring 正式配置、MVC、当前平台 RBAC／空间／AI、真实文本工具、免费零预算、同键重复提交、输入／空间拒绝、撤权、生命周期变化、取消、关闭后新容器恢复 | 完整 arte-app 登录／JWT 签名过滤链或所有业务工具     |
| 独立 MySQL       | 全量 DDL、双 Worker 竞争、耐久结果及测试库清理；TOOL 增量链与全量的列、索引、外键一致性                                                           | 带历史业务数据／所有旧版组合升级、跨主机故障         |
| 独立 Redis       | 原事件传输／HTTP 集成回归中此前需 Redis 的 4 项                                                                                                   | 业务 Redis 部署、跨主机浏览器广播                    |
| 既有 H2 故障矩阵 | 输出失败、终态事务回滚、ACK 丢失、租约失效、重复派发、UNKNOWN 不重做、分类隔离及公平性                                                            | 真实进程 kill -9、数据库／Redis 网络分区下的完整矩阵 |
| 浏览器           | 正式组件、固定测试登录会话、当前平台权限、独立 MySQL、正式工具、目录到调用结果及刷新、390px                                                       | 正式菜单授予、真实登录验收或完整 Umi 生产构建        |

### 本机性能基线

macOS、JDK 21.0.8；Docker Linux aarch64 共用 8 vCPU／约 7.75 GiB，MySQL 8.4.11（buffer pool 128
MiB、flush_log_at_trx_commit=1、REPEATABLE-READ），Redis 7.4.9。单 JVM、DriverManagerDataSource 每次建连接、空随机测试库；文本为
500 码点、无模型或外部调用。首次样本单列，10 次预热后串行 50 次，nearest-rank 分位数；并非并发吞吐测试。

| 路径                 | 首次 ms | P50 ms | P95 ms |  P99 ms |
|----------------------|--------:|-------:|-------:|--------:|
| 预览                 |   92.38 |  34.44 | 162.37 |  246.25 |
| 耐久受理             |  171.72 |  73.58 | 231.15 |  632.28 |
| 状态查询             |   45.02 |  23.19 | 150.21 |  278.35 |
| 受理开始至终态可查询 |  455.97 | 235.83 | 992.79 | 1931.98 |

每条路径包含真实 MVC、平台当前授权和 SQL；未包含 JWT 过滤／浏览器网络。端到端主动推进 Worker，不含生产
poll-interval；包含领取／工具／提交及一次状态查询。端到端尾延迟尚高，不能据此宣称“高性能”或 PF-03 达标，也不能把受理／查询的表面分位数当作
PF-01／02 正式通过。未准备 1000 工具、30 万调用、100 万事件、30 分钟负载或参考环境两台应用节点；两 Worker 只是在同 JVM 下使用独立
workerId／存储适配实例共享物理库。

### 复现命令

仅在独立临时 MySQL／Redis 执行，勿指向业务环境。测试使用 root／platform-test 的一次性容器凭据，随机创建 platform_test_*
库并在结束时删除。JDK 21；以下端口替换成临时容器实际 loopback 映射：

```sh
mvn -o -f backend/pom.xml -pl arte-app -am -Pdev,workspace-baseline \
  -Dmaven.compiler.proc=full -Dmaven.compiler.release=21 \
  '-Dtest=PlatformToolExecutionIntegrationTest,ToolMySqlMigrationAcceptance,ToolLocalBaseline' \
  '-Dplatform.test.mysql=jdbc:mysql://127.0.0.1:49739/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

基线原始 JSON 写入 arte-app/target/tool-s209-baseline.json。兼容回归沿用实施记录 §13，排除已知基线
ProfileConfigurationTest 四项及显式 ToolRunBrowserAcceptanceTest 页面桥，追加平台身份和 S2-09 集成测试；设置
`-Darte.ai-new.test.redis-address=redis://127.0.0.1:64872` 覆盖隔离 Redis 项，最终后端选定兼容集 858 项通过、零跳过，详见实施记录
§17。没有运行全仓测试。

浏览器桥仅显式启动，15 分钟有界，独立 MySQL 数据随结束清理：

```sh
mvn -o -f backend/pom.xml -pl arte-app -am -Pdev,workspace-baseline \
  -Dmaven.compiler.proc=full -Dmaven.compiler.release=21 \
  '-Dtest=ToolPlatformBrowserAcceptance#browserBridge' \
  '-Dplatform.test.mysql=jdbc:mysql://127.0.0.1:49739/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC' \
  -Dsurefire.failIfNoSpecifiedTests=false test
# 第二个终端在 frontend：
node scripts/tool-s209-browser/server.mjs
# 浏览器 http://127.0.0.1:5219；结束后在仓库根停止桥，再停止 Vite：
touch backend/arte-app/target/tool-platform-browser-stop
```

桥只监听 127.0.0.1:15219，限定允许的 TOOL／Invocation 路由并注入固定测试会话；不在默认测试集启动。前端仅替换宿主认证传输，使用实际
ToolWorkspace／ToolRunPanel；运行时从桥读取随机测试
scope，避免伪造固定业务空间。截图见 [页面验收](../../frontend/src/pages/AI/Tools/ACCEPTANCE.md)。

## 6. 未通过项与后续工作

- **正式性能／容量待验**：按需求 §14.5 固定资源、数据规模、双应用进程和 30 分钟 W1—W4，保留 JVM、SQL
  索引、连接池、资源余量及逐窗口分位数；若尾延迟不达标，先定位事务／授权／领取／提交／网络分项，再调整，不降低原门槛。
- **正式部署待办**：业务环境备份、增量、RBAC／菜单显式授予、空间 AI 资格、统一节点配置、真实登录页面试用及回退演练。本轮容器测试不替用户执行业务环境发布。
- **完整故障演练待验**：两独立应用进程的强杀、重连、租约到期、数据库／Redis 暂停／分区和持续恢复；同时确认原 GENERATION
  事件和预算路径。
- 审批、写操作、远端、付费工具及聊天编排仍按 S3／S4／S5 独立实现，不在本批通过范围。文本工具明确 FREE，不建立第二套预算；付费和
  Run 联动时复用统一预算底座，设计中的预算扩展时序不变。
