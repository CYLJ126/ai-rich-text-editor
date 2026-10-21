package com.arte.ainew.serialization;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.pojo.tool.ToolAdmissionInput;
import com.arte.ainew.pojo.tool.ToolExecutionSnapshot;
import com.arte.ainew.pojo.tool.ToolSchemaDefinition;

import java.util.Comparator;
import java.util.TreeSet;

/**
 * TOOL 业务意图与实际执行材料分别求摘要；新 ID、trace、授权刷新及接收时刻不造成幂等冲突。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 16:30 ✾
 */
public final class ToolAdmissionDigests {
    private ToolAdmissionDigests() {
    }

    /**
     * 重放定位后的语义比较不重新选择绑定版本，也不比较当前服务器默认策略。
     */
    public static String submission(ExecutionOwner owner, ToolAdmissionInput input) {
        var fields = CanonicalJson.fields("format", "local-free-tool-submission-v1", "owner", owner,
                "bindingId", input.bindingId(), "arguments", ToolCatalogDigests.configuration(input.arguments()),
                "requestedTimeout", input.requestedTimeout(), "maxAttempts", input.maxAttempts(), "maxOutputBytes", input.maxOutputBytes());
        if (input.budgetRef() != null) fields.put("budgetRef", input.budgetRef());
        return CanonicalJson.digest(fields);
    }

    /**
     * 固定实际版本、Schema、配置、策略及执行权限上限；绝对期限由快照载荷完整性保护。
     */
    public static String request(ToolExecutionSnapshot snapshot) {
        var request = snapshot.request();
        var plan = snapshot.binding();
        if (plan.tool().tool().price() != null)
            return CanonicalJson.digest(CanonicalJson.fields("format", "paid-tool-request-v1",
                    "submission", snapshot.submissionDigest(), "binding", plan.definition(), "revision", plan.revision(),
                    "owner", plan.owner(), "contract", plan.contractDigest(), "configuration", plan.configurationDigest(),
                    "target", plan.tool().target(), "price", plan.tool().tool().price(), "policy", snapshot.policy(),
                    "parent", request.context().parentExecutionId(), "budgetRef", request.context().budgetRef(),
                    "options", request.options(), "call", request.input(),
                    "schemas", snapshot.schemas().stream().sorted(Comparator.comparing(s -> s.definition().toString()))
                            .map(s -> CanonicalJson.fields("definition", s.definition(), "checksum", s.checksum())).toList(),
                    "scopes", new TreeSet<>(request.context().authorization().scopes())));
        if (request.context().parentExecutionId() != null)
            return CanonicalJson.digest(CanonicalJson.fields("format", "tool-chat-tool-execution-v1",
                    "parent", request.context().parentExecutionId(), "child", request.context().executionId(),
                    "binding", plan.definition(), "contract", plan.contractDigest(), "configuration", plan.configurationDigest(),
                    "target", plan.tool().target(), "policy", snapshot.policy(), "input", request.input(),
                    "schemas", snapshot.schemas().stream().sorted(Comparator.comparing(s -> s.definition().toString()))
                            .map(s -> CanonicalJson.fields("definition", s.definition(), "checksum", s.checksum())).toList(),
                    "scopeCeiling", new TreeSet<>(request.context().authorization().scopes()), "options", request.options()));
        return CanonicalJson.digest(CanonicalJson.fields("format", "local-free-tool-execution-v1",
                "submission", submission(ExecutionOwner.from(request.context()), snapshot.input()),
                "binding", plan.definition(), "contract", plan.contractDigest(), "configuration", plan.configurationDigest(),
                "policy", snapshot.policy(), "target", plan.tool().target(), "capability", request.capability(),
                "tool", request.input().tool(), "callId", request.input().callId(), "scopeCeiling", new TreeSet<>(request.context().authorization().scopes()),
                "schemas", snapshot.schemas().stream().sorted(Comparator.comparing((ToolSchemaDefinition s) -> s.definition().id())
                                .thenComparing(s -> s.definition().version()))
                        .map(s -> CanonicalJson.fields("definition", s.definition(), "checksum", s.checksum())).toList(),
                "maxAttempts", request.options().maxAttempts(), "maxOutputBytes", request.options().maxOutputBytes(),
                "timeout", request.options().requestedTimeout()));
    }

    /**
     * 用户确认的相对执行方案；不含临时 ID、绝对期限、trace 或授权引用。
     */
    public static String plan(ToolExecutionSnapshot snapshot) {
        var request = snapshot.request();
        var plan = snapshot.binding();
        if (plan.tool().mcp())
            return CanonicalJson.digest(CanonicalJson.fields("format", "mcp-tool-preview-v1",
                    "submission", snapshot.submissionDigest(), "binding", plan.definition(), "revision", plan.revision(),
                    "owner", plan.owner(), "contract", plan.contractDigest(), "configuration", plan.configurationDigest(),
                    "target", plan.tool().target(), "protocol", plan.tool().protocol(), "policy", snapshot.policy(),
                    "billingMode", "FREE", "scopeCeiling", new TreeSet<>(request.context().authorization().scopes()),
                    "schemas", snapshot.schemas().stream().sorted(Comparator.comparing(s -> s.definition().toString()))
                            .map(s -> CanonicalJson.fields("definition", s.definition(), "checksum", s.checksum())).toList(),
                    "maxAttempts", request.options().maxAttempts(), "maxOutputBytes", request.options().maxOutputBytes(),
                    "timeout", request.options().requestedTimeout()));
        if (plan.tool().tool().price() != null)
            return CanonicalJson.digest(CanonicalJson.fields("format", "paid-tool-preview-v1",
                    "submission", snapshot.submissionDigest(), "binding", plan.definition(), "revision", plan.revision(),
                    "owner", plan.owner(), "contract", plan.contractDigest(), "configuration", plan.configurationDigest(),
                    "target", plan.tool().target(), "price", plan.tool().tool().price(), "policy", snapshot.policy(),
                    "billingMode", "CHARGE", "scopeCeiling", new TreeSet<>(request.context().authorization().scopes()),
                    "schemas", snapshot.schemas().stream().sorted(Comparator.comparing(s -> s.definition().toString()))
                            .map(s -> CanonicalJson.fields("definition", s.definition(), "checksum", s.checksum())).toList(),
                    "maxAttempts", request.options().maxAttempts(), "maxOutputBytes", request.options().maxOutputBytes(),
                    "timeout", request.options().requestedTimeout()));
        return CanonicalJson.digest(CanonicalJson.fields("format", "local-free-tool-preview-v1",
                "submission", snapshot.submissionDigest(), "binding", plan, "policy", snapshot.policy(),
                "billingMode", plan.tool().tool().price() == null ? "FREE" : "CHARGE", "scopeCeiling", new TreeSet<>(request.context().authorization().scopes()),
                "schemas", snapshot.schemas().stream().sorted(Comparator.comparing(s -> s.definition().toString()))
                        .map(s -> CanonicalJson.fields("definition", s.definition(), "checksum", s.checksum())).toList(),
                "maxAttempts", request.options().maxAttempts(), "maxOutputBytes", request.options().maxOutputBytes(),
                "timeout", request.options().requestedTimeout()));
    }

    public static String callId(ExecutionOwner owner, String bindingId, String idempotencyKey) {
        return CanonicalJson.key("tool-call", owner.tenantId(), owner.workspaceId(), owner.subjectId(), bindingId, idempotencyKey);
    }
}
