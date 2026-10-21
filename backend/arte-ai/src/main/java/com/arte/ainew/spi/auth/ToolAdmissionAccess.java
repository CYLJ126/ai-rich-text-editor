package com.arte.ainew.spi.auth;

import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.pojo.tool.ToolAdmissionInput;
import com.arte.ainew.pojo.tool.ToolBindingResolution;
import com.arte.ainew.pojo.tool.ToolExecutionSnapshot;
import reactor.core.publisher.Mono;

/**
 * 宿主复用现有身份／空间／AI 资格／工具调用及资源权限，明确批准本次本地免费受理；只读与领域写入使用独立入口；没有默认允许实现。
 * 不从“没有预算引用”推断免费，不能用 S1 管理资格或 S2 执行网关替代受理资格。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 16:30 ✾
 */
@FunctionalInterface
public interface ToolAdmissionAccess {

    Mono<Permit> authorizeFreeReadOnly(ToolAdmissionInput input, ExecutionContext context, ToolBindingResolution binding);

    /**
     * 写工具须由宿主显式实现，旧的只读许可不能自动获得业务写入资格。
     */
    default Mono<Permit> authorizeFreeWrite(ToolAdmissionInput input, ExecutionContext context, ToolBindingResolution binding) {
        return Mono.error(new org.springframework.security.access.AccessDeniedException("Explicit domain write access required"));
    }

    default Mono<Permit> authorizeFree(ToolAdmissionInput input, ExecutionContext context, ToolBindingResolution binding) {
        return switch (binding.tool().tool().risk().sideEffect()) {
            case READ_ONLY -> authorizeFreeReadOnly(input, context, binding);
            case RESOURCE_WRITE -> authorizeFreeWrite(input, context, binding);
            default ->
                    Mono.error(new org.springframework.security.access.AccessDeniedException("Unsupported tool side effect"));
        };
    }

    default Mono<Permit> authorizeCharged(ToolAdmissionInput input, ExecutionContext context, ToolBindingResolution binding) {
        return Mono.error(new org.springframework.security.access.AccessDeniedException("Paid tool access denied"));
    }

    default Mono<Permit> authorize(ToolAdmissionInput input, ExecutionContext context, ToolBindingResolution binding) {
        return binding.tool().tool().price() == null ? authorizeFree(input, context, binding) : authorizeCharged(input, context, binding);
    }

    /**
     * 本次操作的可信许可；只允许权限子集，入库及重放仍在真实写事务复核。
     */
    interface Permit {

        ExecutionAuthorization authorization();

        /**
         * 在同一物理数据源／线程重验并持有当前公共资格及权限锁；snapshot 是最终胜出的实际方案，可能来自并发受理。
         * 历史重放的原 deadline 可能已过，Permit 应按本次请求检查当前资格，不能重新提交原执行。
         * 必须检查其 owner、工具及资源资格、必要 scopes 和免费资格；不执行业务工具，不接受客户端构造的 Permit。
         */
        void requireInCurrentTransaction(ToolExecutionSnapshot snapshot);
    }
}
