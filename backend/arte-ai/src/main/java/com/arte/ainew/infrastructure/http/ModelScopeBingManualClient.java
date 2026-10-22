package com.arte.ainew.infrastructure.http;

import com.arte.ainew.pojo.control.ConnectionDefinition;
import io.netty.channel.ChannelOption;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.PrintStream;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bing main 专用手工客户端，不作为 Spring Bean 或工具执行器登记。
 * 每次运行私有会话、有界等待、无重试或重定向，不改变正式 transport 的 Schema 校验。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/22 11:00 ✾
 */
final class ModelScopeBingManualClient implements AutoCloseable {

    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE = new ParameterizedTypeReference<>() {
    };
    private final JsonMapper json = GenerationJson.mapper(65536);
    private final ConnectionProvider pool = ConnectionProvider.create("bing-manual", 1);
    private final AtomicReference<String> session = new AtomicReference<>();
    private final AtomicInteger sequence = new AtomicInteger();
    private final WebClient client;
    private final ConnectionDefinition connection;
    private boolean initialized;

    private ModelScopeBingManualClient(ConnectionDefinition connection) {
        this.connection = connection;
        var http = HttpClient.create(pool)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(connection.connectTimeout().toMillis()))
                .responseTimeout(connection.responseTimeout()).disableRetry(true).followRedirect(false);
        client = WebClient.builder().clientConnector(new ReactorClientHttpConnector(http))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(connection.maxResponseBytes())).build();
    }

    static JsonNode run(ConnectionDefinition target, String query, PrintStream output) {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("搜索问题不能为空");
        // 专属 URL 的路径和会话标识不输出到控制台。
        output.println("手工 MCP 调试，目标主机=" + target.endpoint().getHost() + "，问题=" + query);
        output.println("此入口直接调试远端，不代表平台 invoke／Schema 校验已通过。");
        try (ModelScopeBingManualClient client = new ModelScopeBingManualClient(target)) {
            output.println("[1/4] initialize");
            JsonNode initialized = client.request("initialize", Map.of("protocolVersion", McpStreamableHttpTransport.VERSION,
                    "capabilities", Map.of(), "clientInfo", Map.of("name", "arte-bing-manual", "version", "1")));
            String version = initialized.path("protocolVersion").asString();
            output.println("服务协议版本=" + version);
            if (!McpStreamableHttpTransport.VERSION.equals(version))
                throw new IllegalStateException("服务协议版本与当前客户端不兼容");
            output.println("[2/4] notifications/initialized");
            client.initialized();
            output.println("[3/4] tools/list");
            JsonNode catalog = client.request("tools/list", Map.of());
            if (!catalog.path("tools").isArray() || catalog.has("nextCursor"))
                throw new IllegalStateException("需要完整的 tools/list；此 Demo 不处理分页");
            JsonNode selected = null;
            for (JsonNode tool : catalog.path("tools")) {
                if ("bing_search".equals(tool.path("name").asString())) {
                    if (selected != null) throw new IllegalStateException("远端存在重复的 bing_search 工具");
                    selected = tool;
                }
            }
            if (selected == null) throw new IllegalStateException("远端 tools/list 中没有 bing_search");
            output.println("远端 inputSchema：\n" + client.pretty(selected.path("inputSchema")));
            output.println("远端 outputSchema：\n" + (selected.has("outputSchema")
                    ? client.pretty(selected.path("outputSchema")) : "未声明（现有正式 transport 的严格比较会拒绝）"));
            output.println("[4/4] tools/call bing_search，count=10，offset=0");
            JsonNode result = client.request("tools/call", Map.of("name", "bing_search",
                    "arguments", Map.of("query", query, "count", 10, "offset", 0)));
            output.println("MCP result：\n" + client.pretty(result));
            if (result.has("isError") && !result.path("isError").isBoolean())
                throw new IllegalStateException("远端 isError 不是布尔值");
            if (result.path("isError").asBoolean(false))
                throw new IllegalStateException("远端工具返回 isError=true，详见上方 result");
            if (!result.path("content").isArray()) throw new IllegalStateException("远端没有返回 content 数组");
            for (JsonNode item : result.path("content")) {
                if ("text".equals(item.path("type").asString()) && item.path("text").isString())
                    output.println("搜索正文：\n" + item.path("text").asString());
            }
            return result;
        }
    }

    private JsonNode request(String method, Map<String, Object> parameters) {
        return exchange(method, parameters, sequence.incrementAndGet()).block(connection.responseTimeout().plusSeconds(1));
    }

    private void initialized() {
        initialized = true;
        exchange("notifications/initialized", Map.of(), null).block(connection.responseTimeout().plusSeconds(1));
    }

    private String pretty(JsonNode value) {
        return json.writerWithDefaultPrettyPrinter().writeValueAsString(value);
    }

    private Mono<JsonNode> exchange(String method, Map<String, Object> parameters, Integer id) {
        var message = new LinkedHashMap<String, Object>();
        message.put("jsonrpc", "2.0");
        if (id != null) message.put("id", id);
        message.put("method", method);
        message.put("params", parameters);
        byte[] bytes = json.writeValueAsBytes(message);
        if (bytes.length > 8192) return Mono.error(new IllegalArgumentException("MCP 请求超过 8 KiB"));
        var request = client.post().uri(connection.endpoint()).contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM);
        if (initialized) request.header("MCP-Protocol-Version", McpStreamableHttpTransport.VERSION);
        if (session.get() != null) request.header("Mcp-Session-Id", session.get());
        return request.bodyValue(bytes).exchangeToMono(response -> {
                    int status = response.statusCode().value();
                    if (id == null ? status != 202 && status != 204 : status != 200)
                        return response.releaseBody().then(Mono.error(new IllegalStateException(method + " HTTP " + status)));
                    var sessions = response.headers().header("Mcp-Session-Id");
                    if (!sessions.isEmpty()) {
                        String value = sessions.getFirst();
                        if (sessions.size() != 1 || value.isEmpty() || value.length() > 256
                                || !value.chars().allMatch(c -> c >= 0x21 && c <= 0x7e)
                                || session.get() != null && !session.get().equals(value))
                            return response.releaseBody().then(Mono.error(new IllegalStateException("MCP session 不合法或发生变化")));
                        session.set(value);
                    }
                    if (id == null) return response.releaseBody().then(Mono.<JsonNode>empty());
                    var type = response.headers().contentType().orElse(MediaType.APPLICATION_OCTET_STREAM);
                    if (MediaType.APPLICATION_JSON.isCompatibleWith(type))
                        return response.bodyToMono(String.class).map(body -> envelope(json.readTree(body), id));
                    if (MediaType.TEXT_EVENT_STREAM.isCompatibleWith(type)) {
                        var events = new AtomicInteger();
                        return response.bodyToFlux(SSE).<JsonNode>handle((event, sink) -> {
                            if (events.incrementAndGet() > 64) throw new IllegalStateException("MCP SSE 事件超过 64 条");
                            if (event.data() == null || event.data().isBlank()) return;
                            var value = json.readTree(event.data());
                            if (value.has("method") && !value.has("id")) return;
                            sink.next(envelope(value, id));
                        }).next();
                    }
                    return response.releaseBody().then(Mono.error(new IllegalStateException("MCP 响应类型不支持：" + type)));
                }).switchIfEmpty(id == null ? Mono.empty() : Mono.error(new IllegalStateException(method + " 未返回 JSON-RPC 结果")))
                .timeout(connection.responseTimeout());
    }

    private JsonNode envelope(JsonNode value, int id) {
        if (!value.isObject() || !"2.0".equals(value.path("jsonrpc").asString())
                || !value.path("id").isIntegralNumber() || value.path("id").asInt() != id || value.has("method")
                || value.has("result") == value.has("error"))
            throw new IllegalStateException("MCP JSON-RPC 响应格式或 id 不匹配");
        if (value.has("error"))
            throw new IllegalStateException("MCP JSON-RPC error，code=" + value.path("error").path("code").asInt());
        if (!value.path("result").isObject()) throw new IllegalStateException("MCP result 必须为对象");
        return value.path("result");
    }

    @Override
    public void close() {
        try {
            if (session.get() != null && initialized)
                client.delete().uri(connection.endpoint()).header("Mcp-Session-Id", session.get())
                        .header("MCP-Protocol-Version", McpStreamableHttpTransport.VERSION)
                        .exchangeToMono(response -> response.releaseBody())
                        .timeout(Duration.ofMillis(500)).onErrorComplete().block(Duration.ofSeconds(1));
        } finally {
            pool.dispose();
        }
    }
}
