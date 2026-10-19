package com.arte.ainew.persistence.mybatis;

import com.arte.ainew.application.tool.schema.ToolSchemaValidator;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.persistence.mybatis.mapper.SystemMapper;
import com.arte.ainew.persistence.mybatis.mapper.ToolCatalogMapper;
import com.arte.ainew.persistence.mybatis.mapper.ToolReleaseMapper;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ExecutionTarget;
import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.ainew.pojo.tool.*;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.serialization.ToolReleaseDigests;
import com.arte.ainew.spi.persistence.ExecutionRecordCodec;
import com.arte.ainew.spi.persistence.ToolCatalogStore;
import com.arte.ainew.spi.persistence.ToolReleaseStore;
import com.arte.core.application.platform.PlatformTimes;
import com.arte.core.enums.platform.PrincipalKindEnum;
import com.arte.core.enums.platform.PrincipalStatusEnum;
import com.arte.core.pojo.platform.TrustedActor;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

import static com.arte.ainew.pojo.execution.StoreOutcome.Code.*;
import static com.arte.ainew.pojo.tool.ToolRelease.State.*;
import static com.arte.ainew.pojo.tool.ToolReleaseReceipt.Rejection.*;
import static com.arte.ainew.serialization.CanonicalJson.key;

/**
 * 生命周期状态、管理防重及审计回执的同库事务；复用物理数据源和固定版本目录。
 * 稳定工具身份锁串行化同一工具的发布／兼容边，条件更新保护治理行版本；不执行 DDL 或工具。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 09:49
 */
public final class MybatisToolReleaseStore implements ToolReleaseStore {
    /**
     * 发布材料与审计快照沿用目录单条 1,000,000 字节上限。
     */
    private static final int MAX_RECORD_BYTES = 1_000_000;

    private final DataSource dataSource;
    private final ToolReleaseMapper mapper;
    private final ToolCatalogMapper catalogMapper;
    private final SystemMapper locks;
    private final MybatisToolCatalogStore catalog;
    private final ExecutionRecordCodec codec;
    private final TransactionTemplate transaction;
    private final Scheduler scheduler;
    private final Clock clock;
    private final ToolSchemaValidator schemas = new ToolSchemaValidator();

    public MybatisToolReleaseStore(DataSource dataSource, ExecutionRecordCodec codec, Scheduler scheduler, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource);
        var sessions = new SqlSessionTemplate(ExecutionSqlSessionFactory.create(dataSource));
        mapper = sessions.getMapper(ToolReleaseMapper.class);
        catalogMapper = sessions.getMapper(ToolCatalogMapper.class);
        locks = sessions.getMapper(SystemMapper.class);
        this.codec = Objects.requireNonNull(codec);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.clock = Objects.requireNonNull(clock);
        catalog = new MybatisToolCatalogStore(dataSource, codec, scheduler, clock);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setTimeout(30);
    }

    boolean uses(DataSource source) {
        return dataSource == source;
    }

    /**
     * 绑定事务已持有稳定工具身份锁；使用当前读，不能跨调度器脱离事务。
     */
    ToolRelease currentForBinding(DefinitionRef ref) {
        return forBinding(ref, true);
    }

    /**
     * 预览使用事务快照；真正变更／受理仍在稳定身份锁下当前读。
     */
    ToolRelease forBinding(DefinitionRef ref, boolean lock) {
        var row = mapper.toolRelease(id(ref), lock);
        return row == null ? null : decode(row);
    }

    ToolRelease successorForBinding(DefinitionRef ref) {
        return successorForBinding(ref, true);
    }

    ToolRelease successorForBinding(DefinitionRef ref, boolean lock) {
        var row = mapper.toolReleaseSuccessor(id(ref), lock);
        return row == null ? null : decode(row);
    }

    private <T> Mono<T> io(Supplier<T> operation) {
        return Mono.fromCallable(() -> {
            if (Schedulers.isInNonBlockingThread())
                throw new IllegalStateException("Tool release requires a bounded worker");
            return operation.get();
        }).subscribeOn(scheduler);
    }

    @Override
    public Mono<ToolRelease> find(DefinitionRef tool) {
        Objects.requireNonNull(tool).requireType("tool");
        return io(() -> {
            var row = mapper.toolRelease(id(tool), false);
            if (row != null) return decode(row);
            return catalogMapper.tool(id(tool), false) == null ? null : ToolRelease.draft(tool);
        });
    }

    @Override
    public Mono<ToolCatalogStore.Page<ToolReleaseReceipt>> history(DefinitionRef tool, String afterKey, int limit) {
        Objects.requireNonNull(tool).requireType("tool");
        if (afterKey != null) ContractChecks.digest(afterKey, "afterKey");
        ContractChecks.range(limit, "limit", 1, 100);
        return io(() -> {
            var rows = mapper.toolReleaseHistory(id(tool), afterKey, limit + 1);
            boolean more = rows.size() > limit;
            var page = more ? rows.subList(0, limit) : rows;
            var receipts = page.stream().map(this::decode).toList();
            if (receipts.stream().anyMatch(r -> !r.command().tool().equals(tool)))
                throw new IllegalStateException("Release history identity mismatch");
            return new ToolCatalogStore.Page<>(receipts, more ? page.getLast().commandKey() : null);
        });
    }

    @Override
    public Mono<java.util.Map<DefinitionRef, ToolRelease>> findKnown(java.util.List<DefinitionRef> tools) {
        var refs = ContractChecks.list(tools, "known tools", 0, 100);
        refs.forEach(ref -> ref.requireType("tool"));
        return io(() -> {
            var result = new HashMap<DefinitionRef, ToolRelease>();
            refs.forEach(ref -> result.put(ref, ToolRelease.draft(ref)));
            if (!refs.isEmpty())
                for (var row : mapper.toolReleases(refs.stream().map(MybatisToolReleaseStore::id).distinct().toList())) {
                    var release = decode(row);
                    if (!result.containsKey(release.tool()))
                        throw new IllegalStateException("Tool release page identity mismatch");
                    result.put(release.tool(), release);
                }
            return java.util.Map.copyOf(result);
        });
    }

    @Override
    public Mono<StoreOutcome<ToolReleaseReceipt>> execute(TrustedActor actor, ToolReleaseCommand command, ToolPublicationValidation validation) {
        return executeGuarded(actor, command, validation, () -> {
        });
    }

    @Override
    public Mono<StoreOutcome<ToolReleaseReceipt>> executeGuarded(TrustedActor actor, ToolReleaseCommand command,
                                                                 ToolPublicationValidation validation, Runnable authorization) {
        Objects.requireNonNull(authorization);
        Objects.requireNonNull(actor);
        Objects.requireNonNull(command);
        ContractChecks.require(actor.principal().status() == PrincipalStatusEnum.ACTIVE, "Inactive release actor");
        boolean checked = command.action() == ToolReleaseCommand.Action.PUBLISH || command.action() == ToolReleaseCommand.Action.REENABLE;
        ContractChecks.require(checked || validation == null, "Revocation must not depend on publication validation");
        String subject = actor.principal().subjectId();
        var kind = actor.principal().kind();
        String commandKey = commandKey(subject, kind, command.idempotencyKey());
        String requestDigest = requestDigest(subject, kind, command);
        return io(() -> transaction.execute(status -> {
            // 公共主体／空间治理锁先于工具锁；授权失败不读取历史回执或写入假拒绝。
            authorization.run();
            // 两类锁一起排序；不同实例、相反输入顺序也不反向取得锁。
            for (String lock : new TreeSet<>(Set.of(commandKey, key("tool-release-identity", command.tool().id())))) {
                catalogMapper.ensureCatalogLock(lock);
                if (!lock.equals(locks.lock(lock))) throw new IllegalStateException("Release lock was not acquired");
            }
            var replay = mapper.toolReleaseCommand(commandKey);
            if (replay != null) {
                var receipt = decode(replay);
                return replay.requestDigest().equals(requestDigest) && receipt.actorSubjectId().equals(subject) && receipt.actorKind() == kind
                        ? StoreOutcome.replayed(receipt) : StoreOutcome.rejected(IDEMPOTENCY_CONFLICT);
            }
            var now = PlatformTimes.businessNow(clock);
            var row = mapper.toolRelease(id(command.tool()), true);
            var before = row == null ? (catalogMapper.tool(id(command.tool()), false) == null ? null : ToolRelease.draft(command.tool())) : decode(row);
            var decision = decide(actor, command, validation, before, now);
            var receipt = new ToolReleaseReceipt(commandKey, subject, kind, command, decision.code(), decision.rejection(), decision.release(), validation, now);
            String encodedReceipt = encode(receipt);
            if (decision.code() == APPLIED) {
                var updated = row(decision.release());
                int affected = row == null ? mapper.insertToolRelease(updated) : mapper.updateToolRelease(updated, before.version());
                if (affected != 1) throw new IllegalStateException("Release update was not applied");
            }
            var audit = new ToolReleaseMapper.CommandRow(commandKey, actorKey(subject, kind), id(command.tool()), requestDigest,
                    decision.code().name(), command.action().name(), CanonicalJson.sha256(encodedReceipt), encodedReceipt, micros(now));
            if (mapper.insertToolReleaseCommand(audit) != 1)
                throw new IllegalStateException("Release audit was not recorded");
            return StoreOutcome.applied(receipt);
        }));
    }

    private record Decision(StoreOutcome.Code code, ToolReleaseReceipt.Rejection rejection, ToolRelease release) {
    }

    private Decision decide(TrustedActor actor, ToolReleaseCommand command, ToolPublicationValidation validation,
                            ToolRelease before, Instant now) {
        if (before == null) return new Decision(NOT_FOUND, TOOL_NOT_FOUND, null);
        if (before.version() != command.expectedVersion()) return new Decision(StoreOutcome.Code.VERSION_CONFLICT,
                ToolReleaseReceipt.Rejection.VERSION_CONFLICT, before);
        var state = before.state();
        var publication = before.publication();
        ToolRelease.State target;
        ToolRelease.State resume = null;
        Instant deadline = before.existingBindingsUntil();
        switch (command.action()) {
            case PUBLISH, REENABLE -> {
                if (command.action() == ToolReleaseCommand.Action.PUBLISH ? state != DRAFT : state != DISABLED)
                    return new Decision(INVALID_STATE, INVALID_TRANSITION, before);
                if (validation == null || !validation.valid() || validation.checkedAt().isAfter(now)
                        || validation.checkedAt().isBefore(now.minus(Duration.ofMinutes(1))))
                    return new Decision(INVALID_STATE, VALIDATION_FAILED, before);
                var artifacts = Objects.requireNonNull(catalog.releaseArtifacts(command.tool()), "Missing release artifacts");
                String digest = ToolReleaseDigests.contract(artifacts.tools().getFirst(), artifacts.providers().getFirst(), artifacts.schemas());
                if (!digest.equals(validation.contractDigest()) || publication != null && !digest.equals(publication.contractDigest()))
                    return new Decision(INVALID_STATE, CONTRACT_CHANGED, before);
                var review = publication == null ? command.review() : publication.review();
                if (!validProduct(artifacts, review)) return new Decision(INVALID_STATE, VALIDATION_FAILED, before);
                if (publication == null) {
                    var incompatibility = compatible(artifacts, review);
                    if (incompatibility != NONE) return new Decision(INVALID_STATE, incompatibility, before);
                    publication = new ToolRelease.Publication(digest, validation.discoveryChecksum(), review, actor.principal().subjectId(), now);
                    target = PUBLISHED;
                } else {
                    target = before.resumeState();
                    if (deadline != null && !now.isBefore(deadline))
                        return new Decision(INVALID_STATE, EXPIRED, before);
                }
            }
            case DEPRECATE -> {
                if (state != PUBLISHED) return new Decision(INVALID_STATE, INVALID_TRANSITION, before);
                deadline = command.existingBindingsUntil();
                if (deadline != null && !now.isBefore(deadline)) return new Decision(INVALID_STATE, EXPIRED, before);
                target = DEPRECATED;
            }
            case DISABLE -> {
                if (state != PUBLISHED && state != DEPRECATED)
                    return new Decision(INVALID_STATE, INVALID_TRANSITION, before);
                target = DISABLED;
                resume = state;
            }
            default -> throw new IllegalStateException("Unregistered release action");
        }
        return new Decision(APPLIED, NONE, new ToolRelease(before.tool(), target, before.version() + 1,
                publication, resume, deadline, now));
    }

    /**
     * 事务内重验固定产品，防止绕过服务预检或用另一份契约的摘要替代当前事实。
     */
    private boolean validProduct(ToolCatalogStore.Batch artifacts, ToolReleaseCommand.Review review) {
        var tool = artifacts.tools().getFirst().tool();
        if (artifacts.providers().getFirst().kind() != ToolProviderDefinition.Kind.LOCAL
                || !(artifacts.tools().getFirst().target() instanceof ExecutionTarget.Local)
                || !tool.capabilities().features().isEmpty() || tool.capabilities().outcomeQuerySupported()
                || tool.risk().sideEffect() == CapabilityDescriptor.SideEffect.UNKNOWN
                || tool.risk().idempotency() == ToolRiskProfile.Idempotency.UNVERIFIED
                || tool.risk().idempotency() != ToolRiskProfile.Idempotency.NONE && review.idempotencyEvidence() == null)
            return false;
        var minimum = tool.risk().sideEffect() == CapabilityDescriptor.SideEffect.EXTERNAL_EFFECT
                ? ToolExecutionPolicy.ApprovalMode.INDEPENDENT_APPROVAL : tool.risk().minimumApprovalMode();
        if (!review.minimumApproval().satisfies(minimum) || !tool.defaultPolicy().approvalMode().satisfies(review.minimumApproval()))
            return false;
        var documents = new HashMap<DefinitionRef, ToolSchemaDefinition>();
        artifacts.schemas().forEach(s -> documents.put(s.definition(), s));
        try {
            var input = schemas.compile(tool.inputSchema(), documents.get(tool.inputSchema()).document(), ToolSchemaValidator.Purpose.INPUT);
            var output = schemas.compile(tool.outputSchema(), documents.get(tool.outputSchema()).document(), ToolSchemaValidator.Purpose.OUTPUT);
            var config = schemas.compile(tool.configurationSchema(), documents.get(tool.configurationSchema()).document(), ToolSchemaValidator.Purpose.CONFIGURATION);
            if (!config.validate(tool.defaultConfiguration(), MAX_RECORD_BYTES).valid()) return false;
            return review.examples().stream().allMatch(e -> input.validate(e.input(), Math.min(ToolReleaseCommand.MAX_EXAMPLE_BYTES, tool.defaultPolicy().maxInputBytes())).valid()
                    && output.validate(e.output(), Math.min(ToolReleaseCommand.MAX_EXAMPLE_BYTES, tool.defaultPolicy().maxOutputBytes())).valid());
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private ToolReleaseReceipt.Rejection compatible(ToolCatalogStore.Batch target, ToolReleaseCommand.Review review) {
        var from = review.compatibleFrom();
        if (from == null) return NONE;
        var predecessor = mapper.toolRelease(id(from), true);
        if (predecessor == null) return INCOMPATIBLE;
        var baseline = decode(predecessor);
        if (baseline.state() != PUBLISHED && baseline.state() != DEPRECATED) return INCOMPATIBLE;
        if (mapper.toolReleaseSuccessor(id(from), true) != null) return AMBIGUOUS_COMPATIBILITY;
        var original = Objects.requireNonNull(catalog.releaseArtifacts(from), "Missing compatibility predecessor");
        var old = original.tools().getFirst().tool();
        var next = target.tools().getFirst().tool();
        if (!old.definition().id().equals(next.definition().id()) || !old.risk().equals(next.risk())
                || !old.capabilities().equals(next.capabilities()) || !old.defaultPolicy().equals(next.defaultPolicy())
                || !old.defaultConfiguration().equals(next.defaultConfiguration())) return INCOMPATIBLE;
        var oldDocuments = new HashMap<DefinitionRef, String>();
        var newDocuments = new HashMap<DefinitionRef, String>();
        original.schemas().forEach(s -> oldDocuments.put(s.definition(), s.checksum()));
        target.schemas().forEach(s -> newDocuments.put(s.definition(), s.checksum()));
        if (!Objects.equals(oldDocuments.get(old.inputSchema()), newDocuments.get(next.inputSchema()))
                || !Objects.equals(oldDocuments.get(old.outputSchema()), newDocuments.get(next.outputSchema()))
                || !Objects.equals(oldDocuments.get(old.configurationSchema()), newDocuments.get(next.configurationSchema())))
            return INCOMPATIBLE;
        // 只允许“已有发布前驱 → 首次发布目标”；不改写已发布边，因此按构造顺序无环，唯一索引防分叉。
        return NONE;
    }

    private ToolReleaseMapper.Row row(ToolRelease release) {
        String json = encode(release);
        var from = release.publication().review().compatibleFrom();
        return new ToolReleaseMapper.Row(id(release.tool()), from == null ? null : id(from), release.version(), release.state().name(),
                CanonicalJson.sha256(json), json, micros(release.changedAt()));
    }

    private ToolRelease decode(ToolReleaseMapper.Row row) {
        var value = decode(row.snapshot(), row.payloadDigest(), ToolRelease.class);
        var from = value.publication() == null ? null : value.publication().review().compatibleFrom();
        if (value.state() == DRAFT || !row.toolKey().equals(id(value.tool())) || row.version() != value.version()
                || !row.state().equals(value.state().name()) || row.updatedMicros() != micros(value.changedAt())
                || !Objects.equals(row.compatibleFromKey(), from == null ? null : id(from)))
            throw new IllegalStateException("Release index integrity failure");
        return value;
    }

    private ToolReleaseReceipt decode(ToolReleaseMapper.CommandRow row) {
        var value = decode(row.snapshot(), row.payloadDigest(), ToolReleaseReceipt.class);
        if (!row.commandKey().equals(value.commandId()) || !row.commandKey().equals(commandKey(value.actorSubjectId(), value.actorKind(), value.command().idempotencyKey()))
                || !row.actorKey().equals(actorKey(value.actorSubjectId(), value.actorKind())) || !row.toolKey().equals(id(value.command().tool()))
                || !row.requestDigest().equals(requestDigest(value.actorSubjectId(), value.actorKind(), value.command()))
                || !row.action().equals(value.command().action().name()) || !row.outcome().equals(value.outcome().name())
                || row.recordedMicros() != micros(value.recordedAt()))
            throw new IllegalStateException("Release receipt index integrity failure");
        return value;
    }

    private String encode(Object value) {
        String json = CanonicalJson.normalize(codec.encode(value));
        ContractChecks.require(json.getBytes(StandardCharsets.UTF_8).length <= MAX_RECORD_BYTES, "Release record exceeds byte limit");
        return json;
    }

    private <T> T decode(String json, String digest, Class<T> type) {
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_RECORD_BYTES || !CanonicalJson.sha256(json).equals(digest))
            throw new IllegalStateException("Release snapshot integrity failure");
        return Objects.requireNonNull(codec.decode(json, type), "Missing release snapshot");
    }

    private static String requestDigest(String actor, PrincipalKindEnum kind, ToolReleaseCommand command) {
        return CanonicalJson.digest(CanonicalJson.fields("actor", actor, "kind", kind, "command", command));
    }

    private static String commandKey(String actor, PrincipalKindEnum kind, String idempotencyKey) {
        return key("tool-release-command", kind.name(), actor, idempotencyKey);
    }

    private static String actorKey(String actor, PrincipalKindEnum kind) {
        return key("tool-release-actor", kind.name(), actor);
    }

    private static String id(DefinitionRef ref) {
        return key(ref.type(), ref.id(), ref.version());
    }

    private static long micros(Instant value) {
        return Math.addExact(Math.multiplyExact(value.getEpochSecond(), 1_000_000L), value.getNano() / 1000);
    }
}
