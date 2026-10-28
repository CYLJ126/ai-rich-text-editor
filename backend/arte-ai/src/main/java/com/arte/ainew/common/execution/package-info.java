/**
 * AI 执行身份快照、授权及执行信封，不依赖 Spring 或 Reactor。
 * 这些对象是服务端内部契约，不应直接绑定为外部请求体，也不代表授权永久有效。
 * 公共三元归属键已迁至 core.pojo.platform.ExecutionOwner，ExecutionOwners 保留 AI 上下文转换。
 * ExecutionPrincipal 与 core 当前主体显式对齐；历史名称及状态语义不混用，其他信封后续按场景提取。
 */
package com.arte.ainew.common.execution;
