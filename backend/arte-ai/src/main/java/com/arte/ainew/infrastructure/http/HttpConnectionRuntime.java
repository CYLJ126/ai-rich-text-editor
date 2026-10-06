package com.arte.ainew.infrastructure.http;

import com.arte.ainew.api.control.ConnectionManager;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.config.NewAiGenerationProperties;
import com.arte.ainew.context.ExecutionRuntimeContext;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.ainew.spi.adapter.ConnectionRuntime;
import com.arte.ainew.spi.credential.ConnectionCredentialResolver;
import io.netty.channel.ChannelOption;
import io.netty.resolver.AddressResolverGroup;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 有界、独立 HTTP 运行资源
 * <p>
 * 按连接版本／主体／SecretRef 隔离池，发送时重新授权并解析凭据。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/6 14:01 ✾
 */
public final class HttpConnectionRuntime implements ConnectionRuntime<HttpConnectionRuntime.Handle>, AutoCloseable {

    private final ConnectionManager connections;
    private final AdmissionAuthorization authorization;
    private final ConnectionCredentialResolver credentials;
    private final NewAiGenerationProperties properties;
    private final HttpEgressPolicy egress;
    private final Clock clock;
    private final Scheduler dns = Schedulers.newBoundedElastic(4, 256, "arte-ainew-dns");
    private final LoopResources loops = LoopResources.create("arte-ainew-http", 1, 4, true);
    private final Map<PoolKey, Pool> pools = new LinkedHashMap<>(16, 0.75f, true);
    private boolean closed;

    private record PoolKey(ConnectionDefinition definition, ExecutionOwner owner, List<InetAddress> addresses) {
    }

    private static final class Pool {
        final ConnectionProvider provider;
        final AddressResolverGroup<InetSocketAddress> resolver;
        final AtomicBoolean invalidated = new AtomicBoolean();
        int borrowed;

        Pool(ConnectionProvider provider, AddressResolverGroup<InetSocketAddress> resolver) {
            this.provider = provider;
            this.resolver = resolver;
        }
    }

    /**
     * 内部传输句柄
     * <p>
     * 无凭据 getter，不序列化；一次借用只允许一次 HTTP 请求。
     */
    public static final class Handle {
        private final WebClient client;
        private final URI uri;
        private final int maxResponseBytes;

        private Handle(WebClient client, URI uri, int maxResponseBytes) {
            this.client = client;
            this.uri = uri;
            this.maxResponseBytes = maxResponseBytes;
        }

        WebClient client() {
            return client;
        }

        URI uri() {
            return uri;
        }

        int maxResponseBytes() {
            return maxResponseBytes;
        }

        @Override
        public String toString() {
            return "HttpHandle[REDACTED]";
        }
    }

    private final class Borrowed implements Lease<Handle> {
        final PoolKey key;
        final Pool pool;
        final HttpConnectionRuntime runtimeOwner = HttpConnectionRuntime.this;
        final AtomicBoolean released = new AtomicBoolean();
        final AtomicBoolean sent = new AtomicBoolean();
        Handle handle;

        Borrowed(PoolKey key, Pool pool) {
            this.key = key;
            this.pool = pool;
        }

        @Override
        public DefinitionRef connection() {
            return key.definition().definition();
        }

        @Override
        public ExecutionOwner owner() {
            return key.owner();
        }

        void active() {
            if (released.get() || pool.invalidated.get()) {
                throw GenerationException.beforeSend("CONNECTION_LEASE_INVALID");
            }
        }

        @Override
        public Handle handle() {
            active();
            return handle;
        }

        @Override
        public String toString() {
            return "HttpLease[REDACTED]";
        }
    }

    public HttpConnectionRuntime(ConnectionManager connections, AdmissionAuthorization authorization,
                                 ConnectionCredentialResolver credentials, NewAiGenerationProperties properties, Clock clock) {
        this.connections = connections;
        this.authorization = authorization;
        this.credentials = credentials;
        this.properties = properties;
        this.clock = clock;
        this.egress = new HttpEgressPolicy(properties);
    }

    @Override
    public Mono<Lease<Handle>> acquire(ConnectionDefinition definition, ExecutionRuntimeContext runtime) {
        return Mono.<Lease<Handle>>defer(() -> {
            checkActive(runtime);
            egress.validateOrigin(definition.endpoint());
            return connections.resolve(definition.definition(), runtime.execution()).flatMap(current -> {
                if (!current.equals(definition) || current.state() != ConnectionDefinition.State.ENABLED) {
                    return Mono.error(GenerationException.beforeSend("CONNECTION_CONFIGURATION_CHANGED"));
                }
                return Mono.fromCallable(() -> egress.validateAddresses(definition.endpoint(), InetAddress.getAllByName(definition.endpoint().getHost())))
                        .subscribeOn(dns).timeout(properties.acquireTimeout())
                        .map(addresses -> borrow(definition, runtime, addresses));
            });
        }).onErrorMap(error -> error instanceof GenerationException
                || error instanceof org.springframework.security.access.AccessDeniedException ? error
                : GenerationException.beforeSend("CONNECTION_ACQUIRE_FAILED"));
    }

    private Borrowed borrow(ConnectionDefinition definition, ExecutionRuntimeContext runtime, List<InetAddress> addresses) {
        checkActive(runtime);
        var key = new PoolKey(definition, ExecutionOwner.from(runtime.execution()), addresses);
        Pool pool = reserve(key);
        var lease = new Borrowed(key, pool);
        try {
            int connectMillis = Math.clamp(definition.connectTimeout().toMillis(), 1, Integer.MAX_VALUE);
            var http = HttpClient.create(pool.provider).runOn(loops).resolver(pool.resolver)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectMillis)
                    .responseTimeout(definition.responseTimeout()).disableRetry(true).followRedirect(false)
                    .httpResponseDecoder(spec -> spec.maxHeaderSize(16_384));
            String base = definition.endpoint().toString();
            var uri = URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/chat/completions");
            var client = WebClient.builder().clientConnector(new ReactorClientHttpConnector(http))
                    .codecs(codecs -> {
                        codecs.defaultCodecs().maxInMemorySize(properties.maxFrameBytes());
                        codecs.defaultCodecs().jacksonJsonEncoder(new JacksonJsonEncoder(GenerationJson.mapper(properties.maxFrameBytes())));
                    })
                    .filter((request, next) -> Mono.defer(() -> {
                        lease.active();
                        checkActive(runtime);
                        if (!request.url().equals(uri) || !lease.sent.compareAndSet(false, true)) {
                            return Mono.error(GenerationException.beforeSend("CONNECTION_REQUEST_NOT_ALLOWED"));
                        }
                        return connections.resolve(definition.definition(), runtime.execution()).flatMap(current -> {
                            if (!current.equals(definition)) {
                                return Mono.error(GenerationException.beforeSend("CONNECTION_CONFIGURATION_CHANGED"));
                            }
                            lease.active();
                            checkActive(runtime);
                            if (definition.credential() == null) {
                                return Mono.error(GenerationException.beforeSend("CREDENTIAL_NOT_AVAILABLE"));
                            }
                            return credentials.resolve(definition.credential(), runtime.execution())
                                    .switchIfEmpty(Mono.error(GenerationException.beforeSend("CREDENTIAL_NOT_AVAILABLE")))
                                    .flatMap(credential -> {
                                        lease.active();
                                        checkActive(runtime);
                                        var authenticated = ClientRequest.from(request).headers(headers -> headers.setBearerAuth(credential.token())).build();
                                        return next.exchange(authenticated);
                                    });
                        });
                    })).build();
            lease.handle = new Handle(client, uri, definition.maxResponseBytes());
            return lease;
        } catch (RuntimeException error) {
            releaseNow(lease);
            throw error;
        }
    }

    private synchronized Pool reserve(PoolKey key) {
        if (closed) {
            throw GenerationException.beforeSend("CONNECTION_RUNTIME_CLOSED");
        }
        var pool = pools.get(key);
        if (pool == null) {
            if (pools.size() >= properties.maxPools()) {
                var idle = pools.entrySet().stream().filter(entry -> entry.getValue().borrowed == 0).findFirst()
                        .orElseThrow(() -> GenerationException.beforeSend("CONNECTION_CAPACITY_EXCEEDED"));
                dispose(idle.getValue());
                pools.remove(idle.getKey());
            }
            pool = new Pool(ConnectionProvider.builder("arte-ainew-" + java.util.UUID.randomUUID())
                    .maxConnections(properties.connectionsPerPool()).pendingAcquireMaxCount(properties.pendingAcquires())
                    .pendingAcquireTimeout(properties.acquireTimeout()).maxIdleTime(Duration.ofMinutes(1))
                    .maxLifeTime(Duration.ofMinutes(5)).build(),
                    HttpEgressPolicy.pinnedResolver(key.definition().endpoint(), key.addresses()));
            pools.put(key, pool);
        }
        if (pool.borrowed >= properties.connectionsPerPool() + properties.pendingAcquires()) {
            throw GenerationException.beforeSend("CONNECTION_CAPACITY_EXCEEDED");
        }
        pool.borrowed++;
        return pool;
    }

    @Override
    public Mono<Void> release(Lease<Handle> borrowed) {
        return Mono.fromRunnable(() -> releaseNow(borrowed));
    }

    private void releaseNow(Lease<Handle> borrowed) {
        if (!(borrowed instanceof HttpConnectionRuntime.Borrowed lease) || lease.runtimeOwner != this) {
            throw new IllegalArgumentException("Foreign HTTP lease");
        }
        if (lease.released.compareAndSet(false, true)) {
            synchronized (this) {
                lease.pool.borrowed--;
            }
        }
    }

    @Override
    public Mono<Void> invalidate(DefinitionRef connection, ExecutionContext context) {
        return authorization.require(context, AdmissionAuthorization.INVOKE).doOnNext(current -> {
            var owner = ExecutionOwner.from(current);
            synchronized (this) {
                pools.entrySet().removeIf(entry -> {
                    if (!entry.getKey().owner().equals(owner) || !entry.getKey().definition().definition().equals(connection)) {
                        return false;
                    }
                    dispose(entry.getValue());
                    return true;
                });
            }
        }).then();
    }

    private void checkActive(ExecutionRuntimeContext runtime) {
        try {
            runtime.checkActive(clock);
        } catch (java.util.concurrent.TimeoutException error) {
            throw reactor.core.Exceptions.propagate(error);
        }
    }

    private static void dispose(Pool pool) {
        pool.invalidated.set(true);
        pool.provider.dispose();
        pool.resolver.close();
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            pools.values().forEach(HttpConnectionRuntime::dispose);
            pools.clear();
            dns.dispose();
            loops.dispose();
        }
    }
}
