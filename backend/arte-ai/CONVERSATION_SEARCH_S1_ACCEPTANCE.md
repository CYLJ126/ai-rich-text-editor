# 会话搜索第一阶段交付与验证

日期：2026-10-15。范围：S1 契约、配置、索引蓝图与真实依赖原型。搜索 HTTP、后台扫描／进度存储、Redis 搜索会话实现和页面交互留待
S2～S4；本记录不代表完整搜索可用。

## 已交付

- `ConversationSearchService` 与请求／响应数据契约；搜索、关闭、附近轮次及历史边界模式互斥，不注册占位 HTTP。
- 前端 `conversation-search-contract.ts`：与后端对应的消息／标题联合类型、片段区间、前后游标与附近历史契约；仅导出类型，未调用接口。
- `ConversationArchiveReader` 使用 Reactor `Mono<Set<String>>`，对齐现有异步持久化边界；默认空实现无存储访问。
  `ConversationArchiveCommands` 默认能力 false，archive／unarchive 返回明确 `ARCHIVE_NOT_AVAILABLE`，不伪成功。两个端口均可由真实实现替换。
- `SearchIndexRecord`：可信 owner、TITLE／MESSAGE 身份、候选／部分回复、空 groupIds、内容指纹及同步版本。稳定键以有长度边界的字段计算
  SHA-256，不把不同主体或候选合并。
- `SearchIndexSchema`：strict mapping、keyword groupIds、date_nanos 保留时间精度、offsets 与 IK／编号辅助字段；显式蓝图，不在启动时建索引。
- `ConversationSearchIndex`／ES 适配：固定主体／空间过滤、正反向完整 sort、PIT、完整响应检查；已失效 PIT
  单独标识，关闭幂等。它是内部检索端口，命中仍需 S3 执行当前权限／归档校验，不能直接暴露给客户端。
- `SearchPreview`：ES HTML 编码后受控标记转换为纯文本和 UTF-16 区间，保留用户原始标签文字，限制预览及字形边界；不把原始高亮
  HTML 交给界面。

## 版本与配置基线

| 项目                       | 本次基线                                                                                                                        |
|----------------------------|---------------------------------------------------------------------------------------------------------------------------------|
| 编译 JDK                   | 21                                                                                                                              |
| Elasticsearch／analysis-ik | 9.4.2／9.4.2，核对现有本机实例与插件描述符                                                                                      |
| Java Client／Rest5 Client  | 9.4.5，由现有 Spring Boot 4.1.1 BOM 固定；本次真实联调通过，未升级或替换现有客户端                                              |
| Redis／Redisson            | Redis 7.4.9／Redisson 4.3.0                                                                                                     |
| 分词组合                   | 首版 index/query 均为 ik_smart，并加 lowercase；额外编号字段保留英文、数字、下划线和连字符，不假定 max_word 包含 smart 全部词项 |
| 临时索引                   | 随机 `arte-search-s1-<uuid>-g01`，2 主分片、0 副本，仅测试文档                                                                  |
| 默认开关                   | master、query、scan、backfill、reconcile 全部 false                                                                             |

配置位于 `src/main/resources/ainew/config/conversation-search.properties`，由现有 `application-ai.yml` 导入。ES／Redisson
客户端复用 app 既有装配与凭据；搜索主开关开启才装配内部 ES 适配与专用有界调度器并要求两项依赖存在，装配不发探活或执行远端写入。S1
的查询／扫描子开关为后续阶段预留，当前没有对应任务或端点。

Redis namespace、10 分钟空闲／30 分钟存续／3 个名额、2 秒 Redis 超时，以及 20 条页大小、100 候选／5 次内部读取／3 秒请求预算、800
字预览等已声明并校验。会话名额／TTL 及整页预算执行由 S3 实现，此处不把配置绑定当成业务功能已经完成。不同环境应配置独立
namespace／alias。

## 验证结果

最终针对性测试 44 项，0 失败、0 错误、0 跳过：

| 测试                                | 数量 | 主要证据                                                                                                                     |
|-------------------------------------|-----:|------------------------------------------------------------------------------------------------------------------------------|
| SearchContractsTest                 |   10 | 不可见输入、emoji、空组／非空拒绝、身份与候选隔离、纯标题、UTF-16 高亮／HTML 编码、完整响应拒绝、客户端 DSL 解析与大整数游标 |
| SearchConfigurationTest             |    6 | 默认关闭及零远端装配、归档替换、实际配置资源绑定、非法版本／数值拒绝                                                         |
| SearchInfrastructureIntegrationTest |    2 | 真实 ES＋IK 和 Redis，环境变量显式启用                                                                                       |
| ConversationHttpIntegrationTest     |   17 | 既有会话 HTTP、命名及目录能力回归                                                                                            |
| ConfigurationAssemblyTest           |    9 | 既有新 AI 装配回归                                                                                                           |

真实 ES 验证：10,025 条同会话消息命中，完整前向遍历无重复／遗漏；从最后一条之前反向遍历 10,024
条，顺序与前向结果严格互逆。覆盖同分、相同／缺失时间、两分片、原边界重读、PIT 创建后新增记录不混入、重新创建 PIT
可检索新记录、纯标题／空会话、主体隔离、单消息 AND、不跨消息拼 AND、中英大小写／代码编号、历史候选与部分回答、空组及预留组过滤。主动关闭后的
PIT 读取返回 SEARCH_EXPIRED，重复关闭成功。原型不累计业务去重集合、不使用 collapse。

Redis 验证只使用随机 `arte:ai-new:conversation-search:s1-proof:<uuid>` 键，完成写／读／TTL／删除。测试的临时索引和键在
finally 清理；本次已检查无残留。ES 原型验证不是当前 MySQL 权限矩阵、完整同步链路或生产容量证明。

JDK 21 下 `mvn -o -f backend/pom.xml -pl arte-app -am -DskipTests compile` 通过。新增前端契约的独立类型检查通过：

```bash
cd frontend
./node_modules/.bin/tsc --ignoreConfig --noEmit --skipLibCheck --strict \
  --target ES2022 --module ESNext --moduleResolution Bundler \
  src/services/arte-ai/conversation-search-contract.ts
```

此命令不等于前端全项目类型检查。未运行全仓测试、80 条搜索质量标注集或新鲜度／生产容量验收；Top5 与 P95 目标仍归 S5。本机
Netty 原生 DNS 加载有告警并回退系统解析，当前 127.0.0.1 实例联调正常，未扩展验证远程 DNS／网络部署。

## 复验入口

使用 JDK 21；通过环境提供 `ARTE_SEARCH_IT_ES_URL`、`ARTE_SEARCH_IT_ES_USERNAME`、`ARTE_SEARCH_IT_ES_PASSWORD`、
`ARTE_SEARCH_IT_REDIS_URL`，需要 Redis 认证时再提供 `ARTE_SEARCH_IT_REDIS_PASSWORD`。凭据不写入脚本或本记录。

从仓库根目录执行：

```bash
bash backend/arte-ai/scripts/verify-conversation-search-s1.sh -o
```

已有依赖缓存可用 `-o`，首次环境可去掉。脚本运行上述 44 项；未显式提供环境变量时直接拒绝，避免把跳过的真实依赖测试算成通过。测试需要在指定
ES 创建、写入、读取和删除随机临时索引的权限，只操作自己创建的确切名称。

## 环境交接

本阶段没有新增或迁移 MySQL 表，无须执行数据库迁移，当前配置环境没有因 S1 修改 schema；现有未提交配置保留。未安装／升级 ES 或
IK，未创建正式会话搜索索引／alias，未回填实际会话，搜索主开关保持关闭，未部署或重启服务。

下一阶段为 S2 后台索引扫描／校准、独立进度存储和重试；如新增数据库进度表，需单独交付迁移及环境状态说明。
