# 工作空间目录第三批交付（2026-10-14）

## 第 7 步：微秒存储与开发数据调整

租户与空间的创建／更新时间改为 `created_at`、`updated_at` UTC `DATETIME(6)`，成员的不可变创建键副本改为
`workspace_created_at`。同步调整 PO/DTO、Mapper 映射、CAS、防倒退条件、候选投影、排序索引和联合外键。 扫描仍按
`(createdAt DESC, tenantId ASC, workspaceId ASC)`，微秒截断造成的时间并列由完整 scope 打破。

`PlatformTimePrecision` 提供显式微秒截断与存储精度校验；租户／空间模型在构造边界规范化。
`PlatformTimes.businessNow` 用于新元数据、命令受理／完成及事件／审计的生成。 更新时间仍在治理锁内取
max，允许同一微秒内时间相等且版本递增；事件／审计共用同一次未钳制的墙钟观测值的微秒表示。 原始墙钟读取、外部身份创建时间、授权／邀请期限及
Outbox 数据库租约不统一截断。

app 通过 `PlatformUtcTimes` 将 Instant 显式映射为 UTC LocalDateTime，检查微秒精度与 MySQL 1000～9999 年范围。 JDBC 不使用默认时区的
Timestamp 进行元数据转换，也不依赖数据库隐式舍入。 新回执插入、新事件和审计追加均校验微秒；历史 JSON 解码与回执重放不截断。
历史回执、Outbox JSON、事件发生时间及审计不重写；审计／Outbox 原秒／纳秒列为保真历史值继续保留。
这是有意保留的历史兼容边界，不能将这些列当作尚未迁移的空间排序键。

公开游标版本与 Redis 前缀升级为 v3，微秒内部边界拒绝非规范精度。旧 v2 token 返回 410，前端原有重新加载流程继续适用。
新建库使用更新后的 `arte-platform-workspace-ddl-mysql.sql`；已有开发库使用
[增量迁移脚本](../scripts/arte-platform-workspace-time-micros-migration-mysql.sql)。

脚本先检查范围、纳秒值和父子创建键，再增加新列、显式 `nano DIV 1000` 回填，安装新完整性约束，最后移除旧列及索引。
全程保持外键检查开启。采用普通 DDL 与 PREPARE，不需要存储过程权限；错误诊断通过 `ARTE_TIME_STOP_*` 明确停止后续语句。 DDL
不是跨表原子事务，须停止旧版写入、备份并遇错停止，不能使用 mysql --force；故障后关闭会话释放迁移锁，再修复并重跑。
已验证重复运行及部分新增列后的恢复；部署切换后不能再次启动旧版空间写入。

时间精度及驱动映射依据：[MySQL 分数秒](https://dev.mysql.com/doc/refman/8.0/en/fractional-seconds.html)、
[Connector/J 时间映射](https://dev.mysql.com/doc/connectors/en/connector-j-time-instants.html)。
迁移事务边界依据：[DDL 隐式提交](https://dev.mysql.com/doc/refman/8.4/en/implicit-commit.html)。

### arte 开发库实际执行

按用户指定的 arte 库与项目现有 app 连接执行。迁移前停止本地调试后端，备份六张相关表的结构及数据。 首次存储过程版被应用账号的
ALTER ROUTINE 权限阻止，未修改表结构；随后直接 DDL 版经隔离验证后成功执行。 没有扩大数据库账号权限，没有清空或重建业务记录。

| 对象        | 迁移前 | 迁移后 |
|-------------|-------:|-------:|
| 租户        |      1 |      1 |
| 空间        |      3 |      3 |
| 空间成员    |      3 |      3 |
| 历史回执    |      6 |      6 |
| Outbox 事件 |      8 |      8 |
| 审计        |      8 |      8 |

五个元数据／成员键列最终均为 DATETIME (6)。回执、事件、审计按原始字段内容计算 SHA-256，迁移前后保持一致。 本机备份位于
`backend/arte-app/target/backups/workspace-time-2026-10-14-direct/`，受 Git 忽略，不提交实际开发数据；
`verified.txt` 保存计数及历史完整性验收结果。备份为逐表原始结构和 INSERT 数据，恢复须在停写状态按依赖处理；不要直接在新结构上导入旧结构。

本地新版应用包已在 12636 端口启动，连接原 arte 库；无认证目录请求被现有认证处理器拒绝。 IDE 首次调试启动受到既有测试编译错误影响，曾使用本次构建的
boot jar 恢复服务，日志位于
`backend/arte-app/target/workspace-batch3-dev-app.log`。最终已确认原 AIRichTextEditorApplication 调试会话恢复运行，12636
端口正常监听。 另一个同库容器未显式启用平台空间功能，未重启或重新部署。

## 第 8 步：整体验收

- 核心空间／平台回归 178 项通过，涵盖原有门控、当前读、幂等、回拨及分页逻辑，新增纪元前纳秒截断、同微秒版本推进与历史回执原精度重放。
- app 专项回归 87 项通过，包含真实 MySQL／Redis、Spring 装配、HTTP、响应式调用、实体映射、审计回滚及游标。 迁移测试 4
  项涵盖时间并列完整续扫、纪元前时间、不同 JVM／数据库会话时区、历史原文不变、脚本重跑、部分新增列恢复和非法旧值拒绝。 Redis
  验证跨实例 v3 导航、旧前缀及误放在新前缀的旧版本失效；成员创建键外键和数据库时间检查约束仍拒绝不一致值。
- 前端空间状态、选择器、服务及聊天集成 60 项通过，覆盖空页续扫、搜索、过期恢复、选中空间及迟到响应。
- 应用生产包构建通过，IDE 对应用入口的定向构建通过，git diff --check 通过。临时 MySQL／Redis 验收容器及测试卷已清理，开发库备份保留。
- IDE 全量构建仍有已记录的 `NewAiRequestObservationTest` 两处对 private mysqlId 的访问错误（150、151 行）。
  未修改这些无关测试；专项验收使用已有 workspace-baseline profile。不宣称全仓测试／类型检查通过。

## 性能复测

使用与第二批相同的规模、可见比例、并发、8 工作线程、连接池和浏览预算，真实临时 MySQL 8.4／Redis 7.4。 最终 18 场景、576
个请求均通过响应和工作量断言。测量与数据库回归、构建串行；已启动的开发后端及其他宿主应用仍有背景活动。 首轮末尾与 IDE
构建有重叠，另存 browse-build-overlap.json，排除在最终对照之外。

候选 SQL 仅查成员表，使用 ix_platform_workspace_member_scan，无空间 JOIN 或 filesort。 万级目录稠密／稀疏首屏仍为 17／27 次
SQL，首屏 Redis 最多 1 次写入；有限扫描及完整性未因精度迁移退化。 微秒列由两列简化为一列，但没有将本机延迟波动解释为物理类型带来的纯性能收益。
每场景只有 32 个样本，不构成生产吞吐或尾延迟 SLO。精确计数仍完整扫描。

最终原文：[browse.json](measurements/workspace-batch3-2026-10-14/browse.json)。
最终汇总：[summary.json](measurements/workspace-batch3-2026-10-14/summary.json)。

| 候选数 | 种子可见比例 | 并发 | 中位延迟 ms | P95 ms | 排队 P95 ms | SQL/请求 | Redis/请求 | 分配 MiB/请求 |
|-------:|-------------:|-----:|------------:|-------:|------------:|---------:|-----------:|--------------:|
|    101 |         100% |    1 |        12.5 |   26.5 |         0.1 |    17–17 |        1–1 |           3.7 |
|    101 |         100% |    8 |        20.2 |   24.2 |         0.1 |    17–17 |        1–1 |           3.7 |
|    101 |         100% |   32 |        64.1 |   93.3 |        75.6 |    17–17 |        1–1 |           3.6 |
|    101 |          10% |    1 |        14.6 |   18.3 |         0.1 |    27–27 |        0–0 |           8.7 |
|    101 |          10% |    8 |        26.2 |   28.1 |         0.1 |    27–27 |        0–0 |           8.5 |
|    101 |          10% |   32 |        70.0 |  120.4 |        85.3 |    27–27 |        0–0 |           8.5 |
|  1,001 |         100% |    1 |         8.6 |   12.0 |         0.1 |    17–17 |        1–1 |           3.5 |
|  1,001 |         100% |    8 |        12.2 |   14.8 |         0.1 |    17–17 |        1–1 |           3.4 |
|  1,001 |         100% |   32 |        37.1 |   55.6 |        44.3 |    17–17 |        1–1 |           3.4 |
|  1,001 |          10% |    1 |        16.8 |   19.3 |         0.1 |    27–27 |        1–1 |          11.4 |
|  1,001 |          10% |    8 |        27.8 |   34.4 |         0.0 |    27–27 |        1–1 |          11.4 |
|  1,001 |          10% |   32 |        65.5 |  103.0 |        78.2 |    27–27 |        1–1 |          11.4 |
| 10,001 |         100% |    1 |         6.8 |   10.0 |         0.0 |    17–17 |        1–1 |           3.4 |
| 10,001 |         100% |    8 |        13.1 |   15.1 |         0.0 |    17–17 |        1–1 |           3.4 |
| 10,001 |         100% |   32 |        28.1 |   48.0 |        34.3 |    17–17 |        1–1 |           3.4 |
| 10,001 |          10% |    1 |        15.6 |   20.5 |         0.1 |    27–27 |        1–1 |          11.4 |
| 10,001 |          10% |    8 |        28.6 |   36.6 |         0.0 |    27–27 |        1–1 |          11.4 |
| 10,001 |          10% |   32 |        62.6 |   98.8 |        75.9 |    27–27 |        1–1 |          11.4 |

### 复测命令

从 backend 目录使用 JDK 21，在独立临时实例运行，禁止连接业务库：

```sh
mvn -o -Pworkspace-baseline -pl arte-app -am \
  -Dtest=WorkspaceDirectoryBaselineTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dplatform.workspace.baseline=true -Dplatform.workspace.baseline.mode=browse \
  '-Dplatform.test.mysql=jdbc:mysql://127.0.0.1:13328/mysql?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true' \
  -Dplatform.test.redis=redis://127.0.0.1:16401 \
  -Dplatform.workspace.baseline.output=target/workspace-directory-micros-browse-final.json test
```

已有开发库迁移须由数据库迁移会话执行：

```sql
USE arte;
SET @arte_workspace_time_migration_confirmed=1;
SOURCE scripts/arte-platform-workspace-time-micros-migration-mysql.sql;
```

该确认变量意味着已停写并保存可恢复备份；脚本不会自动替操作者完成停写或备份。
