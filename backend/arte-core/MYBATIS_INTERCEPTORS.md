# 旧业务 MyBatis 拦截器契约

`MybatisPlusConfig` 中的插件顺序为查询、插入、更新、删除、乐观锁、分页。新 AI 使用独立的 `ExecutionSqlSessionFactory`，不扫描旧 Mapper，也不加载这些插件；其 tenant/workspace/subject 校验、租约、fencing 和预算事务由新存储负责。

## 注解与身份

`@MybatisParams` 的优先级为方法、Mapper 接口、实体类。方法上的 `ignore=true` 可以覆盖实体上的注解；实体参数支持 MyBatis 的 Map／foreach 包装和继承关系。冲突的重载方法或实体策略直接报错。

默认 `queryFields={createBy}` 同时约束 SELECT、UPDATE、DELETE。默认 INSERT 写入四个审计字段，UPDATE 写入 updateBy/updateTime；同名显式审计值也由可信上下文覆盖。`queryFields={}` 是共享表策略，仅关闭创建人条件，仍保留配置的写入审计。`ignore=true` 跳过该方法的全部自定义处理，调用者须独立完成授权。

旧表的 create_by/update_by 仍使用 UserContext 中的用户名；没有可信用户名时需要身份的语句直接失败，不能降级写入空串。时间审计沿用旧业务的 LocalDateTime／JVM 时区，改用 JDBC 参数绑定。用户名不是稳定 subjectId，也不构成完整 RBAC 或租户隔离；此次修复没有改变旧表的身份模型。异步任务如需使用旧 Mapper，应在实际执行的阻塞工作线程上显式建立、恢复并关闭受信 UserContext 作用域，不能从客户端参数推断身份或把 Web 线程的 ThreadLocal 当成自动传播。

## SQL 与执行器

查询在缓存键生成前改写；对 LIMIT、JOIN、UNION、CTE、嵌套表达式中的子查询，按最终 SQL 的占位符顺序重建 ParameterMapping。原 TypeHandler、JdbcType、foreach/bind 参数保留。无匹配目标表的读取不会添加多余参数。

写操作在 Executor.update 安装一次无执行状态的 SqlSource 包装器，每次获取 BoundSql 都重新绑定当前审计值。SQL 中没有用户字符串或时间字面量，因此 SIMPLE、REUSE、BATCH 都使用本次执行的值，不依赖 StatementHandler.prepare 是否被调用。SQL 阶段及审计数据不再存入 ThreadLocal，审计值不在插件日志中输出。

支持显式列名的单行／多行 VALUES INSERT、单表 UPDATE／DELETE 和分号分隔的旧批量 UPDATE。写入目标表与注解不符、必需处理的 SQL 无法解析、未知审计字段或参数数量不符都直接失败。INSERT SELECT、upsert、多表 UPDATE／DELETE 等需要专门的可信策略，插件不会静默放行；确有需要时在单独 Mapper 方法明确忽略，并由该业务方法落实审计和授权。`_arte_audit_` 是保留的内部参数属性前缀。

## 忽略作用域

移除了无生命周期的 `MybatisInterceptor.ignore()`，项目内两处调用已经迁移为：

```java
try (var scope = MybatisInterceptor.ignoreScope()) {
    // 已授权的同步操作；支持嵌套，异常退出也恢复上一层状态。
    mapper.selectList(wrapper);
}
```

作用域须在打开它的线程按嵌套顺序关闭，不能跨线程或包围仅创建异步 Publisher 的代码。Tag 缓存初始化／刷新和文章检索标签读取已经使用此方式。

## 验证

`MybatisInterceptorTest` 使用 H2 和真实 JDBC 绑定验证参数顺序、批量插入、带引号用户名、审计覆盖、BATCH／REUSE、方法覆盖、缺失身份、作用域异常恢复、写入创建人保护及失败关闭。`MybatisPlusInterceptorCompatibilityTest` 复现应用插件顺序，验证 BaseMapper、分页总数／记录和乐观锁同时生效。

从 backend 执行：

```bash
mvn -o -pl arte-ai -am \
  -Dmaven.compiler.proc=full -Dmaven.compiler.release=21 \
  -Dslf4j.provider=ch.qos.logback.classic.spi.LogbackServiceProvider \
  '-Dtest=com.arte.core.interceptor.*Test,com.arte.ainew.persistence.*Test,com.arte.ainew.contract.*Test,com.arte.ainew.context.*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

H2 回归验证应用契约；生产 MySQL 的多语句执行、数据库时区、权限策略及业务调用仍应纳入部署验证。
