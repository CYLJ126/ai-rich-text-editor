package com.arte.ainew.persistence.mybatis;

import com.arte.ainew.application.tool.binding.ToolBindingConstraints;
import com.arte.ainew.application.tool.schema.ToolSchemaValidator;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.persistence.mybatis.mapper.SystemMapper;
import com.arte.ainew.persistence.mybatis.mapper.ToolBindingMapper;
import com.arte.ainew.persistence.mybatis.mapper.ToolCatalogMapper;
import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.ainew.pojo.tool.*;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.serialization.ToolBindingDigests;
import com.arte.ainew.serialization.ToolCatalogDigests;
import com.arte.ainew.serialization.ToolReleaseDigests;
import com.arte.ainew.spi.persistence.ExecutionRecordCodec;
import com.arte.ainew.spi.persistence.ToolBindingStore;
import com.arte.ainew.spi.persistence.ToolCatalogStore;
import com.arte.core.application.platform.PlatformTimes;
import com.arte.core.enums.platform.PrincipalStatusEnum;
import com.arte.core.pojo.platform.TrustedActor;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.arte.ainew.pojo.tool.ToolBindingResult.Rejection.*;
import static com.arte.ainew.serialization.CanonicalJson.key;

/**
 * 私有 TOOL 绑定的同库事务；管理指针、不可变修订和原始回执同时提交。
 * 先锁绑定／命令，再锁稳定工具身份，与发布治理共用工具锁；不调用执行器或读取 Secret 值。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 10:41 ✾
 */
public final class MybatisToolBindingStore implements ToolBindingStore {
    /**
     * 限制兼容链的 SQL 次数，超出即拒绝，不截断后静默选择旧版本。
     */
    private static final int MAX_CHAIN_LENGTH = 128;
    /**
     * 与目录、发布材料一致的单条编码字节上限。
     */
    private static final int MAX_RECORD_BYTES = 1_000_000;
    private final DataSource dataSource;
    private final ToolBindingMapper mapper;
    private final ToolCatalogMapper catalogMapper;
    private final SystemMapper locks;
    private final MybatisToolCatalogStore catalog;
    private final MybatisToolReleaseStore releases;
    private final ExecutionRecordCodec codec;
    private final Scheduler scheduler;
    private final Clock clock;
    private final ToolExecutionPolicy serverLimits;
    private final TransactionTemplate transaction;
    private final TransactionTemplate reads;
    private final ToolSchemaValidator schemas = new ToolSchemaValidator();

    public MybatisToolBindingStore(DataSource source, ExecutionRecordCodec codec, Scheduler scheduler, Clock clock,
                                   ToolExecutionPolicy serverLimits, MybatisToolCatalogStore catalog, MybatisToolReleaseStore releases) {
        this.dataSource = Objects.requireNonNull(source);
        ContractChecks.require(catalog.uses(source) && releases.uses(source), "Binding dependencies must use the same physical DataSource");
        var sessions = new SqlSessionTemplate(ExecutionSqlSessionFactory.create(source));
        mapper = sessions.getMapper(ToolBindingMapper.class);
        catalogMapper = sessions.getMapper(ToolCatalogMapper.class);
        locks = sessions.getMapper(SystemMapper.class);
        this.catalog = catalog;
        this.releases = releases;
        this.codec = Objects.requireNonNull(codec);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.clock = Objects.requireNonNull(clock);
        this.serverLimits = Objects.requireNonNull(serverLimits);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setTimeout(30);
        reads = new TransactionTemplate(new DataSourceTransactionManager(source));
        reads.setReadOnly(true);
        reads.setTimeout(30);
    }

    boolean uses(DataSource source) {
        return dataSource == source;
    }

    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(dataSource))
            throw new IllegalStateException("Tool binding requires its physical transaction");
    }

    private <T> Mono<T> io(Supplier<T> operation) {
        return Mono.fromCallable(() -> {
            if (Schedulers.isInNonBlockingThread())
                throw new IllegalStateException("Tool binding requires a bounded worker");
            return operation.get();
        }).subscribeOn(scheduler);
    }

    @Override
    public Mono<ToolBindingDefinition> find(ExecutionOwner owner, String bindingId) {
        require(owner, bindingId);
        return io(() -> transaction.execute(status -> {
            var value = header(bindingId);
            return value != null && value.owner().equals(owner) ? value : null;
        }));
    }

    @Override
    public Mono<ToolBindingRevision> revision(ExecutionOwner owner, DefinitionRef ref) {
        requireRevision(ref);
        Objects.requireNonNull(owner);
        return io(() -> transaction.execute(status -> {
            var header = header(ref.id());
            if (header == null || !header.owner().equals(owner)) return null;
            return readRevision(ref, header);
        }));
    }

    @Override
    public Mono<ToolCatalogStore.Page<ToolBindingDefinition>> list(ExecutionOwner owner, String afterKey, int limit) {
        Objects.requireNonNull(owner);
        page(afterKey, limit);
        return io(() -> {
            var rows = mapper.toolBindings(ownerKey(owner), afterKey, limit + 1);
            boolean more = rows.size() > limit;
            var selected = more ? rows.subList(0, limit) : rows;
            var values = selected.stream().map(this::decode).toList();
            if (values.stream().anyMatch(v -> !v.owner().equals(owner)))
                throw new IllegalStateException("Binding list ownership mismatch");
            return new ToolCatalogStore.Page<>(values, more ? selected.getLast().bindingKey() : null);
        });
    }

    @Override
    public Mono<StoreOutcome<ToolBindingReceipt>> execute(TrustedActor actor, ExecutionOwner owner, ToolBindingCommand command) {
        return executeGuarded(actor, owner, command, () -> {
        });
    }

    @Override
    public Mono<StoreOutcome<ToolBindingReceipt>> executeGuarded(TrustedActor actor, ExecutionOwner owner, ToolBindingCommand command,
                                                                 Runnable authorization) {
        Objects.requireNonNull(authorization);
        Objects.requireNonNull(actor);
        Objects.requireNonNull(owner);
        Objects.requireNonNull(command);
        ContractChecks.require(actor.principal().status() == PrincipalStatusEnum.ACTIVE
                && actor.principal().subjectId().equals(owner.subjectId()), "Only the active personal owner can manage bindings");
        String commandKey = commandKey(owner, actor.principal().kind().name(), command.idempotencyKey());
        // 默认身份只含真实 owner 与稳定工具 ID；跨标签页、命令键及工具版本仍指向同一份配置。
        boolean ensureDefault = command.action() == ToolBindingCommand.Action.ENSURE_DEFAULT;
        String bindingId = ensureDefault ? "default-tool-" + key(ownerKey(owner), command.settings().tool().id())
                : command.bindingId() == null ? "tool-" + commandKey : command.bindingId();
        String requestDigest = requestDigest(actor.principal().kind().name(), owner, command);
        // 先编码原命令，过大请求不能在写入指针后才成为可预期拒绝。
        ContractChecks.require(CanonicalJson.encode(command).getBytes(StandardCharsets.UTF_8).length <= MAX_RECORD_BYTES,
                "Binding command exceeds byte limit");
        return io(() -> transaction.execute(status -> {
            authorization.run();
            for (var lock : new TreeSet<>(Set.of(commandKey, bindingKey(bindingId)))) lock(lock);
            var previous = mapper.toolBindingCommand(commandKey);
            if (previous != null) {
                var receipt = decode(previous);
                return previous.requestDigest().equals(requestDigest) && receipt.owner().equals(owner)
                        && receipt.actorKind() == actor.principal().kind() ? StoreOutcome.replayed(receipt)
                        : StoreOutcome.rejected(StoreOutcome.Code.IDEMPOTENCY_CONFLICT);
            }
            var before = header(bindingId);
            ToolBindingResult<ToolBindingDefinition> result;
            var now = PlatformTimes.businessNow(clock);
            try {
                if (before != null && !before.owner().equals(owner)) throw reject(OWNER_MISMATCH);
                if (before == null && command.bindingId() != null) throw reject(NOT_FOUND);
                if (ensureDefault && before != null) {
                    // 只返回已有指针；不覆盖改名／配置／升级，也不重新启用被用户停用的绑定。
                    result = ToolBindingResult.success(before, before.version());
                } else {
                    if (before != null && before.version() != command.expectedVersion()) throw reject(VERSION_CONFLICT);
                    String toolId = before == null ? command.settings().tool().id() : before.toolId();
                    lock(key("tool-release-identity", toolId));
                    var prior = before == null ? null : Objects.requireNonNull(readRevision(before.revision(), before), "Missing current binding revision");
                    var settings = command.action() == ToolBindingCommand.Action.SET_ENABLED ? prior.settings().withEnabled(command.enabled()) : command.settings();
                    if (!settings.tool().id().equals(toolId)) throw reject(TOOL_ID_MISMATCH);
                    if (prior != null && command.action() == ToolBindingCommand.Action.SAVE && !settings.tool().equals(prior.settings().tool()))
                        throw reject(USE_UPGRADE);
                    if (prior != null && settings.equals(prior.settings())) throw reject(NO_CHANGE);
                    long nextVersion = before == null ? 1 : before.version() + 1;
                    var ref = new DefinitionRef("binding", bindingId, "r" + nextVersion);
                    if (command.action() == ToolBindingCommand.Action.UPGRADE) {
                        var preview = previewLocked(before, prior, command.expectedVersion(), settings, now);
                        if (!preview.proposed().successful()) throw reject(preview.proposed().rejection());
                        if (!preview.digest().equals(command.expectedPreviewDigest())) throw reject(PREVIEW_CHANGED);
                        if (!preview.compatible() && !command.acceptIncompatible()) throw reject(INCOMPATIBLE);
                    } else if (command.action() != ToolBindingCommand.Action.SET_ENABLED || command.enabled()) {
                        // 新建必须选择当前可新绑定版本；已有绑定只允许在废弃宽限期继续使用。
                        plan(owner, ref, settings, before == null, now);
                    }
                    var updated = new ToolBindingDefinition(bindingId, owner, toolId, nextVersion, settings.name(), settings.enabled(),
                            before == null ? now : before.createdAt(), before != null && now.isBefore(before.updatedAt()) ? before.updatedAt() : now);
                    var revision = new ToolBindingRevision(ref, owner, settings, updated.updatedAt());
                    var row = row(updated);
                    int affected = before == null ? mapper.insertToolBinding(row) : mapper.updateToolBinding(row, before.version());
                    if (affected != 1) throw new IllegalStateException("Binding update was not applied");
                    if (mapper.insertToolBindingRevision(artifact(revision)) != 1)
                        throw new IllegalStateException("Binding revision was not recorded");
                    result = ToolBindingResult.success(updated, nextVersion);
                }
            } catch (Rejected rejected) {
                result = ToolBindingResult.rejected(rejected.reason, before == null || !before.owner().equals(owner) ? null : before.version());
            }
            var receipt = new ToolBindingReceipt(commandKey, actor.principal().subjectId(), actor.principal().kind(), owner, bindingId, command, result, now);
            String json = encode(receipt);
            if (mapper.insertToolBindingCommand(new ToolBindingMapper.Command(commandKey, ownerKey(owner), bindingKey(bindingId), requestDigest,
                    CanonicalJson.sha256(json), json)) != 1)
                throw new IllegalStateException("Binding audit was not recorded");
            return StoreOutcome.applied(receipt);
        }));
    }

    @Override
    public Mono<ToolBindingUpgradePreview> preview(ExecutionOwner owner, String bindingId, long expectedVersion, ToolBindingSettings settings) {
        require(owner, bindingId);
        ContractChecks.range(expectedVersion, "expectedVersion", 1, Long.MAX_VALUE - 1);
        Objects.requireNonNull(settings);
        return io(() -> transaction.execute(status -> {
            lock(bindingKey(bindingId));
            var before = header(bindingId);
            if (before == null || !before.owner().equals(owner)) return null;
            lock(key("tool-release-identity", before.toolId()));
            var prior = Objects.requireNonNull(readRevision(before.revision(), before), "Missing current binding revision");
            return previewLocked(before, prior, expectedVersion, settings, PlatformTimes.businessNow(clock));
        }));
    }

    private ToolBindingUpgradePreview previewLocked(ToolBindingDefinition before, ToolBindingRevision prior,
                                                    long expectedVersion, ToolBindingSettings settings, Instant now) {
        var old = prior.settings();
        var changes = changes(old, settings);
        DefinitionRef actual = old.tool();
        try {
            actual = select(old, false, now).tool();
        } catch (Rejected ignored) { /* 不可用旧目标仍可显式升级修复。 */ }
        boolean compatible = false;
        ToolBindingResult<ToolBindingResolution> proposed;
        String digest = null;
        try {
            if (before.version() != expectedVersion) throw reject(VERSION_CONFLICT);
            if (!settings.tool().id().equals(before.toolId())) throw reject(TOOL_ID_MISMATCH);
            // 只读预览允许查看未修改配置的有效交集；真正保存仍在 executeGuarded 拒绝 NO_CHANGE。
            var plan = plan(before.owner(), new DefinitionRef("binding", before.bindingId(), "r" + (expectedVersion + 1)), settings,
                    !settings.tool().equals(old.tool()), now);
            compatible = compatible(actual, plan.tool().tool().definition());
            var oldArtifacts = catalog.releaseArtifacts(actual);
            if (oldArtifacts != null) {
                var original = oldArtifacts.tools().getFirst().tool();
                if (!Objects.equals(original.price(), plan.tool().tool().price()))
                    changes.add(ToolBindingUpgradePreview.Change.PRICE);
                if (!original.risk().equals(plan.tool().tool().risk()))
                    changes.add(ToolBindingUpgradePreview.Change.RISK);
                if (!original.capabilities().equals(plan.tool().tool().capabilities()))
                    changes.add(ToolBindingUpgradePreview.Change.CAPABILITIES);
                if (!original.defaultPolicy().equals(plan.tool().tool().defaultPolicy()) && !changes.contains(ToolBindingUpgradePreview.Change.EXECUTION_POLICY))
                    changes.add(ToolBindingUpgradePreview.Change.EXECUTION_POLICY);
                if (!ToolBindingConstraints.merge(original.defaultConfiguration(), old.configuration()).equals(plan.effectiveConfiguration())
                        && !changes.contains(ToolBindingUpgradePreview.Change.CONFIGURATION))
                    changes.add(ToolBindingUpgradePreview.Change.CONFIGURATION);
                var oldOrigins = old.networkOrigins() == null ? original.risk().allowedNetworkOrigins() : old.networkOrigins();
                if (!oldOrigins.equals(plan.networkOrigins()) && !changes.contains(ToolBindingUpgradePreview.Change.NETWORK_ORIGINS))
                    changes.add(ToolBindingUpgradePreview.Change.NETWORK_ORIGINS);
            }
            proposed = ToolBindingResult.success(plan, before.version());
            var baselineRelease = releases.currentForBinding(settings.tool());
            var targetRelease = releases.currentForBinding(plan.tool().tool().definition());
            var originalRelease = releases.currentForBinding(actual);
            digest = CanonicalJson.digest(CanonicalJson.fields("format", "tool-binding-upgrade-v1", "owner", before.owner(), "binding", before.bindingId(),
                    "expectedVersion", expectedVersion, "settings", ToolBindingDigests.settings(settings), "plan", plan.definition(),
                    "baselineRelease", baselineRelease, "targetRelease", targetRelease, "originalRelease", originalRelease,
                    "compatible", compatible, "changes", changes));
        } catch (Rejected rejected) {
            proposed = ToolBindingResult.rejected(rejected.reason, before.version());
        }
        return new ToolBindingUpgradePreview(before.bindingId(), expectedVersion, old.tool(), actual, compatible, changes, proposed, digest);
    }

    @Override
    public Mono<ToolBindingResult<ToolBindingResolution>> resolve(ExecutionOwner owner, String bindingId) {
        require(owner, bindingId);
        return io(() -> transaction.execute(status -> resolveLocked(owner, bindingId)));
    }

    /**
     * 同库受理事务的同步治理检查；调用方须使用本适配器的物理 DataSource，不运行执行器。
     */
    ToolBindingResult<ToolBindingResolution> resolveInCurrentTransaction(ExecutionOwner owner, String bindingId) {
        requireTransaction();
        return resolveLocked(owner, bindingId);
    }

    /**
     * 同库受理复用本次解析已核验的不可变目录材料；不缓存跨事务的治理或权限事实。
     */
    ToolBindingResult<ToolBindingResolution> resolveInCurrentTransaction(ExecutionOwner owner, String bindingId,
                                                                         Consumer<ToolCatalogStore.Batch> artifacts) {
        requireTransaction();
        return resolvePlan(owner, bindingId, true, artifacts);
    }

    @Override
    public Mono<ToolBindingResult<ToolBindingResolution>> inspect(ExecutionOwner owner, String bindingId) {
        return inspect(owner, bindingId, ignored -> {
        });
    }

    @Override
    public Mono<ToolBindingResult<ToolBindingResolution>> inspect(ExecutionOwner owner, String bindingId, Consumer<ToolCatalogStore.Batch> artifacts) {
        require(owner, bindingId);
        Objects.requireNonNull(artifacts);
        return io(() -> reads.execute(status -> resolvePlan(owner, bindingId, false, artifacts)));
    }

    private ToolBindingResult<ToolBindingResolution> resolveLocked(ExecutionOwner owner, String bindingId) {
        return resolvePlan(owner, bindingId, true);
    }

    private ToolBindingResult<ToolBindingResolution> resolvePlan(ExecutionOwner owner, String bindingId, boolean persist) {
        return resolvePlan(owner, bindingId, persist, ignored -> {
        });
    }

    private ToolBindingResult<ToolBindingResolution> resolvePlan(ExecutionOwner owner, String bindingId, boolean persist,
                                                                 Consumer<ToolCatalogStore.Batch> artifacts) {
        require(owner, bindingId);
        if (persist) lock(bindingKey(bindingId));
        var before = header(bindingId, persist);
        if (before == null || !before.owner().equals(owner)) return ToolBindingResult.rejected(NOT_FOUND, null);
        if (persist) lock(key("tool-release-identity", before.toolId()));
        var revision = Objects.requireNonNull(readRevision(before.revision(), before, persist), "Missing current binding revision");
        try {
            if (!before.enabled()) throw reject(BINDING_DISABLED);
            var plan = plan(owner, revision.definition(), revision.settings(), false, PlatformTimes.businessNow(clock), persist, artifacts);
            if (!persist) return ToolBindingResult.success(plan, before.version());
            var row = mapper.toolBindingResolution(id(plan.definition()));
            if (row == null && persist) {
                if (mapper.insertToolBindingResolution(artifact(plan)) != 1)
                    throw new IllegalStateException("Binding resolution was not recorded");
            } else if (row != null && !decodeResolution(row).equals(plan))
                throw new IllegalStateException("Binding plan identity collision");
            return ToolBindingResult.success(plan, before.version());
        } catch (Rejected rejected) {
            return ToolBindingResult.rejected(rejected.reason, before.version());
        }
    }

    @Override
    public Mono<ToolBindingResult<ToolBindingResolution>> fixed(ExecutionOwner owner, DefinitionRef ref) {
        require(owner, Objects.requireNonNull(ref).id());
        ref.requireType("binding");
        ContractChecks.require(ref.version().matches("p1-[0-9a-f]{64}"), "Expected a fixed binding plan reference");
        return io(() -> transaction.execute(status -> fixedLocked(owner, ref)));
    }

    /**
     * 同库受理事务的同步治理检查；调用方须使用本适配器的物理 DataSource，不运行执行器。
     */
    ToolBindingResult<ToolBindingResolution> fixedInCurrentTransaction(ExecutionOwner owner, DefinitionRef ref) {
        requireTransaction();
        return fixedLocked(owner, ref);
    }

    private ToolBindingResult<ToolBindingResolution> fixedLocked(ExecutionOwner owner, DefinitionRef ref) {
        require(owner, Objects.requireNonNull(ref).id());
        ref.requireType("binding");
        ContractChecks.require(ref.version().matches("p1-[0-9a-f]{64}"), "Expected a fixed binding plan reference");
        lock(bindingKey(ref.id()));
        var before = header(ref.id());
        if (before == null || !before.owner().equals(owner)) return ToolBindingResult.rejected(NOT_FOUND, null);
        lock(key("tool-release-identity", before.toolId()));
        var row = mapper.toolBindingResolution(id(ref));
        if (row == null) return ToolBindingResult.rejected(NOT_FOUND, before.version());
        var plan = decodeResolution(row);
        if (!plan.definition().equals(ref) || !plan.owner().equals(owner) || !plan.tool().tool().definition().id().equals(before.toolId()))
            throw new IllegalStateException("Fixed plan ownership mismatch");
        try {
            if (!before.enabled()) throw reject(BINDING_DISABLED);
            if (!plan.secrets().isEmpty()) throw reject(SECRET_UNSUPPORTED);
            var release = releases.currentForBinding(plan.tool().tool().definition());
            if (release == null || !release.permitsExistingBinding(PlatformTimes.businessNow(clock)))
                throw reject(TOOL_UNAVAILABLE);
            if (!release.publication().contractDigest().equals(plan.contractDigest()))
                throw new IllegalStateException("Fixed publication mismatch");
            if (ToolCatalogDigests.configurationBytes(plan.effectiveConfiguration()) > serverLimits.maxInputBytes())
                throw reject(CONFIGURATION_INVALID);
            if (!ToolBindingConstraints.intersect(plan.effectivePolicy(), serverLimits).equals(plan.effectivePolicy()))
                throw reject(POLICY_CONFLICT);
            return ToolBindingResult.success(plan, before.version());
        } catch (IllegalArgumentException conflict) {
            return ToolBindingResult.rejected(POLICY_CONFLICT, before.version());
        } catch (Rejected rejected) {
            return ToolBindingResult.rejected(rejected.reason, before.version());
        }
    }

    private ToolBindingResolution plan(ExecutionOwner owner, DefinitionRef revision, ToolBindingSettings settings, boolean creating, Instant now) {
        return plan(owner, revision, settings, creating, now, true);
    }

    private ToolBindingResolution plan(ExecutionOwner owner, DefinitionRef revision, ToolBindingSettings settings, boolean creating, Instant now, boolean lock) {
        return plan(owner, revision, settings, creating, now, lock, ignored -> {
        });
    }

    private ToolBindingResolution plan(ExecutionOwner owner, DefinitionRef revision, ToolBindingSettings settings, boolean creating,
                                       Instant now, boolean lock, Consumer<ToolCatalogStore.Batch> onArtifacts) {
        if (!settings.secrets().isEmpty()) throw reject(SECRET_UNSUPPORTED);
        var release = select(settings, creating, now, lock);
        var artifacts = catalog.releaseArtifacts(release.tool());
        if (artifacts == null) throw new IllegalStateException("Missing published tool artifacts");
        var tool = artifacts.tools().getFirst();
        String contract = ToolReleaseDigests.contract(tool, artifacts.providers().getFirst(), artifacts.schemas());
        if (!contract.equals(release.publication().contractDigest()))
            throw new IllegalStateException("Published contract integrity failure");
        onArtifacts.accept(artifacts);
        var origins = tool.tool().risk().allowedNetworkOrigins();
        if (settings.networkOrigins() != null) {
            if (!origins.containsAll(settings.networkOrigins())) throw reject(NETWORK_NOT_ALLOWED);
            origins = settings.networkOrigins();
        }
        ToolExecutionPolicy policy;
        try {
            policy = ToolBindingConstraints.intersect(ToolBindingConstraints.intersect(tool.tool().defaultPolicy(), settings.policy()), serverLimits);
        } catch (IllegalArgumentException conflict) {
            throw reject(POLICY_CONFLICT);
        }
        com.arte.ainew.common.value.StructuredValue.ObjectValue configuration;
        try {
            configuration = ToolBindingConstraints.merge(tool.tool().defaultConfiguration(), settings.configuration());
            var schema = artifacts.schemas().stream().filter(s -> s.definition().equals(tool.tool().configurationSchema())).findFirst().orElseThrow();
            if (!schemas.compile(schema.definition(), schema.document(), ToolSchemaValidator.Purpose.CONFIGURATION)
                    .validate(configuration, Math.min(MAX_RECORD_BYTES, serverLimits.maxInputBytes())).valid())
                throw reject(CONFIGURATION_INVALID);
        } catch (IllegalArgumentException invalid) {
            throw reject(CONFIGURATION_INVALID);
        }
        String configDigest = ToolCatalogDigests.configuration(configuration);
        String digest = resolutionDigest(revision, owner, tool, contract, configDigest, policy, origins, settings.secrets());
        var plan = new ToolBindingResolution(new DefinitionRef("binding", revision.id(), "p1-" + digest), revision, owner, tool,
                contract, configuration, configDigest, policy, origins, settings.secrets());
        // 默认配置与实际配置都包含在固定材料内；保存前先验收整份材料，避免留下无法解析的绑定。
        if (CanonicalJson.normalize(codec.encode(plan)).getBytes(StandardCharsets.UTF_8).length > MAX_RECORD_BYTES)
            throw reject(CONFIGURATION_INVALID);
        return plan;
    }

    private ToolRelease select(ToolBindingSettings settings, boolean creating, Instant now) {
        return select(settings, creating, now, true);
    }

    private ToolRelease select(ToolBindingSettings settings, boolean creating, Instant now, boolean lock) {
        var release = releases.forBinding(settings.tool(), lock);
        var seen = new HashSet<DefinitionRef>();
        for (int count = 0; count < MAX_CHAIN_LENGTH; count++) {
            if (release == null || !release.permitsExistingBinding(now)) throw reject(TOOL_UNAVAILABLE);
            if (!seen.add(release.tool()) || !release.tool().id().equals(settings.tool().id()))
                throw reject(CHAIN_INVALID);
            var next = settings.versionPolicy() == ToolBindingSettings.VersionPolicy.PINNED ? null : releases.successorForBinding(release.tool(), lock);
            if (next == null) {
                if (creating && !release.permitsNewBinding()) throw reject(TOOL_UNAVAILABLE);
                return release;
            }
            if (!release.tool().equals(next.publication().review().compatibleFrom())) throw reject(CHAIN_INVALID);
            release = next;
        }
        throw reject(CHAIN_LIMIT_EXCEEDED);
    }

    /**
     * 显式升级的兼容性由审核边决定；停用状态只影响目标可用性，不改写历史审核事实。
     */
    private boolean compatible(DefinitionRef from, DefinitionRef to) {
        var seen = new HashSet<DefinitionRef>();
        for (int count = 0; count < MAX_CHAIN_LENGTH; count++) {
            if (from.equals(to)) return true;
            if (!seen.add(from)) throw reject(CHAIN_INVALID);
            var next = releases.successorForBinding(from);
            if (next == null) return false;
            if (!next.tool().id().equals(to.id()) || !from.equals(next.publication().review().compatibleFrom()))
                throw reject(CHAIN_INVALID);
            from = next.tool();
        }
        throw reject(CHAIN_LIMIT_EXCEEDED);
    }

    @Override
    public Mono<ToolCatalogStore.Page<ToolBindingReceipt>> history(ExecutionOwner owner, String bindingId, String afterKey, int limit) {
        require(owner, bindingId);
        page(afterKey, limit);
        return io(() -> {
            var rows = mapper.toolBindingHistory(ownerKey(owner), bindingKey(bindingId), afterKey, limit + 1);
            boolean more = rows.size() > limit;
            var selected = more ? rows.subList(0, limit) : rows;
            var values = selected.stream().map(this::decode).toList();
            if (values.stream().anyMatch(r -> !r.owner().equals(owner) || !r.bindingId().equals(bindingId)))
                throw new IllegalStateException("Binding history identity mismatch");
            return new ToolCatalogStore.Page<>(values, more ? selected.getLast().commandKey() : null);
        });
    }

    private void lock(String key) {
        catalogMapper.ensureCatalogLock(key);
        if (!key.equals(locks.lock(key))) throw new IllegalStateException("Binding lock was not acquired");
    }

    private ToolBindingDefinition header(String bindingId) {
        return header(bindingId, true);
    }

    private ToolBindingDefinition header(String bindingId, boolean lock) {
        var row = lock ? mapper.toolBinding(bindingKey(bindingId)) : mapper.toolBindingReadOnly(bindingKey(bindingId));
        var value = row == null ? null : decode(row);
        if (value != null && !value.bindingId().equals(bindingId))
            throw new IllegalStateException("Binding identity mismatch");
        return value;
    }

    private ToolBindingRevision readRevision(DefinitionRef ref, ToolBindingDefinition header) {
        return readRevision(ref, header, true);
    }

    private ToolBindingRevision readRevision(DefinitionRef ref, ToolBindingDefinition header, boolean lock) {
        var row = lock ? mapper.toolBindingRevision(id(ref)) : mapper.toolBindingRevisionReadOnly(id(ref));
        if (row == null) return null;
        var value = decode(row.snapshot(), row.payloadDigest(), ToolBindingRevision.class);
        if (!row.artifactKey().equals(id(value.definition())) || !row.revisionKey().equals(id(value.definition()))
                || !row.bindingKey().equals(bindingKey(value.definition().id())) || !row.toolKey().equals(id(value.settings().tool()))
                || !value.definition().equals(ref) || !value.owner().equals(header.owner())
                || !value.settings().tool().id().equals(header.toolId()) || ref.equals(header.revision())
                && (!value.settings().name().equals(header.name()) || value.settings().enabled() != header.enabled()
                || !value.createdAt().equals(header.updatedAt())))
            throw new IllegalStateException("Binding revision index integrity failure");
        return value;
    }

    private ToolBindingDefinition decode(ToolBindingMapper.Header row) {
        var value = decode(row.snapshot(), row.payloadDigest(), ToolBindingDefinition.class);
        if (!row(value).equals(row)) throw new IllegalStateException("Binding header index integrity failure");
        return value;
    }

    private ToolBindingReceipt decode(ToolBindingMapper.Command row) {
        var value = decode(row.snapshot(), row.payloadDigest(), ToolBindingReceipt.class);
        if (!value.commandId().equals(row.commandKey()) || !commandKey(value.owner(), value.actorKind().name(), value.command().idempotencyKey()).equals(row.commandKey())
                || !ownerKey(value.owner()).equals(row.ownerKey()) || !bindingKey(value.bindingId()).equals(row.bindingKey())
                || !requestDigest(value.actorKind().name(), value.owner(), value.command()).equals(row.requestDigest()))
            throw new IllegalStateException("Binding receipt index integrity failure");
        return value;
    }

    private ToolBindingResolution decodeResolution(ToolBindingMapper.Artifact row) {
        var value = decode(row.snapshot(), row.payloadDigest(), ToolBindingResolution.class);
        String digest = resolutionDigest(value.revision(), value.owner(), value.tool(), value.contractDigest(), value.configurationDigest(), value.effectivePolicy(), value.networkOrigins(), value.secrets());
        if (!row.artifactKey().equals(id(value.definition())) || !row.bindingKey().equals(bindingKey(value.definition().id()))
                || !row.revisionKey().equals(id(value.revision())) || !row.toolKey().equals(id(value.tool().tool().definition()))
                || !value.tool().tool().checksum().equals(ToolCatalogDigests.tool(value.tool().tool()))
                || !value.definition().version().equals("p1-" + digest)
                || !ToolCatalogDigests.configuration(value.effectiveConfiguration()).equals(value.configurationDigest()))
            throw new IllegalStateException("Binding resolution index integrity failure");
        return value;
    }

    private ToolBindingMapper.Header row(ToolBindingDefinition value) {
        String json = encode(value);
        return new ToolBindingMapper.Header(bindingKey(value.bindingId()), ownerKey(value.owner()), value.toolId(), value.version(), CanonicalJson.sha256(json), json);
    }

    private ToolBindingMapper.Artifact artifact(ToolBindingRevision value) {
        String json = encode(value);
        return new ToolBindingMapper.Artifact(id(value.definition()), bindingKey(value.definition().id()), id(value.definition()), id(value.settings().tool()), CanonicalJson.sha256(json), json);
    }

    private ToolBindingMapper.Artifact artifact(ToolBindingResolution value) {
        String json = encode(value);
        return new ToolBindingMapper.Artifact(id(value.definition()), bindingKey(value.definition().id()), id(value.revision()), id(value.tool().tool().definition()), CanonicalJson.sha256(json), json);
    }

    private String encode(Object value) {
        String json = CanonicalJson.normalize(codec.encode(value));
        ContractChecks.require(json.getBytes(StandardCharsets.UTF_8).length <= MAX_RECORD_BYTES, "Binding record exceeds byte limit");
        return json;
    }

    private <T> T decode(String json, String digest, Class<T> type) {
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_RECORD_BYTES || !CanonicalJson.sha256(json).equals(digest))
            throw new IllegalStateException("Binding snapshot integrity failure");
        return Objects.requireNonNull(codec.decode(json, type));
    }

    private static String resolutionDigest(DefinitionRef revision, ExecutionOwner owner, ToolDefinitionRevision tool, String contract,
                                           String config, ToolExecutionPolicy policy, Set<java.net.URI> origins, Map<String, DefinitionRef> secrets) {
        return CanonicalJson.digest(CanonicalJson.fields("format", "tool-binding-plan-v1", "revision", revision, "owner", owner,
                "tool", tool.tool().definition(), "toolChecksum", tool.tool().checksum(), "provider", tool.provider(),
                "target", tool.target(), "contract", contract, "configurationDigest", config, "policy", policy,
                "origins", origins.stream().map(Object::toString).sorted().toList(), "secrets", secrets));
    }

    private static String requestDigest(String kind, ExecutionOwner owner, ToolBindingCommand command) {
        return CanonicalJson.digest(CanonicalJson.fields("owner", owner, "kind", kind, "action", command.action(), "binding", command.bindingId(),
                "version", command.expectedVersion(), "key", command.idempotencyKey(), "reason", command.reason(), "enabled", command.enabled(),
                "settings", command.settings() == null ? null : ToolBindingDigests.settings(command.settings()),
                "preview", command.expectedPreviewDigest(), "acceptIncompatible", command.acceptIncompatible()));
    }

    private static List<ToolBindingUpgradePreview.Change> changes(ToolBindingSettings a, ToolBindingSettings b) {
        var changes = new ArrayList<ToolBindingUpgradePreview.Change>();
        if (!a.tool().equals(b.tool())) changes.add(ToolBindingUpgradePreview.Change.TOOL_VERSION);
        if (a.versionPolicy() != b.versionPolicy()) changes.add(ToolBindingUpgradePreview.Change.VERSION_POLICY);
        if (!a.configuration().equals(b.configuration())) changes.add(ToolBindingUpgradePreview.Change.CONFIGURATION);
        if (!a.secrets().equals(b.secrets())) changes.add(ToolBindingUpgradePreview.Change.SECRET_REFERENCES);
        if (!Objects.equals(a.policy(), b.policy())) changes.add(ToolBindingUpgradePreview.Change.EXECUTION_POLICY);
        if (!Objects.equals(a.networkOrigins(), b.networkOrigins()))
            changes.add(ToolBindingUpgradePreview.Change.NETWORK_ORIGINS);
        if (a.enabled() != b.enabled()) changes.add(ToolBindingUpgradePreview.Change.ENABLED);
        if (!a.name().equals(b.name())) changes.add(ToolBindingUpgradePreview.Change.NAME);
        return changes;
    }

    private static String bindingKey(String id) {
        return key("tool-binding", id);
    }

    private static String ownerKey(ExecutionOwner owner) {
        return key("tool-binding-owner", owner.tenantId(), owner.workspaceId(), owner.subjectId());
    }

    private static String commandKey(ExecutionOwner owner, String kind, String command) {
        return key("tool-binding-command", ownerKey(owner), kind, owner.subjectId(), command);
    }

    private static String id(DefinitionRef ref) {
        return key(ref.type(), ref.id(), ref.version());
    }

    private static void require(ExecutionOwner owner, String id) {
        Objects.requireNonNull(owner);
        ContractChecks.id(id, "bindingId");
    }

    private static void requireRevision(DefinitionRef ref) {
        Objects.requireNonNull(ref).requireType("binding");
        ContractChecks.require(ref.version().matches("r[1-9][0-9]{0,18}"), "Expected binding revision");
    }

    private static void page(String after, int limit) {
        if (after != null) ContractChecks.digest(after, "afterKey");
        ContractChecks.range(limit, "limit", 1, 100);
    }

    private static Rejected reject(ToolBindingResult.Rejection reason) {
        return new Rejected(reason);
    }

    private static final class Rejected extends RuntimeException {
        final ToolBindingResult.Rejection reason;

        Rejected(ToolBindingResult.Rejection reason) {
            super(reason.name(), null, false, false);
            this.reason = reason;
        }
    }
}
