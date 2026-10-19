package com.arte.app.platform.persistence;

import com.arte.ainew.application.execution.InvocationDispatchWorker;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 显式验收桥：固定测试会话，正式工具／平台权限／MVC／MySQL；不启动 JWT 签名过滤器或访问业务库。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/19 11:18 ✾
 */
class ToolPlatformBrowserAcceptanceTest {
    @Test
    void browserBridge() throws Exception {
        assertNotNull(System.getProperty("platform.test.mysql"), "Browser acceptance requires isolated MySQL");
        try (var rig = new ToolAcceptanceRig()) {
            rig.context().run(c -> {
                assertNull(c.getStartupFailure());
                var mvc = rig.mvc(c);
                rig.publish(mvc);
                var allowed = Set.of("tool/listTools", "tool/getTool", "tool/listBindings", "tool/getBinding",
                        "tool/prepareDefaultBinding", "tool/previewInvocation", "tool/invokeTool", "tool/cancelToolInvocation",
                        "invocation/getInvocationStatus", "invocation/getInvocationResult");
                var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 15219), 0);
                server.createContext("/arte/ai-new/", exchange -> {
                    try {
                        String path = exchange.getRequestURI().getPath().substring("/arte/ai-new/".length());
                        byte[] output;
                        int status;
                        if (path.equals("fixture") && exchange.getRequestMethod().equals("GET")) {
                            output = rig.json.writeValueAsBytes(Map.of("userId", rig.base.f.actor.principal().subjectId(), "workspace",
                                    Map.of("scope", rig.scope, "name", "S2-09 验收空间", "state", 0, "currentRole", 0, "version", 0)));
                            status = 200;
                        } else if (allowed.contains(path) && exchange.getRequestMethod().equals("POST")) {
                            byte[] body = exchange.getRequestBody().readNBytes(1_100_001);
                            if (body.length > 1_100_000) {
                                exchange.sendResponseHeaders(413, -1);
                                return;
                            }
                            var result = rig.request(mvc, path, new String(body, StandardCharsets.UTF_8), exchange.getRequestHeaders().getFirst("Idempotency-Key"));
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
                        exchange.sendResponseHeaders(503, -1);
                    } finally {
                        exchange.close();
                    }
                });
                var ready = Path.of("target/tool-platform-browser-ready");
                var stop = Path.of("target/tool-platform-browser-stop");
                Files.deleteIfExists(stop);
                server.start();
                Files.writeString(ready, "127.0.0.1:15219");
                try {
                    long deadline = System.nanoTime() + Duration.ofMinutes(15).toNanos();
                    var worker = c.getBean(InvocationDispatchWorker.class);
                    while (!Files.exists(stop) && System.nanoTime() < deadline) {
                        worker.pollOnce().block(Duration.ofSeconds(15));
                        Thread.sleep(250);
                    }
                } finally {
                    server.stop(0);
                    Files.deleteIfExists(ready);
                    Files.deleteIfExists(stop);
                    System.out.println("S209_BROWSER invocations=" + rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_invocation", Integer.class)
                            + " attempts=" + rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_attempt", Integer.class)
                            + " reservations=" + rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_reservation", Integer.class));
                }
            });
        }
    }
}
