package com.arte.ainew.infrastructure.http;

import com.arte.ainew.application.tool.discovery.StaticToolRegistry;
import com.arte.ainew.application.tool.schema.ToolSchemaValidator;
import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.ainew.pojo.control.ExecutionTarget;
import com.arte.ainew.pojo.tool.*;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.serialization.ToolCatalogDigests;
import com.arte.ainew.spi.adapter.McpToolAdapter;
import com.arte.ainew.spi.adapter.StaticToolDefinitionSource;
import com.arte.core.pojo.reference.DefinitionRef;
import com.arte.core.pojo.value.StructuredValue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 固定 MCP 状态查询
 * <p>
 * 只投影 status，拒绝任意 URL、参数、Header 和远端正文回显。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/21 12:50 ✾
 */
@Slf4j
public final class McpHealthToolAdapter implements McpToolAdapter {

    public static final String TOOL_ID = "arte.mcp.health";
    public static final String OPERATION = "health";
    private static final StructuredValue.ObjectValue EMPTY = object(Map.of());
    private static final Set<String> STATUSES = Set.of("UP", "DOWN", "OUT_OF_SERVICE", "UNKNOWN");
    private final ConnectionDefinition connection;
    private final McpStreamableHttpTransport streamableTransport;
    private final ToolProviderDefinition provider;
    private final ToolDefinitionRevision revision;
    private final List<ToolSchemaDefinition> schemas;
    private final JsonNode inputDocument, outputDocument;

    /**
     * 创建 MCP 状态查询工具适配器
     * <p>
     * 定义工具名称、输入／输出 Schema、免费只读策略及固定连接。
     *
     * @param connection          MCP 连接定义
     * @param streamableTransport MCP 流式 HTTP 传输
     */
    public McpHealthToolAdapter(ConnectionDefinition connection, McpStreamableHttpTransport streamableTransport) {
        this.connection = Objects.requireNonNull(connection);
        this.streamableTransport = Objects.requireNonNull(streamableTransport);
        if (!connection.protocol().equals(ToolDefinitionRevision.MCP_PROTOCOL) || connection.credential() != null
                || connection.state() != ConnectionDefinition.State.ENABLED || connection.maxResponseBytes() > 65536)
            throw new IllegalArgumentException("Health tool requires an enabled bounded anonymous MCP connection");
        provider = new ToolProviderDefinition(ref("tool-provider", "arte.mcp-health"), ToolProviderDefinition.Kind.MCP,
                ref("tool-provider-adapter", "arte-mcp-health-source"), connection.definition());
        var input = schema("arte-mcp-health-input", object(Map.of("$schema", text(ToolSchemaValidator.DIALECT),
                "type", text("object"), "properties", EMPTY, "additionalProperties", new StructuredValue.BooleanValue(false))));
        var output = schema("arte-mcp-health-output", object(Map.of("$schema", text(ToolSchemaValidator.DIALECT),
                "type", text("object"), "properties", object(Map.of("status", object(Map.of("type", text("string"), "enum",
                        new StructuredValue.ArrayValue(STATUSES.stream().sorted().map(v -> (StructuredValue) text(v)).toList()))))),
                "required", new StructuredValue.ArrayValue(List.of(text("status"))), "additionalProperties", new StructuredValue.BooleanValue(false))));
        // 连接原文摘要冻结路径、时限、上限及协议；同版本配置变化会改变完整工具合同和部署清单。
        var configuration = object(Map.of("connectionDigest", text(CanonicalJson.digest(connection))));
        var config = schema("arte-mcp-health-configuration", object(Map.of("$schema", text(ToolSchemaValidator.DIALECT),
                "type", text("object"), "properties", object(Map.of("connectionDigest", object(Map.of("type", text("string"),
                        "const", configuration.fields().get("connectionDigest"))))), "required", new StructuredValue.ArrayValue(List.of(text("connectionDigest"))),
                "additionalProperties", new StructuredValue.BooleanValue(false))));
        schemas = List.of(input, output, config);
        var json = GenerationJson.mapper(65536);
        inputDocument = json.valueToTree(ToolCatalogDigests.jsonValue(input.document()));
        outputDocument = json.valueToTree(ToolCatalogDigests.jsonValue(output.document()));
        var unsigned = new ToolDefinition(ref("tool", TOOL_ID), provider.definition().id(), "MCP 服务状态查询",
                "查询管理员登记服务的健康状态，仅返回 UP、DOWN、OUT_OF_SERVICE 或 UNKNOWN。免费、只读，不发送聊天内容。",
                Set.of("服务状态", "MCP"), ref("capability", "arte-mcp-health"), input.definition(), output.definition(), config.definition(), configuration,
                new ToolCapabilities(Set.of(), false), new ToolRiskProfile(CapabilityDescriptor.SideEffect.READ_ONLY, false,
                ToolRiskProfile.Idempotency.NONE, Set.of("ai:invoke"), Set.of(origin(connection.endpoint()))),
                new ToolExecutionPolicy(Duration.ofSeconds(30), 1, Duration.ZERO, 1024, 4096, ToolExecutionPolicy.ApprovalMode.NONE), "0".repeat(64));
        var tool = new ToolDefinition(unsigned.definition(), unsigned.providerId(), unsigned.title(), unsigned.description(), unsigned.tags(), unsigned.capability(),
                unsigned.inputSchema(), unsigned.outputSchema(), unsigned.configurationSchema(), unsigned.defaultConfiguration(), unsigned.capabilities(), unsigned.risk(),
                unsigned.defaultPolicy(), ToolCatalogDigests.tool(unsigned));
        revision = new ToolDefinitionRevision(tool, provider.definition(), new ExecutionTarget.Remote(connection.definition(), OPERATION), ToolDefinitionRevision.MCP_PROTOCOL);
    }

    @Override
    public ToolDefinitionRevision revision() {
        return revision;
    }

    public StaticToolDefinitionSource.Definitions definitions() {
        return new StaticToolDefinitionSource.Definitions(schemas, List.of(revision));
    }

    public StaticToolRegistry.Registration registration() {
        return new StaticToolRegistry.Registration(provider, () -> Mono.just(definitions()), Map.of(), Map.of(), Map.of((ExecutionTarget.Remote) revision.target(), this));
    }

    /**
     * 宿主受理、执行及事务复核均使用完整契约，绝不根据名称或空预算推定免费。
     */
    public void requireFree(ToolAdmissionInput input, ToolBindingResolution binding) {
        if (!input.bindingId().equals(binding.definition().id()) || !revision.equals(binding.tool())
                || !binding.effectiveConfiguration().equals(revision.tool().defaultConfiguration()) || !input.arguments().equals(EMPTY)
                || !ToolExecutionRules.supportedTarget(binding, binding.effectivePolicy()))
            throw new AccessDeniedException("MCP tool is not covered by the reviewed free policy");
    }

    public Mono<ToolResult> invoke(RemoteToolCall call) {
        return Mono.defer(() -> {
            requireFree(new ToolAdmissionInput(call.binding().definition().id(), call.call().request().input().arguments(), null, null, null), call.binding());
            return streamableTransport.call(connection, call.call().runtime(), OPERATION, inputDocument, outputDocument, Map.of()).map(result -> {
                if (result.has("isError") && !result.path("isError").isBoolean())
                    throw GenerationException.beforeSend("TOOL_MCP_RESULT_INVALID");
                if (result.path("isError").asBoolean(false))
                    throw GenerationException.beforeSend("TOOL_MCP_TOOL_ERROR");
                var structured = result.path("structuredContent");
                if (!structured.isObject() || structured.size() != 1 || !structured.path("status").isString()
                        || !STATUSES.contains(structured.path("status").asString()) || !result.path("content").isArray()
                        || result.path("content").size() > 1)
                    throw GenerationException.beforeSend("TOOL_MCP_RESULT_UNSUPPORTED");
                for (var content : result.path("content")) {
                    if (!content.path("type").asString().equals("text") || !content.path("text").isString()
                            || !CanonicalJson.normalize(streamableTransport.parse(content.path("text").asString()).toString()).equals(CanonicalJson.normalize(structured.toString())))
                        throw GenerationException.beforeSend("TOOL_MCP_RESULT_UNSUPPORTED");
                }
                if (result.properties().stream().anyMatch(e -> !Set.of("content", "structuredContent", "isError", "_meta").contains(e.getKey())))
                    throw GenerationException.beforeSend("TOOL_MCP_RESULT_UNSUPPORTED");
                return new ToolResult(call.call().request().input().callId(), ToolResult.Outcome.SUCCEEDED,
                        object(Map.of("status", text(structured.path("status").asString()))), List.of(), List.of(), ExecutionError.SideEffect.NONE, null);
            });
        }).onErrorResume(error -> {
            String traceId = call.call().request().context().traceId();
            if (!(error instanceof GenerationException) && !(error instanceof AccessDeniedException))
                log.error("MCP tool failed, invocationId={}, traceId={}, type={}", call.call().request().context().executionId(),
                        traceId, error.getClass().getName(), com.arte.core.diagnostics.SafeExceptionDiagnostics.forLogging(error));
            String code = error instanceof AccessDeniedException ? "TOOL_ACCESS_DENIED" : error instanceof GenerationException known
                    ? known.error(traceId).code() : "TOOL_MCP_RESPONSE_FAILED";
            var failure = new ExecutionError(code, ExecutionError.Phase.INVOCATION, false, ExecutionError.SideEffect.NONE,
                    ExecutionError.Certainty.KNOWN, traceId);
            return Mono.just(new ToolResult(call.call().request().input().callId(), ToolResult.Outcome.FAILED, null, List.of(), List.of(), failure.sideEffect(), failure));
        });
    }

    private static URI origin(URI endpoint) {
        try {
            return new URI(endpoint.getScheme(), null, endpoint.getHost(), endpoint.getPort(), null, null, null);
        } catch (java.net.URISyntaxException error) {
            throw new IllegalArgumentException("Invalid HTTP origin");
        }
    }

    private DefinitionRef ref(String type, String id) {
        return new DefinitionRef(type, id, connection.definition().version());
    }

    private static StructuredValue.StringValue text(String value) {
        return new StructuredValue.StringValue(value);
    }

    private static StructuredValue.ObjectValue object(Map<String, StructuredValue> values) {
        return new StructuredValue.ObjectValue(values);
    }

    private ToolSchemaDefinition schema(String id, StructuredValue.ObjectValue document) {
        return new ToolSchemaDefinition(ref("schema", id), document, ToolCatalogDigests.schema(document));
    }
}
