package com.arte.ainew.application.gateway;

import com.arte.ainew.api.control.BindingManager;
import com.arte.ainew.api.control.CapabilityCatalog;
import com.arte.ainew.api.control.ConnectionManager;
import com.arte.ainew.infrastructure.http.GenerationException;
import com.arte.ainew.pojo.execution.GatewayCall;
import com.arte.ainew.pojo.generation.GenerationRequest;
import com.arte.ainew.pojo.generation.GenerationSignal;
import com.arte.ainew.spi.adapter.ConnectionRuntime;
import com.arte.ainew.spi.adapter.GenerationProviderAdapter;
import com.arte.ainew.spi.adapter.ProtocolAdapter;
import com.arte.ainew.spi.gateway.ModelGateway;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单次文本网关，复用控制面及适配端口；不重试、不推进 Invocation 或预算。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/6 14:01 ✾
 */
public final class DefaultModelGateway<Q, S, H> implements ModelGateway {

    private final CapabilityCatalog capabilities;
    private final BindingManager bindings;
    private final ConnectionManager connections;
    private final ConnectionRuntime<H> runtime;
    private final GenerationProviderAdapter<Q, S> provider;
    private final ProtocolAdapter<Q, S, H> protocol;
    private final Clock clock;

    public DefaultModelGateway(CapabilityCatalog capabilities, BindingManager bindings, ConnectionManager connections,
                               ConnectionRuntime<H> runtime,
                               GenerationProviderAdapter<Q, S> provider,
                               ProtocolAdapter<Q, S, H> protocol, Clock clock) {
        this.capabilities = capabilities;
        this.bindings = bindings;
        this.connections = connections;
        this.runtime = runtime;
        this.provider = provider;
        this.protocol = protocol;
        this.clock = clock;
    }

    @Override
    public Flux<GenerationSignal> generate(GatewayCall<GenerationRequest> call) {
        return Flux.defer(() -> {
            var interruption = new AtomicReference<Throwable>();
            // takeUntilOther 的 other.onError 不保证取消主流。将停止原因转换为值，
            // 先触发主流取消／usingWhen 清理，再把原原因交给 Provider 聚合失败事实。
            var stop = stopSignal(call).onErrorResume(error -> {
                interruption.set(error);
                return Mono.just(Boolean.TRUE);
            });
            var frames = capabilities.validate(call.request())
                    .then(bindings.resolve(call.request().binding(), call.request().capability(), call.runtime().execution()))
                    .flatMap(binding -> {
                        if (!binding.equals(call.binding())) {
                            return Mono.error(GenerationException.beforeSend("BINDING_CONFIGURATION_CHANGED"));
                        }
                        return connections.resolve(binding.connection(), call.runtime().execution());
                    })
                    .onErrorMap(error -> error instanceof GenerationException
                            || error instanceof org.springframework.security.access.AccessDeniedException ? error
                            : GenerationException.beforeSend("GENERATION_PREFLIGHT_REJECTED"))
                    .flatMapMany(connection -> {
                        if (!provider.providerId().equals(connection.providerId()) || !protocol.definition().equals(connection.protocol())) {
                            return Flux.error(GenerationException.beforeSend("PROVIDER_PROTOCOL_NOT_SUPPORTED"));
                        }
                        var request = provider.mapRequest(call);
                        return Flux.usingWhen(runtime.acquire(connection, call.runtime()),
                                lease -> protocol.exchange(request, lease, call), runtime::release,
                                (lease, error) -> runtime.release(lease), runtime::release);
                    }).takeUntilOther(stop).concatWith(Flux.defer(() -> interruption.get() == null
                            ? Flux.empty() : Flux.error(interruption.get())));
            return provider.mapStream(frames, call);
        });
    }

    /**
     * 绝对期限不会因持续收到增量而重置；本地取消只终止当前交互，不伪造耐久取消完成。
     */
    private Mono<Object> stopSignal(GatewayCall<GenerationRequest> call) {
        var remaining = Duration.between(clock.instant(), call.runtime().execution().deadline());
        if (remaining.isZero() || remaining.isNegative()) {
            return Mono.error(new TimeoutException("Invocation deadline exceeded"));
        }
        Mono<Object> deadline = Mono.delay(remaining).flatMap(ignored -> Mono.error(new TimeoutException("Invocation deadline exceeded")));
        Mono<Object> cancelled = call.runtime().cancellation().signal().flatMap(ignored -> Mono.error(new CancellationException("Invocation cancelled")));
        return Mono.firstWithSignal(deadline, cancelled);
    }
}
