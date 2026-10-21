package com.arte.ainew.application.tool.discovery;

import com.arte.ainew.application.tool.schema.ToolSchemaValidator;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.ainew.pojo.tool.ToolDefinitionRevision;
import com.arte.ainew.pojo.tool.ToolProviderDefinition;
import com.arte.ainew.pojo.tool.ToolSchemaDefinition;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.serialization.ToolCatalogDigests;
import com.arte.ainew.spi.adapter.LocalToolDefinitionSource;
import com.arte.ainew.spi.persistence.ToolCatalogStore;
import com.arte.core.application.platform.PlatformTimes;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.io.Serial;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static com.arte.ainew.application.tool.discovery.LocalToolDiscovery.Code.*;

/**
 * 节点本地完整发现：显式来源 → 校验整份候选 → 原子登记不可变事实 → 一次替换节点视图。
 * 失败保留旧视图并置为不健康；该视图仅供后续管理校验，绝不授予发布、授权或执行资格。
 * 不恢复数据库分页为完整来源快照，不启动后台同步，不调用 BusinessToolAdapter.invoke。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 09:30
 */
@Slf4j
public final class LocalToolDiscovery {
    /**
     * 默认配置沿用单条目录记录的绝对字节上限，不是调用参数默认额度。
     */
    private static final long MAX_CONFIGURATION_BYTES = 1_000_000;
    private static final Comparator<DefinitionRef> REFERENCES = Comparator.comparing(DefinitionRef::type)
            .thenComparing(DefinitionRef::id).thenComparing(DefinitionRef::version);
    private final LocalToolRegistry registry;
    private final ToolCatalogStore store;
    private final ToolSchemaValidator schemas;
    private final Scheduler scheduler;
    private final Clock clock;
    private final Duration sourceTimeout;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final AtomicReference<State> current = new AtomicReference<>(new State(null, UNINITIALIZED));

    /**
     * 发现结果码不携带来源异常消息、输入或默认配置，允许管理层安全翻译。
     * APPLIED／REPLAYED 表示底层版本登记结果，不表示节点视图是否发生变化。
     */
    public enum Code {
        UNINITIALIZED, APPLIED, REPLAYED, BUSY, SOURCE_FAILED, SOURCE_EMPTY, SOURCE_TIMEOUT,
        INVALID_DEFINITION, ADAPTER_MISMATCH, CONTRACT_DRIFT, STORE_REJECTED, STORE_FAILED
    }

    /**
     * 最近一次完整成功的节点目录；集合与值对象不可变，能力只能投影为 DISCOVERED。
     *
     * @param providers 固定提供方版本。
     * @param schemas   本次完整视图中的 Schema，而非数据库全部历史版本。
     * @param tools     本次完整视图中的工具；不承载发布状态。
     * @param checksum  内容清单摘要，覆盖提供方、Schema 摘要、工具摘要及目标，忽略来源顺序与发现时间。
     * @param loadedAt  该视图成功安装的 UTC 时间。
     */
    public record Snapshot(Map<DefinitionRef, ToolProviderDefinition> providers,
                           Map<DefinitionRef, ToolSchemaDefinition> schemas,
                           Map<DefinitionRef, ToolDefinitionRevision> tools,
                           String checksum, Instant loadedAt) {
        public Snapshot {
            providers = Map.copyOf(providers);
            schemas = Map.copyOf(schemas);
            tools = Map.copyOf(tools);
            ContractChecks.digest(checksum, "checksum");
            Objects.requireNonNull(loadedAt, "loadedAt");
        }
    }

    /**
     * 原子读取的节点发现状态；不等于 Worker 就绪或当前授权。
     *
     * @param snapshot   最近一次完整成功的视图，首次成功前为 null；失败不会替换它。
     * @param lastResult 最近一次已完成刷新结果；BUSY 不写入全局状态。
     */
    public record State(Snapshot snapshot, Code lastResult) {
        public State {
            Objects.requireNonNull(lastResult, "lastResult");
        }

        /**
         * 只表示最近发现成功，不能据此把节点加入 TOOL 执行池。
         */
        public boolean discoveryHealthy() {
            return snapshot != null && (lastResult == APPLIED || lastResult == REPLAYED);
        }
    }

    /**
     * 一次手动刷新回执。
     *
     * @param code  本次请求结果；BUSY 表示已有刷新在进行，不修改全局状态。
     * @param state 本次完成时的节点状态。
     */
    public record RefreshResult(Code code, State state) {
    }

    private record Loaded(LocalToolRegistry.Registration registration,
                          LocalToolDefinitionSource.Definitions definitions) {
    }

    /**
     * 逐来源限制合并后的容量，超限立即终止，不先保留全部来源再检查。
     */
    private static final class Candidate {

        private final List<Loaded> loaded = new ArrayList<>();
        private final Set<DefinitionRef> schemas = new HashSet<>();
        private int toolCount;

        private void add(Loaded source) {
            source.definitions().schemas().forEach(s -> schemas.add(s.definition()));
            toolCount += source.definitions().tools().size();
            require(schemas.size() <= 128 && toolCount <= 128, INVALID_DEFINITION);
            loaded.add(source);
        }
    }

    private static final class Failure extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 4460838449265161994L;

        private final Code code;

        private Failure(Code code) {
            super(code.name());
            this.code = code;
        }
    }

    public LocalToolDiscovery(LocalToolRegistry registry, ToolCatalogStore store, ToolSchemaValidator schemas,
                              Scheduler scheduler, Clock clock, Duration sourceTimeout) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.store = Objects.requireNonNull(store, "store");
        this.schemas = Objects.requireNonNull(schemas, "schemas");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sourceTimeout = Objects.requireNonNull(sourceTimeout, "sourceTimeout");
        ContractChecks.require(sourceTimeout.compareTo(Duration.ofMillis(1)) >= 0
                && sourceTimeout.compareTo(Duration.ofMinutes(1)) <= 0, "Invalid discovery source timeout");
    }

    /**
     * 当前状态与完整旧视图一起读取，避免跨两个变量观察到混合版本。
     */
    public State state() {
        return current.get();
    }

    /**
     * 显式触发一次刷新，订阅才开始；同一返回值的多订阅共享一次刷新及回执。
     * 首次订阅后即使调用方离开也完成该次有界刷新，避免取消导致写入与节点视图分离。
     * 并发新请求返回 BUSY；所有来源成功前不调用存储，存储确认成功后才替换视图。
     *
     * @return 安全结果码及节点状态，不向外透传来源或数据库异常内容。
     */
    public Mono<RefreshResult> refresh() {
        return refresh(store::put);
    }

    /**
     * 管理入口在元数据加载完成后注入同库授权／审计提交；来源读取期间不持有数据库治理锁。
     */
    public Mono<RefreshResult> refresh(Function<ToolCatalogStore.Batch, Mono<StoreOutcome<ToolCatalogStore.Batch>>> commit) {
        Objects.requireNonNull(commit);
        return Mono.defer(() -> {
            if (!refreshing.compareAndSet(false, true)) {
                return Mono.just(new RefreshResult(BUSY, current.get()));
            }
            return Flux.fromIterable(registry.registrations()).concatMap(this::load)
                    .collect(Candidate::new, Candidate::add)
                    .publishOn(scheduler)
                    .map(candidate -> prepare(candidate.loaded))
                    .flatMap(batch -> Mono.defer(() -> commit.apply(batch))
                            .switchIfEmpty(Mono.error(new Failure(STORE_FAILED)))
                            .onErrorMap(error -> error instanceof Failure ? error : new Failure(STORE_FAILED))
                            .map(outcome -> install(batch, outcome)))
                    .onErrorResume(error -> {
                        var code = error instanceof Failure failure ? failure.code : INVALID_DEFINITION;
                        var state = new State(current.get().snapshot(), code);
                        current.set(state);
                        // 不附带 Throwable，来源异常可能夹带默认配置或领域内容。
                        log.warn("AI_TOOL_DISCOVERY failed code={}", code);
                        return Mono.just(new RefreshResult(code, state));
                    })
                    // 在回执发出前释放，避免紧接着的刷新误报 BUSY；cache 不会向上游传播离开页面的取消。
                    .doOnNext(result -> refreshing.set(false))
                    .doOnError(error -> refreshing.set(false));
        }).cache();
    }

    private Mono<Loaded> load(LocalToolRegistry.Registration registration) {
        // 即使来源同步读取元数据，也不会阻塞订阅它的 HTTP／事件线程。
        return Mono.defer(registration.source()::discover).subscribeOn(scheduler)
                .timeout(sourceTimeout)
                .onErrorMap(error -> new Failure(error instanceof TimeoutException ? SOURCE_TIMEOUT : SOURCE_FAILED))
                .switchIfEmpty(Mono.error(new Failure(SOURCE_EMPTY)))
                .map(definitions -> new Loaded(registration, definitions));
    }

    private ToolCatalogStore.Batch prepare(List<Loaded> loaded) {
        var providers = new TreeMap<DefinitionRef, ToolProviderDefinition>(REFERENCES);
        var documents = new TreeMap<DefinitionRef, ToolSchemaDefinition>(REFERENCES);
        var tools = new TreeMap<DefinitionRef, ToolDefinitionRevision>(REFERENCES);
        for (var source : loaded) {
            var provider = source.registration().provider();
            providers.put(provider.definition(), provider);
            for (var schema : source.definitions().schemas()) {
                var previous = documents.putIfAbsent(schema.definition(), schema);
                if (previous != null && !previous.equals(schema)) throw new Failure(CONTRACT_DRIFT);
            }
            for (var tool : source.definitions().tools()) {
                require(tool.provider().equals(provider.definition()), INVALID_DEFINITION);
                require(tools.putIfAbsent(tool.tool().definition(), tool) == null, INVALID_DEFINITION);
                verifyAdapter(source.registration(), tool);
            }
        }
        // 合并后的节点全量也受同一批次上限限制，不能逐来源提交造成半份新目录。
        var batch = new ToolCatalogStore.Batch(List.copyOf(providers.values()), List.copyOf(documents.values()), List.copyOf(tools.values()));
        var capabilities = new HashMap<DefinitionRef, CapabilityDescriptor>();
        for (var revision : batch.tools()) {
            var capability = revision.tool().discoveredCapability();
            var prior = capabilities.putIfAbsent(capability.definition(), capability);
            require(prior == null || prior.equals(capability), INVALID_DEFINITION);
        }
        var plans = new HashMap<DefinitionRef, EnumMap<ToolSchemaValidator.Purpose, ToolSchemaValidator.CompiledSchema>>();
        for (var schema : batch.schemas()) {
            require(schema.checksum().equals(ToolCatalogDigests.schema(schema.document())), INVALID_DEFINITION);
            var purposes = new EnumMap<ToolSchemaValidator.Purpose, ToolSchemaValidator.CompiledSchema>(ToolSchemaValidator.Purpose.class);
            purposes.put(ToolSchemaValidator.Purpose.OUTPUT, schemas.compile(schema.definition(), schema.document(), ToolSchemaValidator.Purpose.OUTPUT));
            plans.put(schema.definition(), purposes);
        }
        for (var revision : batch.tools()) {
            var tool = revision.tool();
            require(tool.checksum().equals(ToolCatalogDigests.tool(tool)), INVALID_DEFINITION);
            compile(documents, plans, tool.inputSchema(), ToolSchemaValidator.Purpose.INPUT);
            compile(documents, plans, tool.outputSchema(), ToolSchemaValidator.Purpose.OUTPUT);
            var config = compile(documents, plans, tool.configurationSchema(), ToolSchemaValidator.Purpose.CONFIGURATION);
            require(config.validate(tool.defaultConfiguration(), MAX_CONFIGURATION_BYTES).valid(), INVALID_DEFINITION);
        }
        var previous = current.get().snapshot();
        if (previous != null) {
            unchanged(previous.providers(), providers);
            unchanged(previous.schemas(), documents);
            unchanged(previous.tools(), tools);
        }
        return batch;
    }

    private ToolSchemaValidator.CompiledSchema compile(Map<DefinitionRef, ToolSchemaDefinition> documents,
                                                       Map<DefinitionRef, EnumMap<ToolSchemaValidator.Purpose, ToolSchemaValidator.CompiledSchema>> plans,
                                                       DefinitionRef ref, ToolSchemaValidator.Purpose purpose) {
        require(documents.containsKey(ref), INVALID_DEFINITION);
        return plans.get(ref).computeIfAbsent(purpose, p -> schemas.compile(ref, documents.get(ref).document(), p));
    }

    private void verifyAdapter(LocalToolRegistry.Registration registration, ToolDefinitionRevision revision) {
        var adapter = registry.executor(revision).orElse(null);
        require(adapter != null, ADAPTER_MISMATCH);
        CapabilityDescriptor actual;
        try {
            actual = adapter.capability();
        } catch (RuntimeException error) {
            throw new Failure(ADAPTER_MISMATCH);
        }
        require(actual != null && actual.availability() != CapabilityDescriptor.Availability.DISABLED, ADAPTER_MISMATCH);
        var discovered = new CapabilityDescriptor(actual.definition(), actual.kind(), actual.inputSchema(), actual.outputSchema(),
                actual.features(), actual.sideEffect(), CapabilityDescriptor.Availability.DISCOVERED);
        require(discovered.equals(revision.tool().discoveredCapability()), ADAPTER_MISMATCH);
    }

    /**
     * 部署清单摘要的唯一算法；可由受信静态来源离线计算预期值，不授予发现健康或执行资格。
     * 固定排序使登记顺序、发现时刻及节点重启不改变摘要。
     */
    public static String manifest(ToolCatalogStore.Batch batch) {
        return CanonicalJson.digest(CanonicalJson.fields("format", "local-tool-discovery-v1",
                "providers", batch.providers().stream().sorted(Comparator.comparing(ToolProviderDefinition::definition, REFERENCES)).toList(),
                "schemas", batch.schemas().stream().sorted(Comparator.comparing(ToolSchemaDefinition::definition, REFERENCES))
                        .map(s -> CanonicalJson.fields("definition", s.definition(), "checksum", s.checksum())).toList(),
                "tools", batch.tools().stream().sorted(Comparator.comparing(t -> t.tool().definition(), REFERENCES))
                        .map(t -> CanonicalJson.fields("definition", t.tool().definition(), "checksum", t.tool().checksum(),
                                "provider", t.provider(), "target", t.target())).toList()));
    }

    private RefreshResult install(ToolCatalogStore.Batch batch, StoreOutcome<ToolCatalogStore.Batch> outcome) {
        if (outcome.code() != StoreOutcome.Code.APPLIED && outcome.code() != StoreOutcome.Code.REPLAYED)
            throw new Failure(outcome.code() == StoreOutcome.Code.IDEMPOTENCY_CONFLICT ? CONTRACT_DRIFT : STORE_REJECTED);
        require(batch.equals(outcome.value()), STORE_FAILED);
        var providers = new HashMap<DefinitionRef, ToolProviderDefinition>();
        var documents = new HashMap<DefinitionRef, ToolSchemaDefinition>();
        var tools = new HashMap<DefinitionRef, ToolDefinitionRevision>();
        batch.providers().forEach(p -> providers.put(p.definition(), p));
        batch.schemas().forEach(s -> documents.put(s.definition(), s));
        batch.tools().forEach(t -> tools.put(t.tool().definition(), t));
        var digest = manifest(batch);
        var snapshot = new Snapshot(providers, documents, tools, digest, PlatformTimes.businessNow(clock));
        var code = outcome.code() == StoreOutcome.Code.APPLIED ? APPLIED : REPLAYED;
        var state = new State(snapshot, code);
        current.set(state);
        log.info("AI_TOOL_DISCOVERY completed code={} providers={} tools={} checksum={}", code, providers.size(), tools.size(), digest);
        return new RefreshResult(code, state);
    }

    private static <T> void unchanged(Map<DefinitionRef, T> previous, Map<DefinitionRef, T> candidate) {
        candidate.forEach((ref, value) -> {
            if (previous.containsKey(ref) && !previous.get(ref).equals(value)) throw new Failure(CONTRACT_DRIFT);
        });
    }

    private static void require(boolean condition, Code code) {
        if (!condition) throw new Failure(code);
    }
}
