package com.arte.ainew.spi.adapter;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.reference.ResourceRef;
import com.arte.ainew.pojo.context.ContextBudget;
import com.arte.ainew.pojo.context.ContextSnapshot;
import reactor.core.publisher.Mono;

/**
 * 资源上下文适配器服务
 * 组合模块提供已授权的固定资料，根据选定引用重新授权并读取实际内容；AI 核心不直接访问业务数据库。
 * <p>
 * 主要操作：按授权资源／范围输出内容及来源等。
 * 边界：AI 定义资源提供端口，组合模块提供文章或其他领域实现。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 17:45 ✾
 **/
public interface ResourceContextAdapter {

    /**
     * 注册的资源类型，缺少对应提供者时明确不支持。
     */
    String resourceType();

    /**
     * 先授权读取／外发再解析固定资源与范围，返回实际摘录、摘要来源及裁剪事实；不派发模型。
     */
    Mono<ContextSnapshot.Fragment> resolve(ResourceRef resource, ContextBudget budget, ExecutionContext context);
}
