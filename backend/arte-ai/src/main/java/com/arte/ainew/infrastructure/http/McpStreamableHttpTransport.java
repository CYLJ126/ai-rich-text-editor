package com.arte.ainew.infrastructure.http;

import com.arte.ainew.context.ExecutionRuntimeContext;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.ainew.serialization.CanonicalJson;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Mono;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * 单次 TOOL 的私有 MCP 生命周期；每条消息均重新获取受控连接，不共享会话、不恢复或重发 tools/call。
 * JSON 与 SSE 只形成一个最终 JSON-RPC 响应；远端指令、请求与未实现的协议能力不能提升本机权限。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/21 12:50 ✾
 */
public final class McpStreamableHttpTransport {

    public static final String VERSION = "2025-06-18";
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE = new ParameterizedTypeReference<>() {
    };
    private static final int MAX_BYTES = 65536;
    private final HttpConnectionRuntime runtime;
    private final JsonMapper json = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(24).maxStringLength(MAX_BYTES).maxNumberLength(64).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public McpStreamableHttpTransport(HttpConnectionRuntime runtime) {
        this.runtime = java.util.Objects.requireNonNull(runtime);
    }

    public JsonNode parse(String value) {
        return json.readTree(value);
    }

    private static final class Session {
        private String id;
        private boolean initialized;
        private final String requestPrefix = UUID.randomUUID().toString();

        @Override
        public String toString() {
            return "McpSession[REDACTED]";
        }
    }

    /**
     * 先握手与校验固定目录，再调用指定工具；供应商列表不被直接发布为本地工具。
     */
    public Mono<JsonNode> call(ConnectionDefinition connection, ExecutionRuntimeContext context, String name,
                               JsonNode inputSchema, JsonNode outputSchema, Map<String, Object> arguments) {
        return Mono.usingWhen(Mono.fromSupplier(Session::new), session ->
                        request(connection, context, session, "initialize", Map.of("protocolVersion", VERSION, "capabilities", Map.of(),
                                "clientInfo", Map.of("name", "arte-ai-tool", "version", "1")), "init")
                                .flatMap(result -> {
                                    require(result.path("protocolVersion").asString().equals(VERSION), "TOOL_MCP_VERSION_UNSUPPORTED");
                                    require(result.path("capabilities").path("tools").isObject() && result.path("serverInfo").path("name").isString()
                                            && result.path("serverInfo").path("version").isString(), "TOOL_MCP_CAPABILITY_UNSUPPORTED");
                                    session.initialized = true;
                                    return post(connection, context, session, Map.of("jsonrpc", "2.0", "method", "notifications/initialized"), null);
                                }).then(request(connection, context, session, "tools/list", Map.of(), "list"))
                                .flatMap(list -> {
                                    require(list.path("tools").isArray() && list.path("tools").size() <= 64 && !list.has("nextCursor"), "TOOL_MCP_CATALOG_UNSUPPORTED");
                                    JsonNode selected = null;
                                    var names = new java.util.HashSet<String>();
                                    for (var tool : list.path("tools")) {
                                        require(tool.isObject() && tool.path("name").isString() && names.add(tool.path("name").asString()), "TOOL_MCP_CATALOG_INVALID");
                                        if (tool.path("name").asString().equals(name)) selected = tool;
                                    }
                                    require(selected != null && same(selected.path("inputSchema"), inputSchema) && same(selected.path("outputSchema"), outputSchema), "TOOL_MCP_CONTRACT_CHANGED");
                                    return request(connection, context, session, "tools/call", Map.of("name", name, "arguments", arguments), "call");
                                }), session -> close(connection, context, session), (session, error) -> close(connection, context, session),
                session -> close(connection, context, session));
    }

    private boolean same(JsonNode actual, JsonNode expected) {
        return !actual.isMissingNode() && CanonicalJson.normalize(actual.toString()).equals(CanonicalJson.normalize(expected.toString()));
    }

    private Mono<JsonNode> request(ConnectionDefinition connection, ExecutionRuntimeContext context, Session session,
                                   String method, Map<String, Object> params, String suffix) {
        String id = session.requestPrefix + ":" + suffix;
        return post(connection, context, session, Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params), id);
    }

    private Mono<JsonNode> post(ConnectionDefinition connection, ExecutionRuntimeContext context, Session session, Object message, String id) {
        return Mono.defer(() -> {
            byte[] bytes = json.writeValueAsBytes(message);
            require(bytes.length <= 8192, "TOOL_MCP_REQUEST_LIMIT");
            return Mono.usingWhen(runtime.acquireMcp(connection, context), lease -> {
                var handle = lease.handle();
                var request = handle.client().post().uri(handle.uri()).accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON);
                if (session.initialized) request.header("MCP-Protocol-Version", VERSION);
                if (session.id != null) request.header("Mcp-Session-Id", session.id);
                return request.bodyValue(bytes).exchangeToMono(response -> {
                    long[] received = {0};
                    var bounded = response.mutate().body(original -> original.map(buffer -> {
                        try {
                            received[0] += buffer.readableByteCount();
                            require(received[0] <= Math.min(MAX_BYTES, handle.maxResponseBytes()), "TOOL_MCP_RESPONSE_LIMIT");
                            byte[] copy = new byte[buffer.readableByteCount()];
                            buffer.read(copy);
                            return DefaultDataBufferFactory.sharedInstance.wrap(copy);
                        } finally {
                            DataBufferUtils.release(buffer);
                        }
                    })).build();
                    if (response.statusCode().value() != (id == null ? 202 : 200))
                        return bounded.releaseBody().onErrorComplete().then(Mono.error(fail("TOOL_MCP_HTTP_" + response.statusCode().value())));
                    var sessions = response.headers().header("Mcp-Session-Id");
                    if (!sessions.isEmpty()) {
                        String value = sessions.getFirst();
                        if (sessions.size() != 1 || value.isEmpty() || value.length() > 256
                                || !value.chars().allMatch(c -> c >= 0x21 && c <= 0x7e)
                                || session.initialized && !value.equals(session.id))
                            return bounded.releaseBody().onErrorComplete().then(Mono.error(fail("TOOL_MCP_SESSION_INVALID")));
                        session.id = value;
                    }
                    if (id == null) return bounded.bodyToMono(byte[].class).defaultIfEmpty(new byte[0]).flatMap(body ->
                            body.length == 0 ? Mono.empty() : Mono.error(fail("TOOL_MCP_NOTIFICATION_RESPONSE_INVALID")));
                    var type = response.headers().contentType().orElse(MediaType.APPLICATION_OCTET_STREAM);
                    if (MediaType.APPLICATION_JSON.isCompatibleWith(type))
                        return bounded.bodyToMono(byte[].class).map(body -> envelope(json.readTree(body), id));
                    if (MediaType.TEXT_EVENT_STREAM.isCompatibleWith(type)) {
                        int[] events = {0};
                        return bounded.bodyToFlux(SSE).<JsonNode>handle((event, sink) -> {
                            require(++events[0] <= 64, "TOOL_MCP_EVENT_LIMIT");
                            if (event.data() == null || event.data().isBlank()) return;
                            require(event.event() == null || event.event().equals("message"), "TOOL_MCP_EVENT_UNSUPPORTED");
                            var node = json.readTree(event.data());
                            if (node.has("method") && !node.has("id")) {
                                String method = node.path("method").asString();
                                require(node.path("jsonrpc").asString().equals("2.0") && (method.equals("notifications/progress") || method.equals("notifications/message")), "TOOL_MCP_NOTIFICATION_UNSUPPORTED");
                            } else sink.next(envelope(node, id));
                        }).next();
                    }
                    return bounded.releaseBody().then(Mono.error(fail("TOOL_MCP_CONTENT_TYPE_UNSUPPORTED")));
                }).switchIfEmpty(id == null ? Mono.empty() : Mono.error(fail("TOOL_MCP_RESPONSE_EMPTY")));
            }, runtime::release, (lease, error) -> runtime.release(lease), runtime::release);
        });
    }

    private JsonNode envelope(JsonNode node, String id) {
        require(node.isObject() && node.path("jsonrpc").asString().equals("2.0") && node.path("id").isString()
                && node.path("id").asString().equals(id) && !node.has("method") && node.has("result") != node.has("error"), "TOOL_MCP_RPC_INVALID");
        require(!node.has("error"), "TOOL_MCP_RPC_ERROR");
        require(node.path("result").isObject(), "TOOL_MCP_RPC_INVALID");
        return node.path("result");
    }

    /**
     * 收尾不创建新业务操作；期限／撤权／本地取消后禁止为清理延长授权，远端须配置会话 TTL。
     */
    private Mono<Void> close(ConnectionDefinition connection, ExecutionRuntimeContext context, Session session) {
        if (session.id == null) return Mono.empty();
        return Mono.usingWhen(runtime.acquireMcp(connection, context), lease -> {
            var handle = lease.handle();
            return handle.client().delete().uri(handle.uri()).header("MCP-Protocol-Version", VERSION)
                    .header("Mcp-Session-Id", session.id).exchangeToMono(response -> response.releaseBody());
        }, runtime::release, (lease, error) -> runtime.release(lease), runtime::release).timeout(Duration.ofMillis(500)).onErrorComplete();
    }

    private static GenerationException fail(String code) {
        return GenerationException.beforeSend(code);
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw fail(code);
    }
}
