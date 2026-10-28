package com.arte.ainew.persistence.mybatis;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.persistence.mybatis.mapper.SystemMapper;
import com.arte.ainew.persistence.mybatis.mapper.ToolCatalogMapper;
import com.arte.ainew.persistence.mybatis.mapper.ToolDiscoveryMapper;
import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.ainew.pojo.tool.ToolDiscoveryReceipt;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.spi.persistence.ExecutionRecordCodec;
import com.arte.ainew.spi.persistence.ToolCatalogStore;
import com.arte.ainew.spi.persistence.ToolDiscoveryManagementStore;
import com.arte.core.application.platform.PlatformTimes;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.Comparator;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 完整目录登记与刷新命令／审计的同库事务；来源加载由发现服务在事务外完成。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 12:52 ✾
 */
public final class MybatisToolDiscoveryManagementStore implements ToolDiscoveryManagementStore {

    private final MybatisToolCatalogStore catalog;
    private final ToolDiscoveryMapper mapper;
    private final ToolCatalogMapper catalogMapper;
    private final SystemMapper locks;
    private final TransactionTemplate transaction;
    private final ExecutionRecordCodec codec;
    private final Scheduler scheduler;
    private final Clock clock;

    public MybatisToolDiscoveryManagementStore(DataSource source, ExecutionRecordCodec codec, Scheduler scheduler, Clock clock, MybatisToolCatalogStore catalog) {
        ContractChecks.require(catalog.uses(source), "Discovery and catalog must share physical DataSource");
        var session = new SqlSessionTemplate(ExecutionSqlSessionFactory.create(source));
        mapper = session.getMapper(ToolDiscoveryMapper.class);
        catalogMapper = session.getMapper(ToolCatalogMapper.class);
        locks = session.getMapper(SystemMapper.class);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setTimeout(30);
        this.catalog = catalog;
        this.codec = codec;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    private <T> Mono<T> io(Supplier<T> work) {
        return Mono.fromCallable(() -> {
            if (Schedulers.isInNonBlockingThread())
                throw new IllegalStateException("Discovery management requires bounded worker");
            return work.get();
        }).subscribeOn(scheduler);
    }

    @Override
    public Mono<StoreOutcome<ToolDiscoveryReceipt>> find(TrustedActor actor, Scope scope, String key, String reason, Runnable authorization) {
        validate(actor, scope, key, reason, authorization);
        return io(() -> transaction.execute(status -> {
            authorization.run();
            lock(actor, key);
            return saved(actor, scope, key, reason);
        }));
    }

    @Override
    public Mono<Commit> commit(TrustedActor actor, Scope scope, String key, String reason, ToolCatalogStore.Batch batch, Runnable authorization) {
        validate(actor, scope, key, reason, authorization);
        Objects.requireNonNull(batch);
        return io(() -> transaction.execute(status -> {
            authorization.run();
            lock(actor, key);
            var saved = saved(actor, scope, key, reason);
            String digest = digest(batch);
            if (saved != null) {
                var value = saved.value();
                var candidate = value != null && value.successful() && digest.equals(value.checksum()) ? StoreOutcome.replayed(batch)
                        : StoreOutcome.<ToolCatalogStore.Batch>rejected(StoreOutcome.Code.INVALID_STATE);
                return new Commit(candidate, saved);
            }
            var result = catalog.putInCurrentTransaction(batch);
            boolean success = result.value() != null;
            var code = success ? ToolDiscoveryReceipt.Code.valueOf(result.code().name()) : ToolDiscoveryReceipt.Code.STORE_REJECTED;
            var receipt = receipt(actor, scope, key, reason, code, success ? digest : null,
                    success ? batch.providers().size() : 0, success ? batch.schemas().size() : 0, success ? batch.tools().size() : 0);
            insert(actor, scope, key, reason, receipt);
            return new Commit(result, StoreOutcome.applied(receipt));
        }));
    }

    @Override
    public Mono<StoreOutcome<ToolDiscoveryReceipt>> failure(TrustedActor actor, Scope scope, String key, String reason, ToolDiscoveryReceipt.Code code, Runnable authorization) {
        validate(actor, scope, key, reason, authorization);
        ContractChecks.require(code != ToolDiscoveryReceipt.Code.APPLIED && code != ToolDiscoveryReceipt.Code.REPLAYED, "Failure cannot attest discovery");
        return io(() -> transaction.execute(status -> {
            authorization.run();
            lock(actor, key);
            var saved = saved(actor, scope, key, reason);
            if (saved != null) return saved;
            var receipt = receipt(actor, scope, key, reason, code, null, 0, 0, 0);
            insert(actor, scope, key, reason, receipt);
            return StoreOutcome.applied(receipt);
        }));
    }

    private ToolDiscoveryReceipt receipt(TrustedActor actor, Scope scope, String key, String reason, ToolDiscoveryReceipt.Code code, String digest, int providers, int schemas, int tools) {
        return new ToolDiscoveryReceipt(command(actor, key), actor.principal().subjectId(), actor.principal().kind(), scope, key, reason, code, digest,
                providers, schemas, tools, PlatformTimes.businessNow(clock));
    }

    private StoreOutcome<ToolDiscoveryReceipt> saved(TrustedActor actor, Scope scope, String key, String reason) {
        var row = mapper.toolDiscoveryCommand(command(actor, key));
        if (row == null) return null;
        if (row.snapshot().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 32_768 || !CanonicalJson.sha256(row.snapshot()).equals(row.payloadDigest()))
            throw new IllegalStateException("Discovery receipt integrity failure");
        var receipt = codec.decode(row.snapshot(), ToolDiscoveryReceipt.class);
        if (!row.commandKey().equals(receipt.commandId()) || !row.commandKey().equals(command(receipt.actorSubjectId(), receipt.actorKind().name(), receipt.idempotencyKey()))
                || !row.requestDigest().equals(request(receipt.scope(), receipt.reason())))
            throw new IllegalStateException("Discovery receipt identity mismatch");
        return receipt.actorSubjectId().equals(actor.principal().subjectId()) && receipt.actorKind() == actor.principal().kind()
                && receipt.scope().equals(scope) && receipt.idempotencyKey().equals(key) && row.requestDigest().equals(request(scope, reason))
                ? StoreOutcome.replayed(receipt) : StoreOutcome.rejected(StoreOutcome.Code.IDEMPOTENCY_CONFLICT);
    }

    private void insert(TrustedActor actor, Scope scope, String key, String reason, ToolDiscoveryReceipt receipt) {
        String json = CanonicalJson.normalize(codec.encode(receipt));
        if (mapper.insertToolDiscoveryCommand(new ToolDiscoveryMapper.Row(command(actor, key), request(scope, reason), CanonicalJson.sha256(json), json)) != 1)
            throw new IllegalStateException("Discovery audit was not recorded");
    }

    private void lock(TrustedActor actor, String key) {
        String command = command(actor, key);
        catalogMapper.ensureCatalogLock(command);
        if (!command.equals(locks.lock(command))) throw new IllegalStateException("Discovery command lock missing");
    }

    private static void validate(TrustedActor actor, Scope scope, String key, String reason, Runnable authorization) {
        Objects.requireNonNull(actor);
        Objects.requireNonNull(scope);
        Objects.requireNonNull(authorization);
        ContractChecks.require(actor.principal().status() == com.arte.core.enums.platform.PrincipalStatusEnum.ACTIVE, "Inactive discovery actor");
        ContractChecks.id(key, "idempotencyKey");
        ContractChecks.text(reason, "reason", 1024);
    }

    private static String command(TrustedActor actor, String key) {
        return command(actor.principal().subjectId(), actor.principal().kind().name(), key);
    }

    private static String command(String actor, String kind, String key) {
        return CanonicalJson.key("tool-discovery-command", actor, kind, key);
    }

    private static String request(Scope scope, String reason) {
        return CanonicalJson.digest(CanonicalJson.fields("operation", "REFRESH_LOCAL_CATALOG", "scope", scope, "reason", reason));
    }

    /**
     * 来源候选已按固定引用排序；只摘要不可变契约，集合字段由各自 checksum 覆盖。
     */
    private static String digest(ToolCatalogStore.Batch batch) {
        var refs = Comparator.comparing(com.arte.core.pojo.reference.DefinitionRef::type).thenComparing(com.arte.core.pojo.reference.DefinitionRef::id).thenComparing(com.arte.core.pojo.reference.DefinitionRef::version);
        return CanonicalJson.digest(CanonicalJson.fields("format", "local-tool-discovery-v1", "providers", batch.providers().stream().sorted(Comparator.comparing(com.arte.ainew.pojo.tool.ToolProviderDefinition::definition, refs)).toList(),
                "schemas", batch.schemas().stream().sorted(Comparator.comparing(com.arte.ainew.pojo.tool.ToolSchemaDefinition::definition, refs)).map(s -> CanonicalJson.fields("definition", s.definition(), "checksum", s.checksum())).toList(),
                "tools", batch.tools().stream().sorted(Comparator.comparing(t -> t.tool().definition(), refs)).map(t -> CanonicalJson.fields("definition", t.tool().definition(), "checksum", t.tool().checksum(), "provider", t.provider(), "target", t.target())).toList()));
    }
}
