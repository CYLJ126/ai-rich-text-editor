package com.arte.ainew.web.response;

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
                                            String billingMode, String costDescription) {
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
                options.maxAttempts(), snapshot.policy().maxInputBytes(), options.maxOutputBytes(), "NONE", "FREE",
                "本次为已授权的本地免费只读工具；不调用聊天模型，不预留或扣减模型预算。");
    }
}
