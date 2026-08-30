package com.nip.ai.web.controller;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 抽象 Controller
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/7/12 22:35 ✾
 **/
@Slf4j
public abstract class AbstractStreamController {

    @Resource
    private ObjectMapper objectMapper;

    /**
     * 模板方法：将 Flux<String> 包装为 SSE 流式响应
     * 使用 SSE（text/event-stream）格式向前端推送 AI 回复
     * 重点：在 Web MVC 中，要使用 SseEmitter，使用 Flux<String> 返回，前端不会流式输出！
     *
     * @param flux     上游数据流
     * @param response HTTP 响应
     * @return SseEmitter
     */
    protected SseEmitter executeSseStream(Flux<@NonNull String> flux, HttpServletResponse response) {
        setSseHeaders(response);
        // -1L 表示永不超时，由上游控制生命周期
        SseEmitter emitter = new SseEmitter(-1L);
        AtomicBoolean completed = new AtomicBoolean(false);

        // 注册超时/完成/错误回调，防止资源泄漏
        emitter.onTimeout(() -> {
            log.warn("SSE emitter 超时");
            completed.set(true);
        });
        emitter.onCompletion(() -> completed.set(true));

        flux.publishOn(Schedulers.boundedElastic()) // 切换到有界弹性线程池，避免阻塞 Reactor 核心线程
                .doOnError(e -> log.error("流式响应异常", e))
                .subscribe(
                        chunk -> {
                            if (!completed.get()) {
                                sendChunk(emitter, chunk);
                            }
                        },
                        error -> {
                            if (!completed.get()) {
                                sendErrorAndComplete(emitter, error);
                            }
                        },
                        () -> {
                            if (!completed.get()) {
                                emitter.complete();
                            }
                        }
                );
        return emitter;
    }

    private void setSseHeaders(HttpServletResponse response) {
        // 设置 SSE 相关的响应头
        response.setHeader("X-Accel-Buffering", "no"); // 禁用 Nginx 缓冲
        response.setHeader("Cache-Control", "no-cache, no-transform"); // 禁用缓存
        response.setHeader("Connection", "keep-alive"); // 保持长连接
        response.setContentType("text/event-stream;charset=UTF-8"); // 设置内容类型为 SSE
    }

    private void sendChunk(SseEmitter emitter, String chunk) {
        try {
            emitter.send(SseEmitter.event().data(chunk).build()); // 发送数据块
        } catch (Exception e) {
            log.error("SSE send 失败，主动关闭 emitter", e);
            emitter.completeWithError(e);
        }
    }

    private void sendErrorAndComplete(SseEmitter emitter, Throwable error) {
        try {
            // 安全地序列化错误信息，避免注入风险
            String errorJson = objectMapper.writeValueAsString(
                    Map.of(
                            "error", true,
                            "errorMessage", sanitizeMessage(error),
                            "done", true
                    )
            );
            emitter.send(SseEmitter.event().data(errorJson).build());
        } catch (Exception ignored) {
            // 错误响应发送失败，静默处理
        }
        emitter.completeWithError(error);
    }

    /**
     * 对外屏蔽敏感异常信息
     */
    protected String sanitizeMessage(Throwable error) {
        if (error instanceof IllegalArgumentException || error instanceof IllegalStateException) {
            return error.getMessage();
        }
        return "服务器内部错误，请稍后重试";
    }
}