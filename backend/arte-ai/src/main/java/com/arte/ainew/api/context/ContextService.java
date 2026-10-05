package com.arte.ainew.api.context;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.pojo.context.ContextRequest;
import com.arte.ainew.pojo.context.ContextSnapshot;
import com.arte.ainew.pojo.control.ResolvedBinding;
import reactor.core.publisher.Mono;

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

    /**
     * 重新授权并组装规范化消息、实际历史和来源；核对固定绑定容量、计数及裁剪事实，不调用模型。
     */
    Mono<ContextSnapshot> assemble(ContextRequest request, ResolvedBinding binding, ExecutionContext context);

    /**
     * 按当前权限读取已有快照；过期快照可用于审计，但不得未经重建用于新调用。
     */
    Mono<ContextSnapshot> find(String snapshotId, ExecutionContext context);
}
