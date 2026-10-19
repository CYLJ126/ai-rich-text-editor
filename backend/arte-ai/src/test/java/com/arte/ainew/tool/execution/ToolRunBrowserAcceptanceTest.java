package com.arte.ainew.tool.execution;

import com.arte.ainew.web.response.ToolManagementResponses;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.pojo.ResultContext;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 显式运行的页面验收桥：真实 MVC／H2／Worker，固定测试授权，不连接业务库。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/19 09:47 ✾
 */
public class ToolRunBrowserAcceptanceTest {

    @Test
    public void browserBridge() throws Exception {
        try (var r = new ToolSubmissionExecutionTest.Rig()) {
            var mvc = r.mvc();
            var json = JsonMapper.builder().build();
            var allowed = Set.of("/ai-new/tool/getTool", "/ai-new/tool/getBinding", "/ai-new/tool/previewInvocation",
                    "/ai-new/tool/invokeTool", "/ai-new/tool/cancelToolInvocation", "/ai-new/invocation/getInvocationStatus",
                    "/ai-new/invocation/getInvocationResult");
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 15218), 0);
            server.createContext("/arte/ai-new/", exchange -> {
                try {
                    SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of()));
                    String path = exchange.getRequestURI().getPath().substring("/arte".length());
                    byte[] output;
                    int status;
                    if (path.equals("/ai-new/fixture") && exchange.getRequestMethod().equals("GET")) {
                        output = json.writeValueAsBytes(Map.of("binding", ToolManagementResponses.binding(r.f.binding)));
                        status = 200;
                    } else if (exchange.getRequestMethod().equals("POST") && allowed.contains(path)) {
                        byte[] body = exchange.getRequestBody().readNBytes(1_100_001);
                        if (body.length > 1_100_000) {
                            exchange.sendResponseHeaders(413, -1);
                            return;
                        }
                        var result = ToolSubmissionExecutionTest.request(mvc, path, new String(body, java.nio.charset.StandardCharsets.UTF_8),
                                exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                        output = result.getResponse().getContentAsByteArray();
                        status = result.getResponse().getStatus();
                    } else {
                        exchange.sendResponseHeaders(404, -1);
                        return;
                    }
                    exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
                    exchange.sendResponseHeaders(status, output.length);
                    exchange.getResponseBody().write(output);
                } catch (Exception failure) {
                    byte[] output = json.writeValueAsBytes(ResultContext.fail(ResultCodeEnum.FAIL, Locale.CHINA));
                    exchange.sendResponseHeaders(503, output.length);
                    exchange.getResponseBody().write(output);
                } finally {
                    SecurityContextHolder.clearContext();
                    exchange.close();
                }
            });
            var ready = Path.of("target/tool-run-browser-ready");
            var stop = Path.of("target/tool-run-browser-stop");
            Files.deleteIfExists(stop);
            server.start();
            Files.writeString(ready, "127.0.0.1:15218");
            var worker = r.f.worker(r.f.dispatcher);
            long deadline = System.nanoTime() + Duration.ofMinutes(15).toNanos();
            try {
                while (!Files.exists(stop) && System.nanoTime() < deadline) {
                    worker.pollOnce().block(Duration.ofSeconds(15));
                    Thread.sleep(250);
                }
            } finally {
                server.stop(0);
                Files.deleteIfExists(ready);
                Files.deleteIfExists(stop);
                System.out.println("TOOL_RUN_BROWSER invocations=" + r.f.invocations.get() + " reservations=" + r.f.count("arte_ai_reservation"));
            }
        }
    }
}
