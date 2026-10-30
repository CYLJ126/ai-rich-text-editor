package com.arte.ainew.application.tool.chat;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwners;
import com.arte.ainew.pojo.execution.ExecutionOptions;
import com.arte.ainew.pojo.execution.InvocationRequest;
import com.arte.ainew.pojo.generation.GenerationRequest;
import com.arte.ainew.pojo.tool.ToolChatRunSnapshot;
import com.arte.ainew.spi.auth.AiAdmissionAuthorizationGuard;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.Instant;

/**
 * 从可信 Run 固定计划构造不持久化的模型门控输入，不给根执行改写预算或身份。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 15:00 ✾
 */
public final class ToolChatAdmissionChecks {

    private ToolChatAdmissionChecks() {
    }

    /**
     * 从可信 Run 固定计划构造不持久化的模型门控输入。
     *
     * @param fixed          工具聊天 Run 的不可变执行计划
     * @param current        当前执行上下文
     * @param maxOutputBytes 最大输出字节
     * @param clock          时钟
     * @return 模型门控检查对象
     */
    public static AiAdmissionAuthorizationGuard.Check of(ToolChatRunSnapshot fixed, ExecutionContext current, long maxOutputBytes, Clock clock) {
        if (!ExecutionOwners.from(current).equals(ExecutionOwners.from(fixed.context()))) {
            throw new AccessDeniedException("Run admission scope mismatch");
        }
        // 输入请求
        GenerationRequest input = new GenerationRequest(fixed.initialContext().messages(), fixed.generation(),
                fixed.tools().descriptions(), new GenerationRequest.TextOutput());
        // 截止时间
        Instant deadline = current.deadline().isBefore(clock.instant().plus(fixed.limits().timeout())) ? current.deadline()
                : clock.instant().plus(fixed.limits().timeout());
        // 执行选项
        ExecutionOptions options = new ExecutionOptions(deadline, 1, maxOutputBytes, Math.min(100,
                fixed.limits().maxToolCalls()), fixed.limits().maxConcurrentTools(), fixed.limits().timeout());
        // 调用请求
        InvocationRequest<GenerationRequest> request = new InvocationRequest<>(fixed.model().capability().definition(),
                fixed.model().definition(), input.kind(), input, options, current);
        // 构造并返回模型门控检查
        return new AiAdmissionAuthorizationGuard.Check(request, fixed.model(), fixed.initialContext(), fixed.tools(),
                AiAdmissionAuthorizationGuard.Boundary.TOOL_CHAT, fixed.budgetPlan().selection(fixed.model().definition()).budgetRef());
    }
}
