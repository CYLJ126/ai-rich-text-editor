package com.arte.ainew.application.support;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.reference.ResourceRef;
import com.arte.ainew.pojo.execution.InvocationSubmission;
import com.arte.ainew.pojo.generation.GenerationRequest;
import com.arte.ainew.serialization.CanonicalJson;

import java.util.List;
import java.util.TreeSet;

/**
 * 语义摘要受理
 * <p>
 * v1 受理语义摘要；固定发布／输入／选择约束，不包含新 ID、追踪、授权刷新引用或组装时间。
 * 用于生成请求的语义摘要，帮助判断重复提交的请求是否与第一次一致，防止同一个幂等键被用于不同操作。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/5 16:22 ✾
 */
public final class AdmissionDigests {

    private AdmissionDigests() {
    }

    /**
     * 会话创建语义摘要
     * <p>
     * 用途：判断同一幂等键是否仍在创建相同配置的会话
     * 参与计算内容：标题、ChatProfile 引用、资源列表、摘要协议版本
     *
     * @param title 会话标题
     * @param profile ChatProfile 引用
     * @param resources 资源列表
     * @return 会话创建摘要
     */
    public static String conversation(String title, DefinitionRef profile, List<ResourceRef> resources) {
        return CanonicalJson.digest(CanonicalJson.fields("schema", "conversation-creation-v1",
                "title", title, "profile", profile, "resources", resources));
    }

    /**
     * 文本提交语义摘要
     * <p>
     * 用途：判断同一幂等键是否仍在提交相同内容（即同一次调用）
     * 参与计算内容：主体和权限范围、能力／绑定版本、输入文本、生成参数、执行限制、预算／发布引用、上下文容量、会话及分支信息等
     *
     * @param submission 文本提交请求
     * @return 文本提交摘要
     */
    public static String submission(InvocationSubmission<?> submission) {
        var request = submission.request();
        var generation = (GenerationRequest) request.input();
        var options = request.options();
        var context = request.context();
        var link = submission.conversation();
        var turn = submission.newTurn();
        return CanonicalJson.digest(CanonicalJson.fields("schema", "text-admission-v1",
                "owner", ExecutionOwner.from(context), "scopes", new TreeSet<>(context.authorization().scopes()),
                "parentExecutionId", context.parentExecutionId(), "budgetRef", context.budgetRef(), "releaseRef", context.releaseRef(),
                "capability", request.capability(), "binding", request.binding(), "kind", request.kind(),
                "messages", TextInputs.semanticMessages(generation.messages()), "generationOptions", generation.options(),
                "outputFormat", "text", "maxAttempts", options.maxAttempts(), "maxOutputBytes", options.maxOutputBytes(),
                "maxToolSteps", options.maxToolSteps(), "maxConcurrentTools", options.maxConcurrentTools(),
                "deadlinePolicy", options.requestedTimeout() == null ? "absolute" : "relative",
                "requestedTimeout", options.requestedTimeout(), "absoluteDeadline", options.requestedTimeout() == null ? options.deadline() : null,
                "contextBudget", submission.snapshot().budget(), "contentDigest", submission.snapshot().contentDigest(),
                "tokenizer", submission.snapshot().tokenizerVersion(),
                "conversationId", link == null ? null : link.conversationId(),
                "conversationVersion", link == null ? null : link.conversationVersion(),
                "parentTurnId", turn == null ? null : turn.parentTurnId(),
                "supersedesTurnId", turn == null ? null : turn.supersedesTurnId(),
                "replacesInvocationId", submission.replacesInvocationId()));
    }
}
