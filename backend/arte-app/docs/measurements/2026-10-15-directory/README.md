# 2026-10-15 会话目录性能证据

- [http.json](http.json)：8080代理至12636的真实空间2小数据集，30次串行＋30次最多8并发；每项5次预热。不包含模型调用。
- [http-phases.json](http-phases.json)
  ：对应应用观察日志，按本轮端点／时间窗口的66条记录（首次读取、5预热、30串行、30并发）配对分组；记录SQL数、Servlet、授权和连接获取耗时，不保留连接ID、令牌或消息正文。
- [mysql-existing-data.json](mysql-existing-data.json)：真实配置库，只读；9条元数据、最大活动归属5条。续页为空页，不能推断容量。
- [mysql-temporary-scale.json](mysql-temporary-scale.json)：专用loopback
  MySQL，正式DDL的连接级临时表，1000／10000条、0／7置顶、投影消息计数0／10000。没有创建消息正文，未测授权／HTTP；临时表已随连接关闭清理。

同一数据集的冷／热状态及并发不同，不能跨环境相减耗时或把SQL规模结果当作真实HTTP容量证明。完整解释见[第三阶段验收记录](../../../../../frontend/src/pages/AI/ChatNew/LAYOUT_INTEGRATION_ACCEPTANCE.md)。

可复验脚本：

```sh
# 认证头由操作者在私有文件中提供，权限必须0600；禁止将令牌写进命令行或提交仓库。
python3 backend/arte-app/scripts/measure_conversation_directory.py \
  --base-url http://localhost:8080/arte/ai-new \
  --auth-header-file /private/tmp/private-auth-header \
  --tenant-id '<测试租户>' --workspace-id '<测试空间>'

# 两个Java脚本使用已安装的MySQL Connector/J；配置文件不要提交或输出密码。
java --class-path '<mysql-connector-j.jar>' \
  backend/arte-app/scripts/ConversationDirectoryMysqlProbe.java '<只读基线配置文件>' 30
java --class-path '<mysql-connector-j.jar>' \
  backend/arte-app/scripts/ConversationDirectoryMysqlScaleProbe.java '<专用本机MySQL配置文件>' \
  backend/arte-ai/scripts/arte-ai-new-conversation-metadata-migration.sql 30
```

第三阶段联调另外发送过1次真实模型请求，供应商超时进入UNKNOWN；不属于这些只读性能采样，也不应计为模型成功。
