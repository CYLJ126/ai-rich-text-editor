package com.arte.ainew.web.response;

import com.arte.ainew.application.tool.admission.ToolAdmissionService;
import com.arte.ainew.application.tool.approval.ToolApprovalPolicy;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.pojo.tool.ToolExecutionSnapshot;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.serialization.ToolAdmissionDigests;
import com.arte.ainew.web.ToolManagementValues;

/**
 * 可确认的公开方案；精确数值用 JSON 原文传输，不返回内部执行目标或临时授权。
 * timeoutMillis 是产品、绑定、服务器和请求求交后的相对总上限，不是适配器的独立执行时间。
 * 新提交另按入口及受理时刻固定绝对期限；预览耗时不计入随后新提交，原键确认不重置已保存期限。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/19
 */
public record ToolInvocationPreviewResponse(String planDigest, DefinitionRef binding, DefinitionRef revision,
                                            DefinitionRef tool, String title, String argumentsJson,
                                            String configurationJson, ToolManagementResponses.Schema inputSchema,
                                            long timeoutMillis, int maxAttempts,
                                            long maxInputBytes, long maxOutputBytes, String approvalMode,
                                            String billingMode, String costDescription, long approvalWindowMillis,
                                            long executionMarginMillis, java.util.List<String> approverSubjectIds,
                                            String budgetRef, com.arte.ainew.pojo.tool.ToolPrice price) {
    public static ToolInvocationPreviewResponse from(ToolAdmissionService.Preview preview) {
        var basic = from(preview.snapshot());
        return new ToolInvocationPreviewResponse(ToolApprovalPolicy.previewDigest(preview.snapshot(), preview.approval()), basic.binding(), basic.revision(),
                basic.tool(), basic.title(), basic.argumentsJson(), basic.configurationJson(), basic.inputSchema(), basic.timeoutMillis(),
                basic.maxAttempts(), basic.maxInputBytes(), basic.maxOutputBytes(), preview.snapshot().policy().approvalMode().name(), basic.billingMode(),
                basic.costDescription(), preview.approval() == null ? 0 : java.time.Duration.between(preview.snapshot().receivedAt(), preview.approval().expiresAt()).toMillis(),
                preview.approval() == null ? 0 : preview.approval().executionMargin().toMillis(),
                preview.approval() == null ? java.util.List.of() : preview.approval().approverSubjectIds().stream().sorted().toList(), basic.budgetRef(), basic.price());
    }
    public static ToolInvocationPreviewResponse from(ToolExecutionSnapshot snapshot) {
        var options = snapshot.request().options();
        var binding = snapshot.binding();
        return new ToolInvocationPreviewResponse(ToolAdmissionDigests.plan(snapshot), binding.definition(), binding.revision(),
                binding.tool().tool().definition(), binding.tool().tool().title(),
                CanonicalJson.encode(ToolManagementValues.json(snapshot.input().arguments())),
                CanonicalJson.encode(ToolManagementValues.json(binding.effectiveConfiguration())),
                ToolManagementResponses.schema(snapshot.schemas().stream()
                        .filter(schema -> schema.definition().equals(binding.tool().tool().inputSchema())).findFirst().orElseThrow()),
                options.requestedTimeout().toMillis(),
                options.maxAttempts(), snapshot.policy().maxInputBytes(), options.maxOutputBytes(), snapshot.policy().approvalMode().name(), binding.tool().tool().price() == null ? "FREE" : "CHARGE",
                (binding.tool().tool().price() != null ? "本次按次收费，先预留报价上界；实际费用以可信账单结算，未知费用保留占用。" : binding.tool().tool().risk().sideEffect() == com.arte.ainew.pojo.control.CapabilityDescriptor.SideEffect.RESOURCE_WRITE
                        ? "本次为本地免费业务写工具，须人工决定及当前资源权限；不调用聊天模型，不预留或扣减模型预算。"
                        : "本次为已授权的本地免费只读工具；不调用聊天模型，不预留或扣减模型预算。"), 0, 0, java.util.List.of(), snapshot.input().budgetRef(), binding.tool().tool().price());
    }
}
