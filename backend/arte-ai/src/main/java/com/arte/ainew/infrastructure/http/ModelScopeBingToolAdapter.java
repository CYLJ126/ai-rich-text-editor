package com.arte.ainew.infrastructure.http;

import com.arte.ainew.application.tool.discovery.StaticToolRegistry;
import com.arte.ainew.application.tool.schema.ToolSchemaValidator;
import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.value.StructuredValue;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.ainew.pojo.control.ExecutionTarget;
import com.arte.ainew.pojo.tool.*;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.serialization.ToolCatalogDigests;
import com.arte.ainew.spi.adapter.McpToolAdapter;
import com.arte.ainew.spi.adapter.StaticToolDefinitionSource;
import com.arte.core.diagnostics.SafeExceptionDiagnostics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.util.*;

/**
 * 魔搭 MCP - 必应搜索中文
 * <p>
 * <a href="https://modelscope.cn/mcp/servers/slcatwujian/bing-cn-mcp-server">...</a>
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/21 12:50 ✾
 */
@Slf4j
public final class ModelScopeBingToolAdapter implements McpToolAdapter {

    private final ConnectionDefinition connection;
    private final McpStreamableHttpTransport streamableTransport;
    private final ToolProviderDefinition provider;
    private final ToolDefinitionRevision revision;
    private final List<ToolSchemaDefinition> schemas;
    private final JsonNode inputDocument, outputDocument;

    public static ConnectionDefinition connectionDefinition() {
        return getConnectionDef();
    }

    /**
     * 创建 MCP 状态查询工具适配器
     * <p>
     * 定义工具名称、输入／输出 Schema、免费只读策略及固定连接。
     */
    public ModelScopeBingToolAdapter(HttpConnectionRuntime bingRuntime) {
        this.streamableTransport = new McpStreamableHttpTransport(bingRuntime, false);
        this.connection = getConnectionDef();
        this.provider = getToolProvider();

        // 输入：只描述 arguments，不包含 method、params、id 或 _meta。
        var input = schema("modelscope-bing-input", """
                {
                  "type": "object",
                  "properties": {
                    "query": {
                      "type": "string",
                      "minLength": 1
                    },
                    "count": {
                      "type": "integer",
                      "minimum": 1
                    },
                    "offset": {
                      "type": "integer",
                      "minimum": 0
                    }
                  },
                  "required": ["query"],
                  "additionalProperties": false
                }
                """);

        // 输出：描述 content[0].text 解析后的业务对象。
        var output = schema("modelscope-bing-output", """
                {
                  "type": "object",
                  "properties": {
                    "query": {"type": "string"},
                    "results": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "properties": {
                          "uuid": {"type": "string"},
                          "title": {"type": "string", "minLength": 1},
                          "url": {"type": "string", "minLength": 1},
                          "snippet": {"type": "string"},
                          "displayUrl": {"type": "string"}
                        },
                        "required": ["title", "url"],
                        "additionalProperties": false
                      }
                    },
                    "totalResults": {
                      "type": "integer",
                      "minimum": 0
                    }
                  },
                  "required": ["query", "results", "totalResults"],
                  "additionalProperties": false
                }
                """);

        // 配置：Demo 的连接等配置全部写在类中，绑定不接收额外配置。
        var configuration = schema("modelscope-bing-configuration", """
                {
                  "type": "object",
                  "properties": {},
                  "additionalProperties": false
                }
                """);

        this.schemas = List.of(input, output, configuration);
        this.revision = getRevision(input, output, configuration);
        JsonMapper json = GenerationJson.mapper(65536);
        inputDocument = json.valueToTree(ToolCatalogDigests.jsonValue(input.document()));
        outputDocument = json.valueToTree(ToolCatalogDigests.jsonValue(output.document()));
    }

    private static ConnectionDefinition getConnectionDef() {
        DefinitionRef definition = new DefinitionRef("connection", "mcp-bing-cn", "1.0.1");
        String providerId = "modelscope.net";
        URI endpoint = URI.create("https://mcp.api-inference.modelscope.net/207e37952c6c4e/mcp");
        DefinitionRef credential = null;
        ConnectionDefinition.State state = ConnectionDefinition.State.ENABLED;
        Duration connectTimeout = Duration.ofSeconds(10);
        Duration responseTimeout = Duration.ofSeconds(30);
        int maxResponseBytes = 65536;
        return new ConnectionDefinition(definition, providerId, ToolDefinitionRevision.MCP_PROTOCOL, endpoint, credential, state, connectTimeout, responseTimeout, maxResponseBytes);
    }

    private ToolProviderDefinition getToolProvider() {
        String version = connection.definition().version();
        return new ToolProviderDefinition(
                new DefinitionRef("tool-provider", "modelscope.bing", version),
                ToolProviderDefinition.Kind.MCP,
                new DefinitionRef("tool-provider-adapter", "modelscope-bing-source", version),
                connection.definition()
        );
    }

    private ToolDefinitionRevision getRevision(ToolSchemaDefinition input, ToolSchemaDefinition output, ToolSchemaDefinition configuration) {
        String version = connection.definition().version();
        // 先建立工具契约；checksum 暂用合法占位值，随后计算真实摘要。
        ToolDefinition unsigned = new ToolDefinition(
                new DefinitionRef("tool", "modelscope.bing_search", version),
                provider.definition().id(),
                "必应中文搜索",
                "根据关键词搜索网页，返回标题、链接、摘要及搜索结果总数。",
                Set.of("搜索", "必应", "MCP"),
                new DefinitionRef("capability", "modelscope-bing-search", version),
                input.definition(),
                output.definition(),
                configuration.definition(),
                // Demo 配置全部写在类中，绑定配置为空对象。
                new StructuredValue.ObjectValue(Map.of()),
                // 不声明额外协议能力或执行结果核对能力。
                new ToolCapabilities(Set.of(), false),
                new ToolRiskProfile(
                        CapabilityDescriptor.SideEffect.READ_ONLY,
                        false,
                        ToolRiskProfile.Idempotency.NONE,
                        Set.of("ai:invoke"),
                        Set.of(URI.create(
                                "https://mcp.api-inference.modelscope.net"))
                ),
                // Demo：总期限 30 秒、一次尝试、输入 4 KiB、输出 32 KiB。
                new ToolExecutionPolicy(
                        Duration.ofSeconds(60),
                        1,
                        Duration.ZERO,
                        4 * 1024,
                        32 * 1024,
                        ToolExecutionPolicy.ApprovalMode.NONE
                ),
                "0".repeat(64)
        );

        // 使用真实摘要创建最终工具定义。
        ToolDefinition tool = new ToolDefinition(
                unsigned.definition(),
                unsigned.providerId(),
                unsigned.title(),
                unsigned.description(),
                unsigned.tags(),
                unsigned.capability(),
                unsigned.inputSchema(),
                unsigned.outputSchema(),
                unsigned.configurationSchema(),
                unsigned.defaultConfiguration(),
                unsigned.capabilities(),
                unsigned.risk(),
                unsigned.defaultPolicy(),
                ToolCatalogDigests.tool(unsigned)
        );

        return new ToolDefinitionRevision(
                tool,
                provider.definition(),
                new ExecutionTarget.Remote(connection.definition(), "bing_search"),
                ToolDefinitionRevision.MCP_PROTOCOL
        );
    }

    private ToolSchemaDefinition schema(String id, String json) {
        var body = (StructuredValue.ObjectValue) CanonicalJson.structured(json);

        var fields = new HashMap<>(body.fields());
        fields.put("$schema", new StructuredValue.StringValue(ToolSchemaValidator.DIALECT));

        var document = new StructuredValue.ObjectValue(fields);

        return new ToolSchemaDefinition(
                new DefinitionRef("schema", id, "1.0.0"),
                document,
                ToolCatalogDigests.schema(document)
        );
    }

    @Override
    public ToolDefinitionRevision revision() {
        return revision;
    }

    public StaticToolDefinitionSource.Definitions definitions() {
        return new StaticToolDefinitionSource.Definitions(
                schemas, List.of(revision));
    }

    public StaticToolRegistry.Registration registration() {
        return new StaticToolRegistry.Registration(
                provider,
                () -> Mono.just(definitions()),
                Map.of(),
                Map.of(),
                Map.of((ExecutionTarget.Remote) revision.target(), this)
        );
    }

    public void requireFree(ToolAdmissionInput input, ToolBindingResolution binding) {
        if (!input.bindingId().equals(binding.definition().id()) || !revision.equals(binding.tool())
                || !binding.effectiveConfiguration().equals(revision.tool().defaultConfiguration())
                || !ToolExecutionRules.supportedTarget(binding, binding.effectivePolicy()))
            throw new AccessDeniedException("Bing MCP tool is not covered by the reviewed free policy");
    }

    @Override
    public Mono<ToolResult> invoke(RemoteToolCall call) {
        return Mono.defer(() -> {
                    StructuredValue.ObjectValue arguments = call.call().request().input().arguments();
                    Map<String, Object> param = new LinkedHashMap<>();
                    arguments.fields().forEach((name, value) -> param.put(name, ToolCatalogDigests.jsonValue(value)));
                    param.putIfAbsent("count", 10);
                    param.putIfAbsent("offset", 0);
                    return streamableTransport.call(connection, call.call().runtime(), "bing_search", inputDocument, outputDocument, param)
                            .map(result -> {
                                if (result.has("isError") && !result.path("isError").isBoolean()) {
                                    throw GenerationException.beforeSend("TOOL_MCP_RESULT_INVALID");
                                }
                                if (result.path("isError").asBoolean(false)) {
                                    throw GenerationException.beforeSend("TOOL_MCP_TOOL_ERROR");
                                }

                                JsonNode content = result.path("content");
                                if (!content.isArray() || content.size() != 1) {
                                    throw GenerationException.beforeSend("TOOL_MCP_RESULT_UNSUPPORTED");
                                }

                                JsonNode item = content.get(0);
                                if (!"text".equals(item.path("type").asString())
                                        || !item.path("text").isString()) {
                                    throw GenerationException.beforeSend("TOOL_MCP_RESULT_UNSUPPORTED");
                                }

                                StructuredValue.ObjectValue output = normalizeOutput(CanonicalJson.structured(item.path("text").asString()));

                                return new ToolResult(
                                        call.call().request().input().callId(),
                                        ToolResult.Outcome.SUCCEEDED,
                                        output,
                                        List.of(),
                                        List.of(),
                                        ExecutionError.SideEffect.NONE,
                                        null
                                );
                            });
                })
                .onErrorResume(error -> {
                    log.error("Bing Search error occurred", SafeExceptionDiagnostics.forLogging(error));
                    String traceId = call.call().request().context().traceId();
                    ExecutionError failure = new ExecutionError("UNKNOWN_ERROR", ExecutionError.Phase.INVOCATION,
                            false, ExecutionError.SideEffect.NONE, ExecutionError.Certainty.KNOWN, traceId);
                    return Mono.just(new ToolResult(call.call().request().input().callId(), ToolResult.Outcome.FAILED,
                            null, List.of(), List.of(), failure.sideEffect(), failure));
                });
    }

    /**
     * 将远端搜索结果投影成本地输出契约，忽略未声明字段。
     * 字段类型及输出大小继续由平台输出 Schema 校验。
     */
    private StructuredValue.ObjectValue normalizeOutput(StructuredValue value) {
        if (!(value instanceof StructuredValue.ObjectValue source)) {
            throw GenerationException.beforeSend("TOOL_MCP_RESULT_INVALID");
        }

        Map<String, StructuredValue> output = project(
                source,
                Set.of("query", "results", "totalResults"),
                Set.of("query", "results", "totalResults")
        );

        if (!(output.get("results") instanceof StructuredValue.ArrayValue(List<StructuredValue> values))) {
            throw GenerationException.beforeSend("TOOL_MCP_RESULT_INVALID");
        }

        List<StructuredValue> normalizedResults = new ArrayList<>();

        for (StructuredValue entry : values) {
            if (!(entry instanceof StructuredValue.ObjectValue item)) {
                throw GenerationException.beforeSend("TOOL_MCP_RESULT_INVALID");
            }

            normalizedResults.add(new StructuredValue.ObjectValue(project(
                    item,
                    Set.of("title", "url"),
                    Set.of("title", "url")
            )));
        }

        output.put("results",
                new StructuredValue.ArrayValue(normalizedResults));

        return new StructuredValue.ObjectValue(output);
    }

    /**
     * 检查必填字段，并只保留本地契约允许的字段。
     */
    private Map<String, StructuredValue> project(
            StructuredValue.ObjectValue source,
            Set<String> allowed,
            Set<String> required) {

        if (!source.fields().keySet().containsAll(required)) {
            throw GenerationException.beforeSend("TOOL_MCP_RESULT_INVALID");
        }

        Map<String, StructuredValue> projected = new LinkedHashMap<>();
        for (String name : allowed) {
            StructuredValue value = source.fields().get(name);
            if (value != null) {
                projected.put(name, value);
            }
        }

        return projected;
    }

}
