# 工作空间目录第一批基线与验收（2026-10-14）

## 结论

第一批完成设计契约和时间回拨修复，尚未更换物理时间列或公开分页接口。当前第一页请求仍完整扫描候选； 101／1,001／10,001 个候选分别产生
27／117／1,017 次 SQL 和 2／20／200 次 Redis 读写。 可见比例降为 10% 时，这些次数不减少。下一批优先处理完整计数成本、内部 Redis
续扫和重复事实读取。

这是一条服务级、合成数据、同机容器基线，不是 HTTP／前端性能测试，也不是生产容量或 SLO 结论。

## 环境和方法

- JDK 21.0.8；专用临时 MySQL 8.4.11／Redis 7.4.9，同机 Docker，容器没有额外 CPU／内存配额。
- 真实 Mapper、公共服务、RBAC 身份读取及 Redis 游标；Druid 池预建 8 条连接，8 个工作线程，每工作线程队列上限 128。
- 3 档合成空间数（100／1,000／10,000），另有 1 个初始化默认空间，实际候选数为 101／1,001／10,001。
- 单租户／单主体，默认政策，种子成员的 100%／10% 有效，其余为已撤销成员；默认空间保持有效。 创建时间包含同时间并列，ID 使用短
  ASCII 合成值；不代表多租户、最长公开 ID、复杂政策或不断变化的成员关系。
- 并发 1／8／32，每场景 2 次串行预热、32 个测量请求，合计 18 场景／576 个业务断言成功的请求。 请求固定第一页、size=20；并发场景为每波最多
  concurrency 个请求，下一波等待当前波完成，不是恒定到达率负载。
- 专用日志配置关闭 SQL DEBUG。每个请求统计所有经测量 DataSource 执行的 SQL、候选批次数／返回行数、Redis 游标
  读写、连接获取和调度排队。工作线程分配量使用 ThreadMXBean，包含测量代理开销， **不是请求结束时存活堆大小**。
- 分位数为本组最近秩估计，只有 32 个样本，尾部易受 JIT／GC／宿主其他容器活动影响。各阶段有重叠，不相加分摊延迟。
- SQL 执行时间包括驱动、传输及数据库等待；候选拉取行数不是 MySQL rows_examined。没有对比实现，不宣称提速比例。

正式原始样本：[baseline.json](measurements/workspace-batch1-2026-10-14/baseline.json)。
汇总：[summary.json](measurements/workspace-batch1-2026-10-14/summary.json)。
早一轮与独立数据库回归重叠的记录保留在 [baseline-contended.json](measurements/workspace-batch1-2026-10-14/baseline-contended.json)，
该轮排除在正式结论之外。正式重测期间没有并行运行本次回归任务，但宿主已有其他应用容器，不能视为独占硬件测量。

## 正式结果

以下表由原始样本生成。Redis 次数包含读与写；分配量为每请求工作线程的中位分配字节。

| 候选数 | 种子可见比例 | 并发 | 中位 ms | P95 ms | 排队 P95 ms | SQL/请求 | Redis/请求 | 分配 MiB/请求 |
|-------:|-------------:|-----:|--------:|-------:|------------:|---------:|-----------:|--------------:|
|    101 |         100% |    1 |    20.9 |   28.4 |         0.1 |       27 |          2 |           9.2 |
|    101 |         100% |    8 |    32.5 |   44.1 |         0.1 |       27 |          2 |           8.9 |
|    101 |         100% |   32 |    84.4 |  138.2 |       103.0 |       27 |          2 |           8.9 |
|    101 |          10% |    1 |    16.1 |   23.1 |         0.1 |       27 |          2 |           8.7 |
|    101 |          10% |    8 |    26.4 |   28.8 |         0.1 |       27 |          2 |           8.7 |
|    101 |          10% |   32 |    77.9 |  119.3 |        94.1 |       27 |          2 |           8.7 |
|  1,001 |         100% |    1 |   103.1 |  151.8 |         0.1 |      117 |         20 |          81.5 |
|  1,001 |         100% |    8 |   165.9 |  168.0 |         0.2 |      117 |         20 |          81.5 |
|  1,001 |         100% |   32 |   431.9 | 1001.3 |       524.1 |      117 |         20 |          81.5 |
|  1,001 |          10% |    1 |   111.3 |  472.9 |         0.1 |      117 |         20 |          82.2 |
|  1,001 |          10% |    8 |   159.7 |  168.1 |         0.0 |      117 |         20 |          82.2 |
|  1,001 |          10% |   32 |   387.1 |  631.3 |       474.5 |      117 |         20 |          82.2 |
| 10,001 |         100% |    1 |  1096.4 | 2928.3 |         0.1 |     1017 |        200 |         810.6 |
| 10,001 |         100% |    8 |  1415.6 | 1562.1 |         0.1 |     1017 |        200 |         810.5 |
| 10,001 |         100% |   32 |  6414.3 | 8629.5 |      7165.3 |     1017 |        200 |         810.5 |
| 10,001 |          10% |    1 |  1139.5 | 2475.2 |         0.5 |     1017 |        200 |         818.0 |
| 10,001 |          10% |    8 |  1458.1 | 4384.3 |         0.2 |     1017 |        200 |         818.0 |
| 10,001 |          10% |   32 |  3546.2 | 8213.3 |      4320.9 |     1017 |        200 |         818.0 |

## 查询结构与后续决策

正式执行计划使用 `ix_platform_workspace_member_scan` 扫描成员、空间 PRIMARY 做 eq_ref 查找，没有显示 filesort。
即使第一页命中既有索引，精确 total 仍使 10,001 个候选产生 101 批候选查询、10,101 个拉取行（含每批额外预取） 和 1,017 次
SQL；不能把本次成本简单归因为缺少索引。

原始 JSON 还保存最终数据集的 InnoDB 索引页数。EXPLAIN 的 key_len 是索引访问允许的键长度，不是每条记录实际占用。 当前使用短合成
ID，本批没有足够证据要求迁移所有内部键；需要更长 ID、多租户及实际行宽的对照测量后再决定。

下一批验收应复用相同规模／可见性／并发矩阵，重点比较 SQL／Redis 次数、扫描预算、工作线程分配量和排队。 尤其验证低可见比例时空页可续扫，不能用错误的
exhausted 或候选 total 换取看似较快的响应。

## 时间正确性与回归

- 核心空间／平台契约回归 **167 项通过**，包含新增 5 项业务时间测试：慢节点改名、慢节点新建空间的父时间、两级政策、
  命令执行中回拨、回放不重复副作用及现有纳秒精度保留。
- 独立真实 MySQL 持久化回归 **25 项通过**，包含新增时间 CAS 防线，验证正确版本下较早的时间仍不能写入。
- 正式基线测试 1 项通过，内部 576 个请求全部通过 total／records 断言。
- `git diff --check` 通过。

扩大验证遇到的现有问题单独记录：

1. 普通 app 全量测试编译中，`NewAiRequestObservationTest` 调用 `NewAiJdbcObservationFilter.mysqlId`，但方法为 private。
   使用专用 workspace-baseline profile 完成相关测试，不改变普通构建配置，也不修复无关诊断代码。
2. 包边界测试因已有 `PublicTransactionRunner.readOnly` 默认 UnsupportedOperationException 被通用文本规则拒绝；
   相同内容存在于本批开始前的 HEAD。相关 167 项命令不包含这一测试，不能因此宣布全量套件通过。
3. core 全量运行在既有 PdfUtilTest 处导致测试 JVM 退出，exit=134。尚未定位原因，不将其解释为本批业务时间失败。

## 复测命令

在专用测试环境使用项目既有测试账户 root／platform-test。夹具自动创建随机 `platform_test_*` 库并在结束时删除， Redis
使用随机前缀；禁止把下面连接指向业务实例。基线 test 默认禁用，须显式开启。

```sh
# 从 backend 目录运行；先启动仅绑定本机的临时测试实例。
docker run -d --rm --name arte-workspace-baseline-mysql-20261014 \
  -p 127.0.0.1:13326:3306 -e MYSQL_ROOT_PASSWORD=platform-test mysql:8.4
docker run -d --rm --name arte-workspace-baseline-redis-20261014 \
  -p 127.0.0.1:16399:6379 redis:7.4-alpine
docker exec arte-workspace-baseline-mysql-20261014 mysqladmin -uroot -pplatform-test ping

mvn -o -Pworkspace-baseline -pl arte-app -am \
  -Dtest=WorkspaceDirectoryBaselineTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dplatform.workspace.baseline=true \
  '-Dplatform.test.mysql=jdbc:mysql://127.0.0.1:13326/mysql?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true' \
  -Dplatform.test.redis=redis://127.0.0.1:16399 \
  -Dplatform.workspace.baseline.output=target/workspace-directory-baseline.json test

python3 arte-app/scripts/summarize_workspace_baseline.py \
  arte-app/target/workspace-directory-baseline.json \
  --output arte-app/target/workspace-directory-summary.json

mvn -o -Pworkspace-baseline -pl arte-app -am \
  '-Dtest=com.arte.core.application.workspace.*Test,PlatformContractTest,PlatformEnumSerializationTest,PlatformPersistenceIntegrationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false \
  '-Dplatform.test.mysql=jdbc:mysql://127.0.0.1:13326/mysql?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true' test

docker stop arte-workspace-baseline-mysql-20261014 arte-workspace-baseline-redis-20261014
```

使用 JDK 21；确认 ping 成功再测量。可通过 `platform.workspace.baseline.sizes`（100 的倍数）、`requests`（至少 32）和
`workers` 改变矩阵；修改后不得与原环境样本混为同一对照。
