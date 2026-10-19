package com.arte.ainew.application.tool.binding;

import com.arte.ainew.application.tool.discovery.LocalToolDiscovery;
import com.arte.ainew.application.tool.discovery.LocalToolRegistry;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ExecutionTarget;
import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.ainew.pojo.tool.*;
import com.arte.ainew.spi.persistence.ToolBindingStore;
import com.arte.core.pojo.platform.TrustedActor;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.Duration;
import java.util.Objects;

/**
 * 内部本地绑定管理及固定解析
 * <p>
 * 成功解析前复核当前节点的受信实现声明，不调用 invoke。
 * 这里保留内部解析职责；公开管理入口由 ToolManagementService 复核当前资格，S2 派发仍须再次检查。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 10:41
 */
public final class ToolBindingService {

    private final ToolBindingStore store;
    private final LocalToolDiscovery discovery;
    private final LocalToolRegistry registry;
    private final Scheduler metadataScheduler;
    private final Duration timeout;

    public ToolBindingService(ToolBindingStore store, LocalToolDiscovery discovery, LocalToolRegistry registry,
                              Scheduler metadataScheduler, Duration timeout) {
        this.store = Objects.requireNonNull(store);
        this.discovery = Objects.requireNonNull(discovery);
        this.registry = Objects.requireNonNull(registry);
        this.metadataScheduler = Objects.requireNonNull(metadataScheduler);
        this.timeout = Objects.requireNonNull(timeout);
        ContractChecks.require(timeout.compareTo(Duration.ofMillis(1)) >= 0 && timeout.compareTo(Duration.ofMinutes(1)) <= 0,
                "Invalid binding metadata timeout");
    }

    /**
     * 管理不依赖执行器存活，停用尤其不应因节点发现失败受阻；实际可用解析另行校验。
     */
    public Mono<StoreOutcome<ToolBindingReceipt>> execute(TrustedActor actor, ExecutionOwner owner, ToolBindingCommand command) {
        return store.execute(actor, owner, command);
    }

    public Mono<ToolBindingUpgradePreview> preview(ExecutionOwner owner, String id, long version, ToolBindingSettings settings) {
        return store.preview(owner, id, version, settings).flatMap(preview -> check(preview.proposed()).map(result ->
                new ToolBindingUpgradePreview(preview.bindingId(), preview.expectedVersion(), preview.currentBaseline(), preview.currentTool(),
                        preview.compatible(), preview.changes(), result, result.successful() ? preview.digest() : null)));
    }

    /**
     * 只检查当前有效方案与本节点实现，不保存固定解析。
     */
    public Mono<ToolBindingResult<ToolBindingResolution>> inspect(ExecutionOwner owner, String bindingId) {
        return store.inspect(owner, bindingId).flatMap(this::check);
    }

    public Mono<ToolBindingResult<ToolBindingResolution>> resolve(ExecutionOwner owner, String bindingId) {
        return store.resolve(owner, bindingId).flatMap(this::check);
    }

    public Mono<ToolBindingResult<ToolBindingResolution>> fixed(ExecutionOwner owner, DefinitionRef ref) {
        return store.fixed(owner, ref).flatMap(this::check);
    }

    private Mono<ToolBindingResult<ToolBindingResolution>> check(ToolBindingResult<ToolBindingResolution> result) {
        if (!result.successful()) return Mono.just(result);
        return Mono.fromCallable(() -> available(result.value()) ? result : unavailable(result))
                .subscribeOn(metadataScheduler).timeout(timeout).onErrorResume(error -> Mono.just(unavailable(result)));
    }

    private boolean available(ToolBindingResolution plan) {
        var state = discovery.state();
        var revision = plan.tool();
        if (!state.discoveryHealthy() || !revision.equals(state.snapshot().tools().get(revision.tool().definition()))
                || !(revision.target() instanceof ExecutionTarget.Local(DefinitionRef adapter1))) return false;
        var adapter = registry.findAdapter(adapter1).orElse(null);
        if (adapter == null) return false;
        var actual = adapter.capability();
        return actual != null && actual.availability() != CapabilityDescriptor.Availability.DISABLED
                && new CapabilityDescriptor(actual.definition(), actual.kind(), actual.inputSchema(), actual.outputSchema(), actual.features(),
                actual.sideEffect(), CapabilityDescriptor.Availability.DISCOVERED).equals(revision.tool().discoveredCapability());
    }

    private static ToolBindingResult<ToolBindingResolution> unavailable(ToolBindingResult<ToolBindingResolution> result) {
        return ToolBindingResult.rejected(ToolBindingResult.Rejection.EXECUTOR_UNAVAILABLE, result.currentVersion());
    }
}
