package com.arte.ainew.infrastructure.http;

import com.arte.ainew.api.control.ConnectionManager;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.application.support.InvocationTiming;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwners;
import com.arte.ainew.config.HttpRuntimeOptions;
import com.arte.ainew.context.ExecutionRuntimeContext;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.ainew.pojo.execution.GatewayCall;
import com.arte.ainew.pojo.tool.ToolDefinitionRevision;
import com.arte.ainew.spi.adapter.ConnectionRuntime;
import com.arte.ainew.spi.credential.ConnectionCredentialResolver;
import com.arte.core.pojo.platform.ExecutionOwner;
import com.arte.core.pojo.reference.DefinitionRef;
import io.netty.channel.ChannelOption;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.NoopAddressResolverGroup;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Exceptions;
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
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 有界、独立 HTTP 运行资源
 * <p>
 * 连接基础设施，负责连接授权、出口白名单、DNS 校验、连接池及网络超时。
 * <p>
 * 按连接版本／主体／SecretRef 隔离池，发送时重新授权并解析凭据。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/6 14:01 ✾
 */
@Slf4j
public final class HttpConnectionRuntime implements ConnectionRuntime<HttpConnectionRuntime.Handle>, AutoCloseable {

    private final ConnectionManager connections;
    private final AdmissionAuthorization authorization;
    private final ConnectionCredentialResolver credentials;
    private final HttpRuntimeOptions options;
    private final HttpEgressPolicy egress;
    private final Clock clock;
    private final Scheduler dns = Schedulers.newBoundedElastic(4, 256, "arte-ainew-dns");
    private final LoopResources loops = LoopResources.create("arte-ainew-http", 1, 4, true);
    private final Map<PoolKey, Pool> pools = new LinkedHashMap<>(16, 0.75f, true);
    private boolean closed;

    /**
     * 连接借用模式，用于区分模型、只读 GET 工具和 MCP 工具的路径、请求方法及凭据约束。
     */
    private enum Mode {MODEL, GET, MCP}

    /**
     * 连接池隔离键，用于按完整连接配置、执行主体及已校验的 DNS 地址查找和复用连接池。
     *
     * @param definition 包含版本及凭据引用的连接定义
     * @param owner      本次执行所属的主体及空间
     * @param addresses  出口策略校验通过并固定给连接池的目标地址
     */
    private record PoolKey(
            /** 包含版本及凭据引用的连接定义。 */
            ConnectionDefinition definition,
            /** 本次执行所属的主体及空间。 */
            ExecutionOwner owner,
            /** 出口策略校验通过并固定给连接池的目标地址。 */
            List<InetAddress> addresses) {
    }

    /**
     * 单个隔离连接池的运行状态，保存连接提供器、固定地址解析器及当前借用数量，供借用、失效和关闭操作使用。
     */
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
     * 供协议适配器使用受控客户端访问固定目标 URI，并读取响应字节上限。
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

    /**
     * 一次连接借用的租约，供网关持有和释放，限制重复发送并在连接池失效后拒绝继续使用。
     */
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
                                 ConnectionCredentialResolver credentials, HttpRuntimeOptions options, Clock clock,
                                 HttpEgressPolicy egress) {
        this.connections = connections;
        this.authorization = authorization;
        this.credentials = credentials;
        this.options = Objects.requireNonNull(options).requireComplete();
        this.clock = clock;
        this.egress = Objects.requireNonNull(egress);
    }

    /**
     * 预热独立事件循环及 TLS 运行资源，不解析供应商 DNS、不建立远端连接、不读取凭据。
     */
    public void warmupResources() {
        HttpClient.create(ConnectionProvider.newConnection()).runOn(loops)
                .resolver(NoopAddressResolverGroup.INSTANCE).secure().warmup()
                .block(Duration.ofSeconds(10));
    }

    /**
     * 按模型模式借用受控连接，供模型协议适配器向登记端点的 Chat Completions 路径发送请求。
     */
    @Override
    public Mono<Lease<Handle>> acquire(ConnectionDefinition definition, ExecutionRuntimeContext runtime) {
        return acquire(definition, runtime, Mode.MODEL);
    }

    /**
     * 固定 GET JSON 操作使用完整登记路径；不继承模型路径或放开模型的认证要求。
     */
    public Mono<Lease<Handle>> acquireReadOnly(ConnectionDefinition definition, ExecutionRuntimeContext runtime) {
        if (!definition.protocol().equals(HttpJsonGetTransport.PROTOCOL) || definition.credential() != null) {
            return Mono.error(GenerationException.beforeSend("TOOL_HTTP_PROTOCOL_UNSUPPORTED"));
        }
        return acquire(definition, runtime, Mode.GET);
    }

    /**
     * MCP 仅使用完整固定 endpoint 上的 POST／DELETE；不放宽 GET 或模型连接。
     */
    public Mono<Lease<Handle>> acquireMcp(ConnectionDefinition definition, ExecutionRuntimeContext runtime) {
        if (!definition.protocol().equals(ToolDefinitionRevision.MCP_PROTOCOL)) {
            return Mono.error(GenerationException.beforeSend("TOOL_MCP_PROTOCOL_UNSUPPORTED"));
        }
        return acquire(definition, runtime, Mode.MCP);
    }

    /**
     * 延迟到订阅时校验执行状态及连接当前配置，解析并校验目标地址，再按指定模式创建连接租约。
     * <p>
     * DNS 解析在独立调度器上执行，并受获取超时限制；此阶段不读取凭据或发送 HTTP 请求。
     * 连接配置变化直接拒绝，其他获取异常统一转换为发送前失败，已分类的生成异常和授权异常保留原分类。
     *
     * @param definition 准备阶段选定的版本化连接定义
     * @param runtime    包含执行身份、截止时间及取消状态的运行时上下文
     * @param mode       决定请求路径、允许方法及凭据要求的借用模式
     * @return 订阅后产生受控连接租约的冷 Mono，校验或获取失败通过错误信号返回
     */
    private Mono<Lease<Handle>> acquire(ConnectionDefinition definition, ExecutionRuntimeContext runtime, Mode mode) {
        return Mono.<Lease<Handle>>defer(() -> {
                    checkActive(runtime);
                    return connections.resolve(definition.definition(), runtime.execution()).flatMap(current -> {
                        if (!current.equals(definition) || current.state() != ConnectionDefinition.State.ENABLED) {
                            return Mono.error(GenerationException.beforeSend("CONNECTION_CONFIGURATION_CHANGED"));
                        }
                        return Mono.fromCallable(() -> egress.resolveAddresses(definition.endpoint()))
                                .subscribeOn(dns).timeout(options.acquireTimeout())
                                .map(addresses -> borrow(definition, runtime, addresses, mode));
                    });
                }).doOnError(error -> log.warn("AI HTTP connection acquisition failed, invocationId={}, traceId={}, connectionId={}, code={}, type={}",
                        runtime.execution().executionId(), runtime.execution().traceId(), definition.definition().id(),
                        error instanceof GenerationException known ? known.error(runtime.execution().traceId()).code() : "CONNECTION_ACQUIRE_FAILED",
                        error.getClass().getName()))
                .onErrorMap(error -> error instanceof GenerationException
                        || error instanceof AccessDeniedException ? error
                        : GenerationException.beforeSend("CONNECTION_ACQUIRE_FAILED"));
    }

    /**
     * 为当前主体预留隔离连接池容量，创建仅允许发送一次请求的租约及受控 HTTP 客户端。
     * <p>
     * 模型模式在登记端点后追加 Chat Completions 路径，工具模式使用完整登记路径。
     * 客户端在实际发送前重新校验执行状态、连接配置和调用归属，并按模式解析凭据及校验出口。
     * 此方法只装配客户端，实际网络请求由协议适配器触发；装配失败时释放已预留的借用计数。
     *
     * @param definition 本次借用使用的版本化连接定义及网络限制
     * @param runtime    用于执行状态检查、当前授权核验及凭据解析的运行时上下文
     * @param addresses  已通过出口策略校验、用于固定 DNS 解析结果的目标地址
     * @param mode       决定请求路径、允许方法及凭据要求的借用模式
     * @return 携带受控传输句柄且尚未发送请求的连接租约
     * @throws GenerationException 运行资源已关闭，或连接池及借用容量不足
     */
    private Borrowed borrow(ConnectionDefinition definition, ExecutionRuntimeContext runtime, List<InetAddress> addresses, Mode mode) {
        checkActive(runtime);
        PoolKey key = new PoolKey(definition, ExecutionOwners.from(runtime.execution()), addresses);
        Pool pool = reserve(key);
        Borrowed lease = new Borrowed(key, pool);
        try {
            int connectMillis = Math.clamp(definition.connectTimeout().toMillis(), 1, Integer.MAX_VALUE);
            HttpClient http = HttpClient.create(pool.provider).runOn(loops).resolver(pool.resolver)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectMillis)
                    .option(ChannelOption.SO_KEEPALIVE, true).keepAlive(true)
                    .responseTimeout(definition.responseTimeout()).disableRetry(true).followRedirect(false)
                    .httpResponseDecoder(spec -> spec.maxHeaderSize(16_384))
                    .doOnRequest((request, connection) -> {
                        Attribute<AtomicInteger> uses = connection.channel().attr(AttributeKey.<AtomicInteger>valueOf("arte-ainew-http-uses"));
                        uses.setIfAbsent(new AtomicInteger());
                        log.info("AI_HTTP_CONNECTION invocationId={} traceId={} connectionId={} reused={}",
                                runtime.execution().executionId(), runtime.execution().traceId(), definition.definition().id(), uses.get().getAndIncrement() > 0);
                    })
                    .doAfterRequest((request, connection) -> request.currentContextView().<InvocationTiming>getOrEmpty(InvocationTiming.class)
                            .ifPresent(timing -> timing.mark("PROVIDER_REQUEST_SENT")));
            String base = definition.endpoint().toString();
            URI uri = mode != Mode.MODEL ? definition.endpoint() : URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/chat/completions");
            WebClient client = WebClient.builder().clientConnector(new ReactorClientHttpConnector(http))
                    .codecs(codecs -> {
                        codecs.defaultCodecs().maxInMemorySize(options.maxInMemoryBytes());
                        codecs.defaultCodecs().jacksonJsonEncoder(new JacksonJsonEncoder(GenerationJson.mapper(options.maxInMemoryBytes())));
                    })
                    .filter((request, next) -> Mono.deferContextual(values -> {
                        lease.active();
                        checkActive(runtime);
                        if (!request.url().equals(uri) || mode == Mode.GET && request.method() != HttpMethod.GET
                                || mode == Mode.MCP && request.method() != HttpMethod.POST && request.method() != HttpMethod.DELETE || !lease.sent.compareAndSet(false, true)) {
                            return Mono.error(GenerationException.beforeSend("CONNECTION_REQUEST_NOT_ALLOWED"));
                        }
                        GatewayCall<?> call = values.getOrDefault(GatewayCall.class, null);
                        if (call != null && !call.runtime().execution().equals(runtime.execution())) {
                            return Mono.error(GenerationException.beforeSend("CONNECTION_REQUEST_NOT_ALLOWED"));
                        }
                        Mono<ConnectionDefinition> resolved = call == null ? connections.resolveCurrent(definition.definition(), runtime.execution())
                                : connections.resolveCurrent(definition.definition(), call);
                        // 政策门控在 next.exchange 前失败，明确记录为未发送；不能误归为未知供应商结果。
                        return resolved.onErrorMap(AdmissionException.class,
                                error -> GenerationException.beforeSend(error.getResultCode().name())).flatMap(current -> {
                            if (!current.equals(definition)) {
                                return Mono.error(GenerationException.beforeSend("CONNECTION_CONFIGURATION_CHANGED"));
                            }
                            lease.active();
                            checkActive(runtime);
                            if (mode == Mode.GET || mode == Mode.MCP && definition.credential() == null) {
                                return next.exchange(request);
                            }
                            if (definition.credential() == null) {
                                return Mono.error(GenerationException.beforeSend("CREDENTIAL_NOT_AVAILABLE"));
                            }
                            return credentials.resolve(definition.credential(), runtime.execution())
                                    .switchIfEmpty(Mono.error(GenerationException.beforeSend("CREDENTIAL_NOT_AVAILABLE")))
                                    .flatMap(credential -> {
                                        Mono<Void> egressCheck = mode == Mode.MODEL
                                                ? Mono.<Void>fromRunnable(() -> egress.validateOrigin(request.url()))
                                                .subscribeOn(Schedulers.boundedElastic())
                                                : Mono.empty();
                                        return egressCheck.then(Mono.defer(() -> {
                                            lease.active();
                                            checkActive(runtime);
                                            ClientRequest authenticated = ClientRequest.from(request).headers(headers -> headers.setBearerAuth(credential.token())).build();
                                            return next.exchange(authenticated);
                                        }));
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

    /**
     * 同步查找或创建隔离连接池，并为本次借用增加计数，防止并发获取突破运行资源上限。
     * <p>
     * 达到连接池数量上限时，按最近访问顺序淘汰首个无借用者的池；全部繁忙时返回可重试的容量错误。
     * 每个池的借用总数受连接数量与待获取数量之和约束，此处预留逻辑容量，实际 TCP 连接在发送时获取。
     *
     * @param key 包含连接定义、执行主体及固定目标地址的连接池隔离键
     * @return 已增加一次借用计数的连接池，由租约释放操作归还该计数
     * @throws GenerationException 运行资源已关闭，或无法淘汰空闲池、池内借用容量已满
     */
    private synchronized Pool reserve(PoolKey key) {
        if (closed) {
            throw GenerationException.beforeSend("CONNECTION_RUNTIME_CLOSED");
        }
        Pool pool = pools.get(key);
        if (pool == null) {
            if (pools.size() >= options.maxPools()) {
                Map.Entry<PoolKey, Pool> idle = pools.entrySet().stream().filter(entry -> entry.getValue().borrowed == 0).findFirst()
                        .orElseThrow(() -> GenerationException.transientBeforeSend("CONNECTION_CAPACITY_EXCEEDED"));
                log.debug("AI HTTP idle pool evicted, connectionId={}, poolCount={}", idle.getKey().definition().definition().id(), pools.size());
                dispose(idle.getValue());
                pools.remove(idle.getKey());
            }
            pool = new Pool(ConnectionProvider.builder("arte-ainew-" + UUID.randomUUID())
                    .maxConnections(options.connectionsPerPool()).pendingAcquireMaxCount(options.pendingAcquires())
                    .pendingAcquireTimeout(options.acquireTimeout()).maxIdleTime(Duration.ofMinutes(1))
                    .maxLifeTime(Duration.ofMinutes(5)).build(),
                    HttpEgressPolicy.pinnedResolver(key.definition().endpoint(), key.addresses()));
            pools.put(key, pool);
        }
        if (pool.borrowed >= options.connectionsPerPool() + options.pendingAcquires()) {
            throw GenerationException.transientBeforeSend("CONNECTION_CAPACITY_EXCEEDED");
        }
        pool.borrowed++;
        return pool;
    }

    @Override
    public Mono<Void> release(Lease<Handle> borrowed) {
        return Mono.fromRunnable(() -> releaseNow(borrowed));
    }

    private void releaseNow(Lease<Handle> borrowed) {
        if (!(borrowed instanceof Borrowed lease) || lease.runtimeOwner != this) {
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
            ExecutionOwner owner = ExecutionOwners.from(current);
            synchronized (this) {
                pools.entrySet().removeIf(entry -> {
                    if (!entry.getKey().owner().equals(owner) || !entry.getKey().definition().definition().equals(connection)) {
                        return false;
                    }
                    dispose(entry.getValue());
                    log.info("AI HTTP pool invalidated, invocationId={}, traceId={}, connectionId={}, borrowed={}",
                            current.executionId(), current.traceId(), connection.id(), entry.getValue().borrowed);
                    return true;
                });
            }
        }).then();
    }

    private void checkActive(ExecutionRuntimeContext runtime) {
        try {
            runtime.checkActive(clock);
        } catch (TimeoutException error) {
            throw Exceptions.propagate(error);
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
