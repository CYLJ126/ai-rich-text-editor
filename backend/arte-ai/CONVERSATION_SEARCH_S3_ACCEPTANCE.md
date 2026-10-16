# 会话搜索 S3 后端交付与验收

日期：2026-10-16。对应搜索设计 V0.9、列表设计 V1.19、聊天总设计 V1.29。

S3 已实现搜索后端及前端类型化调用封装。查询、扫描等开关仍默认关闭，当前页面保持原实现；S4 双入口界面、浏览器消息锚点及 S5
搜索质量／灰度验收尚未交付。

## 交付范围

| 能力       | 当前行为                                                                                                                                                              |
|------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 搜索 HTTP  | `POST /arte/ai-new/conversation/searchConversations`、`closeSearch`、`queryTurnsAround`；新 AI、搜索总开关及 query-enabled 同时开启才装配                             |
| 搜索会话   | Redis Lua 原子占位、租约、活动时间和关闭；默认空闲 10 分钟、硬截止 30 分钟，每 tenant＋subject 跨 workspace 最多 3 个，不驱逐已有搜索、无心跳                         |
| 翻页       | 固定物理代际 PIT、最新 PIT ID、HMAC 签名的完整原始 sort；前后翻页、同 PIT 首页、同游标重试，不在 Redis 累积历史页书签                                                 |
| 补查与失败 | 默认每页最多 20 条、检查 100 个候选、最多 5 次 ES 查询、总预算 3 秒；空页保留继续边界。不完整 ES 响应整页失败，不发布暂存记录或新游标                                 |
| 当前资格   | 开始及发布前重新授权；批量核验 owner、活动会话、归档状态、真实轮次／消息／候选关系，之后才返回安全片段。不因索引正文版本落后而等待同步                                |
| 片段       | 约 800 字形的一份纯文本预览、UTF-16 高亮区间；无 ES 片段时以已核验当前内容生成有界普通预览，不伪造高亮。不返回 PIT、物理索引、完整源记录或结果内部引用                |
| 历史定位   | queryTurnsAround 返回包含指定轮次的最多 10 轮窗口、版本及双向历史边界；不回退最新窗口，不混用版本，独立于原搜索 PIT，不延长搜索活动期                                 |
| 候选正文   | 沿用前端 getInvocationSnapshot 辅助函数，实际 HTTP 为 invocation/getInvocationSnapshots；快照／单结果读取在 S3 开启时也复核父会话与当前候选关系，已知结果版本不能绕过 |
| 范围       | 无新增 MySQL 表、业务事务内通知或模型调用；groupIds 传空，非空明确拒绝；归档仍为可替换空读／未开放写命令，不提供归档管理                                              |

实现入口：[应用服务](src/main/java/com/arte/ainew/application/search/DefaultConversationSearchService.java)、[Redis 协调](src/main/java/com/arte/ainew/infrastructure/search/RedisConversationSearchSessions.java)、[当前资格与历史窗口](src/main/java/com/arte/ainew/persistence/mybatis/MybatisConversationSearchReads.java)、[HTTP](src/main/java/com/arte/ainew/web/controller/NewAiConversationSearchController.java)、[查询装配](src/main/java/com/arte/ainew/config/NewAiSearchQueryConfiguration.java)、[前端封装](../../frontend/src/services/arte-ai/conversation-search.ts)
。closeSearch 允许统一成功包装中的空 data，其他有数据接口继续校验 data。

## 生命周期与错误

每个搜索同时只允许一项分页操作，冲突返回 409。关闭先从活动名额移除并使其不可续读，再异步清理
PIT；取消标记防止超时的初建占位迟到后复活。Redis／ES 故障不降级为空结果，不更新成功活动时间。正常预算空页属于成功读取，可以续期。

ES 保留期覆盖本次成功后的新空闲窗口并受硬截止约束；Redis 对外截止不晚于确认的 PIT 期限。请求各轮共用预算；SQL
使用有界只读一致性事务，外层请求到期后不能继续发布结果。清理失败由有界 TTL 收尾，不承诺依赖中断时立即回收 ES 资源。

| HTTP／业务码 | 含义                                                     |
|--------------|----------------------------------------------------------|
| 410／205044  | 搜索或历史游标过期；PIT 丢失、旧规则不支持时同样明确失效 |
| 429／205045  | 当前主体活动搜索名额已满                                 |
| 409／205046  | 同一个搜索已有在途读取                                   |
| 400／205047  | 游标非法、签名或绑定条件不符                             |
| 503／205048  | 依赖不可用、预算超时或 ES 响应不完整                     |
| 400／205049  | 分组筛选尚未开放                                         |

普通参数／身份／权限／目标不存在及版本冲突沿用现有包装，不返回原始依赖异常。搜索需要 ai:conversation＋ai:read；轮次定位和关闭需要
ai:conversation，助手正文仍需既有 ai:read。

历史游标从首次定位起固定最长 30 分钟，继续浏览不续期。实例共用 Redis `namespace:history-signing:v1` 签名密钥，不保存每个历史窗口或正文，也不依赖
searchSessionId。Redis 清空或密钥更换会使旧历史游标失效，可按真实目标重新定位。该密钥不设 TTL；会话键、活动名额及关闭标记有界到期。

## 配置、迁移与运行边界

沿用 [conversation-search.properties](src/main/resources/ainew/config/conversation-search.properties)
。启用查询前，需已配置的物理数据源、现有 RedissonClient、ElasticsearchClient，以及有效的具体索引／读 alias。查询不自动建索引、不自动切
alias、不等扫描追平；后台三个开关可保持关闭。已存在同步数据的索引即可搜索。

**本阶段未修改项目当前业务库
schema，未执行第二阶段迁移、业务回填、正式索引创建、服务重启或部署，因此没有业务库迁移或备份完成的声明。**
查询不依赖四张同步进度表，真实联测特意删除这些表后执行查询。未来开启 S2 后台仍需按第二阶段记录备份并显式执行独立迁移，S3
不能代替该步骤。

旧索引不自动删除；alias 切换后原搜索继续旧代际，新搜索使用新代际。删除旧代际需运维核对活动会话并覆盖最大存续期和清理余量。规则升级当前只支持本实例配置的版本，旧版本明确返回
410，不在旧游标中换规则／换 PIT。

真实归档仍未实现，测试通过可替换读取验证。开启归档写入前，需要所有读取／跳转与后台实例接入真实归档读实现，并让候选正文保护独立于搜索开关持续生效；普通目录过滤、归档期间写入拦截和归档管理随归档专项交付。

## 验证结果

环境：JDK 21；本机隔离 MySQL 8.4、ES／IK 9.4.2、Java Client 9.4.5、Redis 7.4.9。未调用真实模型。

- 后端针对性回归共 **201 项：197 通过，0 失败，0 错误，4 项条件跳过**。跳过项是既有执行装配／调用 HTTP 中需要另一套 Redis
  测试变量的通知用例；本次 S3 的 5 项真实 Redis 和 3 项 MySQL＋ES＋Redis 联测全部执行并通过。
- 核心验证：跨 workspace 并发名额、关闭与迟到响应、双向分页及未消费预读、null／大数 sort 精度、失败后保留最新 PIT
  并按原边界重试、撤权／归档／删除、当前标题、历史候选、版本冲突、PIT 到期后定位，以及无同步进度表时正常读取。
- 真实双分片 145 条同词消息验证跨页、反向、首页重读、缺失排序时间及 alias 切换；原搜索完整遍历旧代际，新搜索进入新代际。105
  条失效候选验证 100 候选预算空页和续查；缩短时限的真实 PIT 验证跨原空闲边界续期、硬截止与独立定位。
- 前端接口回归 **53 项全部通过**。新增共享契约独立 TypeScript 检查通过；包含请求封装的独立检查受到现有 Umi
  类型环境影响（@umijs/max 无 request 导出），未声明全前端类型检查通过。
- arte-app 集成编译通过。未运行全仓测试、浏览器搜索验收、真实归档管理、标注样例 Top5／定位成功率或索引新鲜度 P95 测量。
- 测试后只读核对：随机 arte_search_s3_* 数据库、arte-search-s3-* ES 索引、arte:search:s3* Redis 临时键均为 **0**
  ；项目业务库和正式索引未操作。

## 复验

真实基础设施脚本：[verify-conversation-search-s3.sh](scripts/verify-conversation-search-s3.sh)。设置 JDK 21 的
JAVA_HOME，以及：

- ARTE_SEARCH_IT_ES_URL、ARTE_SEARCH_IT_ES_USERNAME、ARTE_SEARCH_IT_ES_PASSWORD：隔离 ES／IK。
- ARTE_SEARCH_IT_REDIS_URL：隔离 Redis；需要鉴权时另设 ARTE_SEARCH_IT_REDIS_PASSWORD。
- ARTE_SEARCH_S2_IT_MYSQL_URL：jdbc:mysql://127.0.0.1:端口/mysql?...；同前缀
  MYSQL_USER／MYSQL_PASSWORD，仅允许本地测试实例并允许创建／删除随机测试库。

从仓库根目录执行 `bash backend/arte-ai/scripts/verify-conversation-search-s3.sh -o`。测试只清理自己的随机具体资源；异常中断后按实际名称和创建时间核对，勿使用
ES 通配删除。

前端复验：在 frontend 目录执行
`npx vitest run src/services/arte-ai/conversation-search.test.ts src/services/arte-ai/api.test.ts src/services/arte-ai/sharedReads.test.ts`
。后端报告位于 arte-ai/target/surefire-reports；脚本包含既有持久化、会话／调用 HTTP、执行装配及 S2 worker 回归，不代表全仓测试。
