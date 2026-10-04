package com.arte.ainew.api.context;

/**
 * 上下文服务
 * 按选择策略组织消息、历史、记忆与资料；通过资源端口读取，不直接访问业务数据库，不派发模型调用。
 * <p>
 * 主要操作：组织消息、选入资料、历史、记忆和实际范围等。
 * 边界：通过资源提供者解析领域资料，返回快照与范围说明。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 16:53 ✾
 **/
public interface ContextService {
}
