package com.arte.ainew.infrastructure.http;

import com.arte.ainew.application.support.InvocationTiming;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.config.NewAiGenerationProperties;
import com.arte.ainew.pojo.execution.GatewayCall;
import com.arte.ainew.spi.adapter.ConnectionRuntime;
import com.arte.ainew.spi.adapter.ProtocolAdapter;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单次 Chat Completions HTTP／SSE 交换
 * <p>
 * 复用 Spring SSE codec，不将普通断流冒充结束。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/6 14:01 ✾
 */
public final class ChatCompletionsSseProtocolAdapter<Q> implements ProtocolAdapter<Q, SseFrame, HttpConnectionRuntime.Handle> {

    public static final DefinitionRef DEFINITION = new DefinitionRef("protocol", "chat-completions-sse", "v1");
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE = new ParameterizedTypeReference<>() {};
    private final JsonMapper json;
    private final NewAiGenerationProperties properties;

    public ChatCompletionsSseProtocolAdapter(JsonMapper json, NewAiGenerationProperties properties) {
        this.json = json;
        this.properties = properties;
    }

    @Override
    public DefinitionRef definition() {
        return DEFINITION;
    }

    /**
     * 执行一次 Chat Completions HTTP／SSE 交换，订阅时才开始校验和发送。
     * <p>
     * 大致步骤：
     * <ol>
     *     <li>核对连接引用及主体归属，将请求编码为 JSON 字节并检查请求大小。</li>
     *     <li>通过受控连接句柄发送 POST，凭据注入及发送边界校验由连接运行时负责。</li>
     *     <li>为正文设置累计字节上限，消费时逐块转换并释放原始池化缓冲区。</li>
     *     <li>校验 HTTP 状态及 SSE Content-Type，异常响应释放正文后返回安全错误。</li>
     *     <li>使用 Spring SSE codec 解码，过滤心跳／空事件，检查帧大小并保留 [DONE] 标记。</li>
     * </ol>
     * 收到 [DONE] 后结束协议流；普通断流是否构成失败由供应商适配器判断。
     * 本方法不重试，Lease 的最终释放由调用方通过连接运行时管理。
     *
     * @param request 供应商适配器生成的内部请求对象
     * @param connection 当前调用借用的受控 HTTP 连接
     * @param call 本次调用的绑定、尝试及运行上下文
     * @return 按接收顺序发布数据帧或结束标记的冷流
     */
    @Override
    public Flux<SseFrame> exchange(Q request, ConnectionRuntime.Lease<HttpConnectionRuntime.Handle> connection, GatewayCall<?> call) {
        // 延迟到订阅时执行；实际发送仍受 Lease 的一次请求约束。
        return Flux.defer(() -> {
            // 借用的连接必须匹配本次绑定和执行主体。
            if (!connection.connection().equals(call.binding().connection())
                    || !connection.owner().equals(ExecutionOwner.from(call.runtime().execution()))) {
                throw GenerationException.beforeSend("CONNECTION_OWNER_MISMATCH");
            }
            byte[] body;
            try {
                body = json.writeValueAsBytes(request);
            } catch (RuntimeException ignored) {
                throw GenerationException.beforeSend("REQUEST_ENCODING_FAILED");
            }
            // 先编码并检查实际字节数，超限请求不进入 HTTP 发送。
            if (body.length > properties.maxRequestBytes()) {
                throw GenerationException.beforeSend("REQUEST_LIMIT_EXCEEDED");
            }
            var timing = InvocationTiming.start(call.runtime().execution(), call.attempt().attemptId());
            var firstFrame = new AtomicBoolean();
            var handle = connection.handle();
            // 使用句柄中的固定 URI，发送 JSON 并要求 SSE 响应。
            return handle.client().post().uri(handle.uri()).accept(MediaType.TEXT_EVENT_STREAM)
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchangeToFlux(response -> {
                        timing.mark("PROVIDER_HEADERS");
                        long[] received = {0};
                        // 转换原正文流，避免替换正文时提前消费；累计上限包含 SSE 协议开销。
                        var bounded = response.mutate().body(original -> original
                                .map(buffer -> {
                                    try {
                                        int size = buffer.readableByteCount();
                                        received[0] += size;
                                        if (received[0] > handle.maxResponseBytes()) {
                                            throw GenerationException.output("RESPONSE_LIMIT_EXCEEDED");
                                        }
                                        // 逐块转移到有界堆缓冲区，让 SSE codec 独立拥有内存。
                                        // Spring 7 字符串 codec 的超限清理可能重复释放拆分后的池化缓冲区。
                                        byte[] bytes = new byte[size];
                                        buffer.read(bytes);
                                        return DefaultDataBufferFactory.sharedInstance.wrap(bytes);
                                    } finally {
                                        // 正常复制和异常退出都释放当前原始缓冲区。
                                        DataBufferUtils.release(buffer);
                                    }
                                })).build();
                        // 错误正文只做释放，不暴露给业务错误；清理异常不覆盖原 HTTP 状态。
                        if (!response.statusCode().is2xxSuccessful()) {
                            return bounded.releaseBody().onErrorComplete().thenMany(Flux.error(GenerationException.http(response.statusCode().value())));
                        }
                        var type = response.headers().contentType();
                        // 只有声明为 SSE 的成功响应才进入流式解码。
                        if (type.isEmpty() || !MediaType.TEXT_EVENT_STREAM.isCompatibleWith(type.get())) {
                            return bounded.releaseBody().onErrorComplete().thenMany(Flux.error(GenerationException.output("INVALID_PROVIDER_CONTENT_TYPE")));
                        }
                        // 按顺序处理事件，预取设为 1，避免积压大量待映射帧。
                        return bounded.bodyToFlux(SSE).concatMap(event -> {
                            // 注释心跳、无 data 或空 data 不形成平台响应帧。
                            if (event.data() == null || event.data().isBlank()) {
                                return Flux.<SseFrame>empty();
                            }
                            String data = event.data();
                            // 按 UTF-8 字节检查单帧大小，而非 Java 字符数量。
                            if (data.getBytes(StandardCharsets.UTF_8).length > properties.maxFrameBytes()) {
                                return Flux.error(GenerationException.output("SSE_FRAME_LIMIT_EXCEEDED"));
                            }
                            if (!data.equals("[DONE]") && firstFrame.compareAndSet(false, true))
                                timing.mark("PROVIDER_FIRST_DATA_FRAME");
                            // [DONE] 显式传给供应商适配器，takeUntil 在发布它之后终止流。
                            return Flux.just(data.equals("[DONE]") ? new SseFrame.Done() : new SseFrame.Data(data));
                        }, 1).takeUntil(frame -> frame instanceof SseFrame.Done);
                    }).doFinally(signal -> timing.mark("PROVIDER_STREAM_END_" + signal.name()))
                    .contextWrite(context -> context.put(InvocationTiming.class, timing));
        });
    }
}
