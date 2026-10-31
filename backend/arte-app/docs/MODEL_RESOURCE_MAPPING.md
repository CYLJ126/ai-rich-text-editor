# 模型管理资源与发布版本映射：第 3 点第 2 批

日期：2026-10-30。

本批在公共 ACL 基础上增加稳定模型管理身份、精确发布版本关联、定义来源登记及真实领域适配。 仅提供内部管理应用服务，不开放
HTTP，不迁移任何旧配置，不切换 FIXED／DATABASE 模型发现或执行资格来源。

## 身份与版本

- `arte-ai` 的 `ModelResource` 保存服务器生成的 model 资源 ID、WORKSPACE 归属、主空间、名称、createdBy、状态、版本和绑定族标识。
- 创建先取得空间 model:create 门控，同事务保存管理记录并调用公共 ResourceRegistrationService，建立 EXPLICIT ACL、初始授权、范围
  epoch、审计、Outbox 和回执。
- 新资源没有 bindingRef，状态为 DRAFT；第一个关联确定 binding.id 族，之后只接受该族的精确新版本。binding.id 及其版本不充当管理资源
  ID。
- `ModelResourceVersion` 记录精确 bindingRef、不可变定义快照、FIXED／DATABASE 来源、明确数据库修订号（适用时）、releaseRef、linkedBy
  与时间。发布 v2 不创建第二份 ACL，不改 createdBy 或归属。
- `(tenantId,bindingRef)` 唯一映射到一个管理资源。同租户另一资源、包括另一空间的资源，不能抢占该引用；不同租户可经各自明确授权关联同一固定发布内容。
- 首批模型管理资源限定主空间，跨空间复用和租户级创建仍需后续专门流程。源空间和目标执行空间不会隐式互换。

凭据责任、创建者与执行归属分别处理：快照只保存连接及 SecretRef，不保存密钥明文，也不根据 createdBy 或空间 OWNER 推断凭据提供者。
凭据用途授权继续由既有独立解析／治理路径负责，本批不创建虚假的凭据责任主体。ExecutionOwner、预算、历史会话、预留和账本不写入模型资源。

## 内部服务

`ModelResourceService`（同步、数据库线程）提供：

| 方法           | 权限与行为                                                                                     |
|----------------|------------------------------------------------------------------------------------------------|
| create         | 当前 model:create；服务器生成资源身份并原子登记 ACL；相同命令重放返回原 ID                     |
| associate      | 当前 model:publish，加采用已有配置的独立受信资格；精确关联、资源版本 CAS、定义来源防冲突       |
| get / versions | 当前主空间资格和 model:read；版本列表按固定 binding key 排序、有界分页，不提供 latest 执行解析 |
| disable        | 当前 model:disable；推进管理状态与范围 epoch，保留已关联的不可变版本                           |

关联首个或新的精确版本将资源置为 PUBLISHED。重复关联同一来源及同一内容时保留首次修订证据与链接时间，不重复增加模型版本或更新资源；独立新命令仍有自己的幂等回执。
DISABLED 禁止公共 model:use；模型 read、管理及草稿 validate 不因此获得凭据或预算。已有版本的重复关联不隐式恢复
DISABLED；关联新的已发布版本可重新进入 PUBLISHED。 注意：这里的 use 是公共 ACL／领域资格，现有 AI 运行时仍使用原 Entitlement
适配器。因此本批的 disable 尚不能替代旧执行路径的停用控制，管理 HTTP 不提前开放。

`ModelResourceAccessAdapter` 是生产环境唯一 MODEL 描述／领域条件提供者，只读原始 ModelResourceStore，不递归调用受保护管理
API。 提供者与模型 Store 先装配，管理服务再组合公共授权和登记服务，避免与 DefaultWorkspaceServices 构成依赖环。

## 受信发布来源

管理采用流程不伪造 ExecutionContext，不要求先开预算。`ModelPublicationSource` 是独立的同步管理 SPI；它不替代执行侧
PublishedAiCatalog。

当前 app 实现要求 **当前 RBAC aiConfiguration:manage 或既有 admin 操作标记**，并按稳定主体重新解析有效账号；目标模型的
model:publish 不能单独认领任意平台配置。 这是对已有审核发布的内部采用流程，普通用户完整添加／验证／发布及凭据管理仍属于后续个人模型管理批次。

- FIXED：精确读取受信部署定义；不从客户端接收连接、费率或快照。
- DATABASE：必须指定已存在、不可变的正整数 catalogRevision；读取对应 revision 中的精确引用，不依赖当前 head、不自动导入、不回退其他版本。
- 只接受有完整生成能力、远端连接及模型费率依赖的绑定；不将工具绑定、未知版本或缺少依赖的对象伪装为模型。
- 定义内容采用规范化摘要；能力 feature 集合显式排序，数值归一化。相同精确引用不同内容拒绝，不按来源优先级覆盖。

首批登记模型绑定及其能力、连接、费率依赖；其他资源类型不能因尚未登记就被视为来源已确认。定义来源表以 `(type,id,version)`
摘要建立唯一键，保存原始元组及内容摘要。来源编码 0=FIXED、1=DATABASE。
绑定的来源权威不能在两种来源间切换；能力、连接和费率若已经有相同内容的权威登记，则保留该权威，其他目录只能引用相同内容的副本，不能改写来源或内容。
来源登记和版本映射与资源 CAS、epoch、审计及回执一起提交，后续任一步失败全部回滚。

## 持久化和部署

迁移：[arte-ai-model-resource-ddl-mysql.sql](../scripts/arte-ai-model-resource-ddl-mysql.sql)。先部署公共身份／空间、公共
ACL，以及数据库控制面表（采用 DATABASE 时）。

三张新增表：

- `arte_ai_model_resource`：稳定模型身份及当前管理状态。
- `arte_ai_model_version`：租户内唯一精确 binding 映射和不可变快照。
- `arte_ai_model_definition_source`：全局定义内容权威登记。

物理唯一索引使用固定长度摘要，并在读取时核对完整原始身份。版本映射另保存快照摘要，覆盖定义内容、来源、修订、releaseRef、操作者和时间；读取及关联回执重放均核对，损坏数据不得伪装为原发布内容。所有
Mapper 经平台专用工厂使用 appDataSource，绕过旧创建人过滤和路由库。 当前方法必须在原写事务和父租户门控下运行；当前版本列表计数采用锁定键集合，避免
RR 旧快照。每资源版本列表上限 10,000 条，超限明确失败，未做容量承诺。

装配在 AI、公共身份、持久化和空间四项 enabled 同时为 true 时启用。启动不执行 DDL 或旧模型回填。 新增回执 schema
`model:resource:v1`／`model:version:v1` 保留在固定平台白名单；暂不通过旧空间 HTTP 回执查询开放。关联回执的 resultVersion
为管理资源版本，不是 binding 的字符串版本；重放仍保留原结果版本。 本批未执行业务库迁移、重启或真实模型调用。

## 验证与后续

最终定向回归 348 项全部通过：core 平台／空间及包边界 185 项，AI 控制面 33 项，app 装配、持久化、空间 HTTP、ACL 与模型映射 130
项。 其中新增模型资源映射专项 20 项在 H2 和专用隔离 MySQL 8.4 各通过一次，无失败、错误或跳过。临时随机测试库与容器已清理。
验证未覆盖浏览器、多节点、容量压测或真实模型调用；未修改业务数据库、凭据或账本。最终回归使用 JDK 21 及显式 Mockito javaagent。

`ModelResourceIntegrationTest` 使用真实 DDL、Mapper、RBAC、ACL 和服务装配，覆盖资源稳定性、版本与 ACL
独立、同租户冲突、跨租户独立映射、来源及内容冲突、精确数据库修订、独立采用资格、幂等、CAS、旧快照、审计回滚和领域状态。

H2 的 REPEATABLE READ 并发更新可能直接中止锁定读取；用例明确接受该数据库冲突并确认无新映射提交。MySQL 用例要求当前读取取得已推进版本并返回
VERSION_CONFLICT。

后续第 3 批才把模型发现、精确解析、受理、派发和外发前复核改接本批映射及 model:use。 需明确选择迁移范围，已迁移范围的公共拒绝不能回退旧
Grant 放行；未迁移范围保持原授权来源。

使用 JDK 21，在 backend 运行：

```shell
mvn -o -pl arte-app -am -Dtest=ModelResourceIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

MySQL 验收额外传入专用隔离实例的 `platform.test.mysql`（root／platform-test，URL 的库名为 `/mysql?`
），逐用例创建并删除随机测试库，禁止传入业务或生产实例。

## 公共 ACL 第三批实施记录

以上保留第二批交付基线。第三批已接入模型发现、精确解析、原事务受理／派发和外发前 ACL 复核；空间需要显式迁移，管理 HTTP
尚未开放。当前边界、部署和验证见[模型 ACL 发现与执行](MODEL_ACL_EXECUTION.md)。
