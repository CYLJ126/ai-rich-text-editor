package com.arte.ainew.tool.management;

import com.sun.net.httpserver.HttpServer;
import org.junit.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 显式手动运行的隔离页面验收：固定测试主体、真实 MVC 和 H2；不连接业务数据库或执行工具。
 */
public class ToolBrowserAcceptance extends ToolManagementHttpTest {
    @Test
    public void browserBridge() throws Exception {
        var allowed = Set.of("listTools", "getTool", "listBindings", "getBinding", "prepareDefaultBinding",
                "saveBinding", "setBindingState", "previewBindingUpgrade", "upgradeBinding", "getBindingHistory");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 15217), 0);
        server.createContext("/arte/ai-new/tool/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                if (!"POST".equals(exchange.getRequestMethod()) || !allowed.contains(path.substring(path.lastIndexOf('/') + 1))) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                byte[] input = exchange.getRequestBody().readNBytes(1_100_001);
                if (input.length > 1_100_000) {
                    exchange.sendResponseHeaders(413, -1);
                    return;
                }
                var request = post(path.substring("/arte".length())).contentType("application/json").content(input)
                        .principal(new UsernamePasswordAuthenticationToken("alice", "unused", List.of()));
                for (var header : List.of("Idempotency-Key", "Accept-Language")) {
                    var value = exchange.getRequestHeaders().getFirst(header);
                    if (value != null) request.header(header, value);
                }
                var result = browserMvc().perform(request).andReturn();
                if (result.getRequest().isAsyncStarted()) {
                    result.getAsyncResult(15000);
                    result = browserMvc().perform(asyncDispatch(result)).andReturn();
                }
                var output = result.getResponse().getContentAsByteArray();
                exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
                exchange.sendResponseHeaders(result.getResponse().getStatus(), output.length);
                exchange.getResponseBody().write(output);
            } catch (Exception failure) {
                var body = "{\"success\":false,\"code\":\"305002\",\"desc\":\"隔离验收桥故障\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(503, body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        });
        var ready = Path.of("target/tool-browser-ready");
        var stop = Path.of("target/tool-browser-stop");
        Files.deleteIfExists(stop);
        server.start();
        Files.writeString(ready, "127.0.0.1:15217");
        long deadline = System.nanoTime() + Duration.ofMinutes(15).toNanos();
        try {
            while (!Files.exists(stop) && System.nanoTime() < deadline) Thread.sleep(250);
        } finally {
            server.stop(0);
            Files.deleteIfExists(ready);
            Files.deleteIfExists(stop);
        }
    }
}
