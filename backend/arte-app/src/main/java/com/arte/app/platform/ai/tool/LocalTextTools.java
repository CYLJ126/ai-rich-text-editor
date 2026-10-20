package com.arte.app.platform.ai.tool;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.tool.discovery.LocalToolDiscovery;
import com.arte.ainew.application.tool.discovery.LocalToolRegistry;
import com.arte.ainew.application.tool.schema.ToolSchemaValidator;
import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.value.StructuredValue;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ExecutionTarget;
import com.arte.ainew.pojo.execution.GatewayCall;
import com.arte.ainew.pojo.tool.*;
import com.arte.ainew.serialization.ToolCatalogDigests;
import com.arte.ainew.spi.adapter.BusinessToolAdapter;
import com.arte.ainew.spi.adapter.LocalToolDefinitionSource;
import com.arte.ainew.spi.persistence.ToolCatalogStore;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 本地工具：文本字符计数
 * <p>
 * 受信本地文本工具清单及明确的免费策略；只处理本次参数，不访问文件、网络或业务资源。
 * 固定版本不可原地改契约；新增工具须同时补充实现、计费／资源授权与验收，不能按 READ_ONLY 一律放行。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/19 11:18 ✾
 */
public final class LocalTextTools implements LocalToolPolicy {

    public static final DefinitionRef TOOL = ref("tool", "text.countCodePoints");
    public static final DefinitionRef ADAPTER = ref("tool-adapter", "arte-text-code-points");
    private static final StructuredValue.ObjectValue EMPTY = object(Map.of());
    private final Clock clock;
    private final ToolProviderDefinition provider = new ToolProviderDefinition(ref("tool-provider", "arte.local-text"),
            ToolProviderDefinition.Kind.LOCAL, ref("tool-provider-adapter", "arte-local-text-source"), null);
    private final ToolSchemaDefinition input = schema("arte-text-count-input", object(Map.of(
            "$schema", text(ToolSchemaValidator.DIALECT), "type", text("object"),
            "properties", object(Map.of("text", object(Map.of("type", text("string"), "title", text("待统计文本"),
                    "description", text("空格和换行计入；组合表情可能包含多个 Unicode 码点。"), "maxLength", number(65536))))),
            "required", new StructuredValue.ArrayValue(List.of(text("text"))), "additionalProperties", new StructuredValue.BooleanValue(false))));
    private final ToolSchemaDefinition output = schema("arte-text-count-output", object(Map.of(
            "$schema", text(ToolSchemaValidator.DIALECT), "type", text("object"),
            "properties", object(Map.of("codePoints", object(Map.of("type", text("integer"), "minimum", number(0), "maximum", number(65536))))),
            "required", new StructuredValue.ArrayValue(List.of(text("codePoints"))), "additionalProperties", new StructuredValue.BooleanValue(false))));
    private final ToolSchemaDefinition configuration = schema("arte-text-count-configuration", object(Map.of(
            "$schema", text(ToolSchemaValidator.DIALECT), "type", text("object"), "properties", EMPTY,
            "additionalProperties", new StructuredValue.BooleanValue(false))));
    private final ToolDefinitionRevision revision;

    public LocalTextTools(Clock clock) {
        this.clock = clock;
        var unsigned = new ToolDefinition(TOOL, provider.definition().id(), "文本字符计数",
                "按 Unicode 码点统计文本字符数，包含空格和换行；组合表情可能计为多个字符。不读取文件、不调用模型、不外发数据。",
                Set.of("文本", "字符统计"), ref("capability", "arte-text-code-points"), input.definition(), output.definition(), configuration.definition(), EMPTY,
                new ToolCapabilities(Set.of(), false), new ToolRiskProfile(CapabilityDescriptor.SideEffect.READ_ONLY, false,
                ToolRiskProfile.Idempotency.NONE, Set.of(AdmissionAuthorization.INVOKE), Set.of()),
                // 开发期统一 v1 为 30 秒总预算；已登记的旧 5 秒契约须清理测试数据后重新发现和发布。 TODO 后续待优化——工具调用时间过长
                new ToolExecutionPolicy(Duration.ofSeconds(30), 1, Duration.ZERO, 262144, 4096, ToolExecutionPolicy.ApprovalMode.NONE), "0".repeat(64));
        var signed = new ToolDefinition(unsigned.definition(), unsigned.providerId(), unsigned.title(), unsigned.description(), unsigned.tags(),
                unsigned.capability(), unsigned.inputSchema(), unsigned.outputSchema(), unsigned.configurationSchema(), unsigned.defaultConfiguration(),
                unsigned.capabilities(), unsigned.risk(), unsigned.defaultPolicy(), ToolCatalogDigests.tool(unsigned));
        revision = new ToolDefinitionRevision(signed, provider.definition(), new ExecutionTarget.Local(ADAPTER));
    }

    public ToolDefinitionRevision revision() {
        return revision;
    }

    public LocalToolDefinitionSource.Definitions definitions() {
        return new LocalToolDefinitionSource.Definitions(List.of(input, output, configuration), List.of(revision));
    }

    /**
     * 固定受信来源与真实算法一起登记；构造和发现都不执行算法，也不自动发布。
     */
    public LocalToolRegistry registry() {
        return new LocalToolRegistry(List.of(new LocalToolRegistry.Registration(provider, () -> Mono.just(definitions()), Map.of(ADAPTER, adapter()))));
    }

    /**
     * 免费资格限定为本代码版本的完整契约，不能用同名、只读声明或空预算冒充。
     */
    public void requireFree(ToolAdmissionInput input, ToolBindingResolution binding) {
        if (!input.bindingId().equals(binding.definition().id()) || !revision.equals(binding.tool())
                || !binding.effectiveConfiguration().equals(EMPTY) || !binding.secrets().isEmpty() || !binding.networkOrigins().isEmpty())
            throw new AccessDeniedException("Tool is not covered by the reviewed local free policy");
    }

    /**
     * 仅计算本部署受信清单的预期值；Worker 仍须成功发现并匹配该值。
     */
    public String manifest() {
        return LocalToolDiscovery.manifest(new ToolCatalogStore.Batch(List.of(provider), definitions().schemas(), List.of(revision)));
    }

    private BusinessToolAdapter adapter() {
        return new BusinessToolAdapter() {
            public CapabilityDescriptor capability() {
                return revision.tool().discoveredCapability();
            }

            public Mono<ToolResult> invoke(GatewayCall<ToolInvocation> call) {
                return Mono.fromCallable(() -> {
                    call.runtime().checkActive(clock);
                    if (!TOOL.equals(call.request().input().tool()) || !new ExecutionTarget.Local(ADAPTER).equals(call.binding().target()))
                        throw new AccessDeniedException("Unregistered local text target");
                    var arguments = call.request().input().arguments();
                    if (arguments.fields().size() != 1 || !(arguments.fields().get("text") instanceof StructuredValue.StringValue value))
                        throw new IllegalArgumentException("Expected one text argument");
                    int count = value.value().codePointCount(0, value.value().length());
                    if (count > 65536) throw new IllegalArgumentException("Text exceeds tool limit");
                    call.runtime().checkActive(clock);
                    return new ToolResult(call.request().input().callId(), ToolResult.Outcome.SUCCEEDED,
                            object(Map.of("codePoints", number(count))), List.of(), List.of(), ExecutionError.SideEffect.NONE, null);
                });
            }
        };
    }

    private static DefinitionRef ref(String type, String id) {
        return new DefinitionRef(type, id, "v1");
    }

    private static StructuredValue.StringValue text(String value) {
        return new StructuredValue.StringValue(value);
    }

    private static StructuredValue.NumberValue number(long value) {
        return new StructuredValue.NumberValue(BigDecimal.valueOf(value));
    }

    private static StructuredValue.ObjectValue object(Map<String, StructuredValue> values) {
        return new StructuredValue.ObjectValue(values);
    }

    private static ToolSchemaDefinition schema(String id, StructuredValue.ObjectValue document) {
        return new ToolSchemaDefinition(ref("schema", id), document, ToolCatalogDigests.schema(document));
    }
}
