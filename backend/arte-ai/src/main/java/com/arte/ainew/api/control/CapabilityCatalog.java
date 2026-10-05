package com.arte.ainew.api.control;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.execution.InvocationRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 能力服务
 * <p>
 * 主要操作：声明、发现、校验、查询能力与操作等。
 * 边界：管理可版本化契约，不默认开放全部发现结果。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 17:30 ✾
 **/
public interface CapabilityCatalog {

    /** 精确读取固定版本；重新授权，不存在／不可见以明确错误结束，不返回 null。 */
    Mono<CapabilityDescriptor> resolve(DefinitionRef capability, ExecutionContext context);

    /** 有界且经过发现权限过滤；不支持的资源／工具提供者不能呈现为 EXECUTABLE。 */
    Flux<CapabilityDescriptor> discover(CapabilityDescriptor.Kind kind, int limit, ExecutionContext context);

    /** 校验登记 Schema、输入类型、模态／特性和当前使用权限；不建立 Invocation。 */
    Mono<Void> validate(InvocationRequest<?> request);
}
