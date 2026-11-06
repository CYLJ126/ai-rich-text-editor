package com.arte.ainew.application.support;

import com.arte.ainew.application.tool.chat.ToolChatInputs;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.infrastructure.control.ExecutionPolicies;
import com.arte.ainew.pojo.context.ContextSnapshot;
import com.arte.ainew.pojo.control.AiExecutionPolicy;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.execution.ExecutionOptions;
import com.arte.ainew.pojo.execution.InvocationRequest;
import com.arte.ainew.pojo.generation.ChatMessage;
import com.arte.ainew.pojo.generation.GenerationOptions;
import com.arte.ainew.pojo.generation.GenerationRequest;
import com.arte.ainew.spi.auth.AiAdmissionAuthorizationGuard;
import com.arte.core.enums.ResultCodeEnum;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * 受理、派发和外发共用政策规则
 * <p>
 * 不重写原请求、期限或预留。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 15:00 ✾
 */
public final class ExecutionPolicyChecks {

    private ExecutionPolicyChecks() {
    }

    public static void require(AiAdmissionAuthorizationGuard.Check check, AiExecutionPolicy policy, Clock clock) {
        InvocationRequest<?> request = check.request();
        if (!(request.input() instanceof GenerationRequest(
                List<ChatMessage> messages, GenerationOptions options1,
                List<GenerationRequest.ToolDefinition> tools, GenerationRequest.OutputFormat outputFormat
        ))) {
            throw new AdmissionException(ResultCodeEnum.AI_UNSUPPORTED_CAPABILITY);
        }
        if (request.kind() != CapabilityDescriptor.Kind.GENERATION
                || !(outputFormat instanceof GenerationRequest.TextOutput)
                || !tools.isEmpty() && !check.binding().capability().features().contains(CapabilityDescriptor.Feature.TOOL_CALLS)) {
            throw new AdmissionException(ResultCodeEnum.AI_UNSUPPORTED_CAPABILITY);
        }
        ExecutionOptions options = request.options();
        if (tools.isEmpty() && (options.maxToolSteps() != 0 || options.maxConcurrentTools() != 0)
                || options1.maxOutputTokens() > policy.maxOutputTokens() || options.maxOutputBytes() > policy.maxOutputBytes()) {
            throw new AdmissionException(ResultCodeEnum.AI_EXECUTION_LIMIT_EXCEEDED);
        }
        Duration timeout = options.requestedTimeout() == null ? policy.maximumTimeout() : options.requestedTimeout();
        if (timeout.compareTo(policy.maximumTimeout()) > 0 || options.deadline().isAfter(clock.instant().plus(timeout))) {
            throw new AdmissionException(ResultCodeEnum.AI_EXECUTION_LIMIT_EXCEEDED);
        }
        if (!clock.instant().isBefore(options.deadline()) || !clock.instant().isBefore(request.context().deadline())) {
            throw new AdmissionException(ResultCodeEnum.AI_DEADLINE_EXCEEDED);
        }
        NewAiProperties.Limits limits = ExecutionPolicies.legacy(policy);
        if (check.snapshot() != null) {
            ContextSnapshot snapshot = check.snapshot();
            if (!snapshot.expiresAt().isAfter(clock.instant()) || snapshot.createdAt().isAfter(clock.instant())
                    || snapshot.expiresAt().isAfter(snapshot.createdAt().plus(policy.snapshotRetention()))) {
                throw new AdmissionException(ResultCodeEnum.AI_CONTEXT_EXPIRED_OR_INVALID);
            }
            if (check.tools() == null && ToolChatInputs.TOKENIZER.equals(snapshot.tokenizerVersion())) {
                // Run 模型子调用的完整工具集合由耐久 Run 及派发端复核；保存的估算含固定协议开销，可还原原输入字节数。
                long inputBytes = snapshot.inputTokens() - 256L - 64L * snapshot.messages().size() - 64L * tools.size();
                if (inputBytes < 0 || inputBytes > policy.maxInputBytes()) {
                    throw new AdmissionException(ResultCodeEnum.AI_INPUT_LIMIT_EXCEEDED);
                }
                if (!snapshot.messages().equals(messages)
                        || !snapshot.modelBinding().equals(check.binding().definition())
                        || !snapshot.estimatedTokens()) {
                    throw new AdmissionException(ResultCodeEnum.AI_INVALID_CONTEXT_SNAPSHOT);
                }
            } else if (check.tools() == null) {
                if (snapshot.assistant() != null && (!snapshot.messages().equals(messages) || !tools.isEmpty())) {
                    throw new AdmissionException(ResultCodeEnum.AI_INVALID_CONTEXT_SNAPSHOT);
                }
                TextInputs.verify(snapshot, check.binding(), limits);
            } else {
                ToolChatInputs.verify(snapshot, check.tools(), limits);
            }
        } else if (check.tools() != null) {
            ToolChatInputs.estimate(messages, check.tools(), limits);
        } else if (tools.isEmpty()) TextInputs.validateContext(messages, limits);
    }
}
