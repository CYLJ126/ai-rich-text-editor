package com.arte.ainew.infrastructure.http;

import com.arte.ainew.context.ExecutionRuntimeContext;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.core.pojo.reference.DefinitionRef;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;

/**
 * 单次无认证 GET JSON
 * <p>
 * 传输组件，执行一次 HTTP GET，检查 HTTP 状态、JSON 类型和响应大小，并释放连接。
 * <p>
 * 沿用连接授权、固定 DNS、池容量及取消释放，不重试、不重定向。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/21 12:50 ✾
 */
public final class HttpJsonGetTransport {

    public static final DefinitionRef PROTOCOL = new DefinitionRef("protocol", "http-readonly-json", "v1");
    private final HttpConnectionRuntime runtime;

    public HttpJsonGetTransport(HttpConnectionRuntime runtime) {
        this.runtime = java.util.Objects.requireNonNull(runtime);
    }

    public Mono<byte[]> get(ConnectionDefinition connection, ExecutionRuntimeContext context) {
        return Mono.usingWhen(runtime.acquireReadOnly(connection, context), lease -> {
            var handle = lease.handle();
            return handle.client().get().uri(handle.uri()).accept(MediaType.APPLICATION_JSON).exchangeToMono(response -> {
                if (response.statusCode().value() != 200)
                    return response.releaseBody().then(Mono.error(GenerationException.beforeSend("TOOL_HTTP_STATUS_" + response.statusCode().value())));
                var contentType = response.headers().contentType().orElse(MediaType.APPLICATION_OCTET_STREAM);
                if (!MediaType.APPLICATION_JSON.isCompatibleWith(contentType)
                        || response.headers().contentLength().orElse(0) > handle.maxResponseBytes())
                    return response.releaseBody().then(Mono.error(GenerationException.beforeSend("TOOL_HTTP_RESPONSE_INVALID")));
                return DataBufferUtils.join(response.bodyToFlux(org.springframework.core.io.buffer.DataBuffer.class), handle.maxResponseBytes())
                        .map(buffer -> {
                            try {
                                byte[] bytes = new byte[buffer.readableByteCount()];
                                buffer.read(bytes);
                                return bytes;
                            } finally {
                                DataBufferUtils.release(buffer);
                            }
                        }).switchIfEmpty(Mono.error(GenerationException.beforeSend("TOOL_HTTP_RESPONSE_EMPTY")));
            });
        }, runtime::release, (lease, error) -> runtime.release(lease), runtime::release);
    }
}
