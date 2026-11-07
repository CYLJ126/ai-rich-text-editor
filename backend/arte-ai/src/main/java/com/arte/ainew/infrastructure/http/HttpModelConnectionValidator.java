package com.arte.ainew.infrastructure.http;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.ainew.pojo.model.ModelDrafts.Probe;
import com.arte.ainew.pojo.model.ModelDrafts.ProbeResult;
import com.arte.ainew.spi.model.ModelConnectionValidator;
import io.netty.channel.ChannelOption;
import io.netty.resolver.AddressResolverGroup;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Semaphore;

/**
 * 基于审核连接模板执行个人模型连接验证的 HTTP 适配器，仅检查认证及远端模型列表。
 * <p>
 * 在受信基础路径追加 /models，完整 /chat/completions 地址先移除该后缀；携带本次授权领取的个人密钥发送 GET。
 * 复用精确 origin 白名单、地址检查和 DNS 固定策略，禁止重定向及自动重试；DNS 解析在阻塞调度线程执行。
 * 单次网络总时限 15 秒，响应不超过模板上限及 64 KiB 的较小值，并发不超过部署连接数上限且最多 8 个。
 * <p>
 * 不建立聊天轮次或调用生成接口，不验证流式、工具调用和上下文容量，也不操作模型发布及预算。
 * 只返回受限结果码，不输出远端响应、密钥或异常详情；取消、超时和完成时释放相应容量及网络解析资源。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 15:55 ✾
 */
public final class HttpModelConnectionValidator implements ModelConnectionValidator {

    private final HttpEgressPolicy egress;
    private final Semaphore capacity;
    private final JsonMapper json = JsonMapper.builder().build();

    public HttpModelConnectionValidator(int maxConcurrentProbes, HttpEgressPolicy egress) {
        ContractChecks.range(maxConcurrentProbes, "maxConcurrentProbes", 1, 8);
        this.egress = Objects.requireNonNull(egress);
        capacity = new Semaphore(maxConcurrentProbes);
    }

    @Override
    public Mono<ProbeResult> validate(Probe probe) {
        return Mono.defer(() -> {
            if (!capacity.tryAcquire()) {
                return Mono.just(new ProbeResult(false, "REMOTE_UNAVAILABLE"));
            }
            return execute(probe).timeout(Duration.ofSeconds(15)).onErrorReturn(new ProbeResult(false, "REMOTE_UNAVAILABLE"))
                    .doFinally(ignored -> capacity.release());
        });
    }

    /**
     * 实际执行一次探测；调用方已取得容量，整个过程不得包在数据库事务或治理锁回调中。
     */
    private Mono<ProbeResult> execute(Probe probe) {
        return Mono.fromCallable(() -> {
            URI endpoint = probe.connection().endpoint();
            if (!probe.connection().protocol().equals(ChatCompletionsSseProtocolAdapter.DEFINITION)) {
                throw new IllegalArgumentException("Unsupported validation protocol");
            }
            return egress.resolveAddresses(endpoint);
        }).subscribeOn(Schedulers.boundedElastic()).flatMap(addresses -> {
            ConnectionDefinition connection = probe.connection();
            URI endpoint = connection.endpoint();
            egress.validateOrigin(endpoint);
            AddressResolverGroup<InetSocketAddress> resolver = HttpEgressPolicy.pinnedResolver(endpoint, addresses);
            HttpClient http = HttpClient.newConnection().resolver(resolver).followRedirect(false).disableRetry(true)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.clamp(connection.connectTimeout().toMillis(), 1, 5000))
                    .responseTimeout(Duration.ofSeconds(10));
            int max = Math.min(65536, connection.maxResponseBytes());
            WebClient client = WebClient.builder().clientConnector(new ReactorClientHttpConnector(http))
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(max)).build();
            String path = endpoint.toASCIIString();
            if (path.endsWith("/chat/completions")) {
                path = path.substring(0, path.length() - "/chat/completions".length());
            }
            URI models = URI.create(path.replaceAll("/+$", "") + "/models");
            return client.get().uri(models).headers(h -> h.setBearerAuth(probe.token())).accept(MediaType.APPLICATION_JSON)
                    .exchangeToMono(response -> {
                        int status = response.statusCode().value();
                        if (status == 401 || status == 403) {
                            return response.releaseBody().thenReturn(new ProbeResult(false, "AUTHENTICATION_FAILED"));
                        }
                        if (status != 200) {
                            return response.releaseBody().thenReturn(new ProbeResult(false, "REMOTE_UNAVAILABLE"));
                        }
                        if (response.headers().contentLength().orElse(0) > max || !MediaType.APPLICATION_JSON.isCompatibleWith(
                                response.headers().contentType().orElse(MediaType.APPLICATION_OCTET_STREAM))) {
                            return response.releaseBody().thenReturn(new ProbeResult(false, "INVALID_RESPONSE"));
                        }
                        return DataBufferUtils.join(response.bodyToFlux(org.springframework.core.io.buffer.DataBuffer.class), max)
                                .map(buffer -> {
                                    try {
                                        byte[] bytes = new byte[buffer.readableByteCount()];
                                        buffer.read(bytes);
                                        JsonNode root = json.readTree(bytes);
                                        JsonNode data = root.get("data");
                                        if (data == null || !data.isArray()) {
                                            return new ProbeResult(false, "INVALID_RESPONSE");
                                        }
                                        for (JsonNode model : data) {
                                            if (model.path("id").isString() && model.path("id").asString().equals(probe.remoteModel())) {
                                                return new ProbeResult(true, "MODEL_FOUND");
                                            }
                                        }
                                        return new ProbeResult(false, "MODEL_NOT_FOUND");
                                    } catch (RuntimeException invalid) {
                                        return new ProbeResult(false, "INVALID_RESPONSE");
                                    } finally {
                                        DataBufferUtils.release(buffer);
                                    }
                                }).defaultIfEmpty(new ProbeResult(false, "INVALID_RESPONSE"));
                    }).doFinally(ignored -> resolver.close());
        });
    }
}
