package com.arte.ainew.application.support;

import com.arte.ainew.infrastructure.control.ExecutionPolicies;
import com.arte.ainew.pojo.control.AiExecutionPolicy;
import com.arte.ainew.pojo.generation.GenerationRequest;
import com.arte.ainew.spi.auth.AiAdmissionAuthorizationGuard;
import com.arte.core.enums.ResultCodeEnum;

import java.time.Clock;

/**
 * 受理、派发和外发共用政策规则；不重写原请求、期限或预留。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 15:00 ✾
 */
public final class ExecutionPolicyChecks {

    private ExecutionPolicyChecks() {
    }

    public static void require(AiAdmissionAuthorizationGuard.Check check, AiExecutionPolicy policy, Clock clock) {
        var request = check.request();
        if (!(request.input() instanceof GenerationRequest generation))
            throw new AdmissionException(ResultCodeEnum.AI_UNSUPPORTED_CAPABILITY);
        if (request.kind() != com.arte.ainew.pojo.control.CapabilityDescriptor.Kind.GENERATION
                || !(generation.outputFormat() instanceof GenerationRequest.TextOutput)
                || !generation.tools().isEmpty() && !check.binding().capability().features().contains(com.arte.ainew.pojo.control.CapabilityDescriptor.Feature.TOOL_CALLS))
            throw new AdmissionException(ResultCodeEnum.AI_UNSUPPORTED_CAPABILITY);
        var options = request.options();
        if (generation.tools().isEmpty() && (options.maxToolSteps() != 0 || options.maxConcurrentTools() != 0)
                || generation.options().maxOutputTokens() > policy.maxOutputTokens() || options.maxOutputBytes() > policy.maxOutputBytes())
            throw new AdmissionException(ResultCodeEnum.AI_EXECUTION_LIMIT_EXCEEDED);
        var timeout = options.requestedTimeout() == null ? policy.maximumTimeout() : options.requestedTimeout();
        if (timeout.compareTo(policy.maximumTimeout()) > 0 || options.deadline().isAfter(clock.instant().plus(timeout)))
            throw new AdmissionException(ResultCodeEnum.AI_EXECUTION_LIMIT_EXCEEDED);
        if (!clock.instant().isBefore(options.deadline()) || !clock.instant().isBefore(request.context().deadline()))
            throw new AdmissionException(ResultCodeEnum.AI_DEADLINE_EXCEEDED);
        var limits = ExecutionPolicies.legacy(policy);
        if (check.snapshot() != null) {
            var snapshot = check.snapshot();
            if (!snapshot.expiresAt().isAfter(clock.instant()) || snapshot.createdAt().isAfter(clock.instant())
                    || snapshot.expiresAt().isAfter(snapshot.createdAt().plus(policy.snapshotRetention())))
                throw new AdmissionException(ResultCodeEnum.AI_CONTEXT_EXPIRED_OR_INVALID);
            if (check.tools() == null && com.arte.ainew.application.tool.chat.ToolChatInputs.TOKENIZER.equals(snapshot.tokenizerVersion())) {
                // Run 模型子调用的完整工具集合由耐久 Run 及派发端复核；保存的估算含固定协议开销，可还原原输入字节数。
                long inputBytes = snapshot.inputTokens() - 256L - 64L * snapshot.messages().size() - 64L * generation.tools().size();
                if (inputBytes < 0 || inputBytes > policy.maxInputBytes())
                    throw new AdmissionException(ResultCodeEnum.AI_INPUT_LIMIT_EXCEEDED);
                if (!snapshot.messages().equals(generation.messages()) || !snapshot.modelBinding().equals(check.binding().definition()) || !snapshot.estimatedTokens())
                    throw new AdmissionException(ResultCodeEnum.AI_INVALID_CONTEXT_SNAPSHOT);
            } else if (check.tools() == null) TextInputs.verify(snapshot, check.binding(), limits);
            else com.arte.ainew.application.tool.chat.ToolChatInputs.verify(snapshot, check.tools(), limits);
        } else if (check.tools() != null)
            com.arte.ainew.application.tool.chat.ToolChatInputs.estimate(generation.messages(), check.tools(), limits);
        else if (generation.tools().isEmpty()) TextInputs.validateContext(generation.messages(), limits);
    }
}
