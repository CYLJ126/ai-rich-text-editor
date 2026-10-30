package com.arte.ainew.spi.auth;

import com.arte.ainew.pojo.context.ContextSnapshot;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.pojo.execution.InvocationRequest;
import com.arte.ainew.pojo.tool.ToolSetSnapshot;
import reactor.core.publisher.Mono;

import java.util.Objects;

/**
 * 模型受理／派发／外发的可信门控
 * <p>
 * Permit 不可序列化，事务回调中禁止阻塞 Reactor 或等待网络。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 15:00 ✾
 */
public interface AiAdmissionAuthorizationGuard {

    /**
     * 标识本次授权检查所处的执行边界
     * <p>
     * 用于选择快照要求及当前资格复核方式；不是执行状态。
     */
    enum Boundary {
        // 模型调用受理：在保存 Invocation／Turn／Outbox 的事务中复核。
        ADMISSION,
        // 模型调用派发：在创建 Attempt、预留预算及标记派发的边界复核。
        DISPATCH,
        // 工具聊天根 Run 的受理或推进：复核固定计划，并从预算计划单独取得模型预算。
        TOOL_CHAT,
        // 模型请求真正外发前：重新核验当前资格、政策及连接，可不携带输入快照。
        EXTERNAL_SEND
    }

    /**
     * 要检查的信息
     *
     * @param request   原执行上下文、固定引用、生成输入及执行选项
     * @param binding   本次解析的精确绑定，真实提交时重新比对
     * @param snapshot  可选的可信输入快照，受理／派发／Run 必须提供，外发可仅核对请求
     * @param tools     工具聊天的固定集合，普通模型调用为空
     * @param boundary  正在核验的执行边界，不改变原执行归属或期限
     * @param budgetRef 本次明确选择的预算；Run 根上下文无预算时从固定预算计划取得，普通调用沿用原上下文预算
     */
    record Check(InvocationRequest<?> request, ResolvedBinding binding, ContextSnapshot snapshot,
                 ToolSetSnapshot tools, Boundary boundary, String budgetRef) {

        public Check {
            Objects.requireNonNull(request);
            Objects.requireNonNull(binding);
            Objects.requireNonNull(boundary);
            if (budgetRef != null) com.arte.ainew.common.validation.ContractChecks.id(budgetRef, "budgetRef");
            if (boundary != Boundary.TOOL_CHAT && !Objects.equals(budgetRef, request.context().budgetRef()))
                throw new IllegalArgumentException("Budget scope mismatch");
        }

        public Check(InvocationRequest<?> request, ResolvedBinding binding, ContextSnapshot snapshot, ToolSetSnapshot tools, Boundary boundary) {
            this(request, binding, snapshot, tools, boundary, request.context().budgetRef());
        }
    }

    Mono<Permit> authorize(Check check);

    interface Permit {
        /**
         * 在实际写入的同一物理事务中重新检查；实际请求必须与原许可绑定，不是可跨请求重放的发送令牌。
         */
        void requireInCurrentTransaction(InvocationRequest<?> actual);

        /**
         * 本次外发复核得到的连接，普通写入许可不能伪造或复用为发送许可。
         */
        default ConnectionDefinition connection() {
            throw new UnsupportedOperationException("External send connection is required");
        }
    }
}
