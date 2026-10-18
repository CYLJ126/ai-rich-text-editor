package com.arte.ainew.persistence.mybatis;

import com.arte.ainew.application.tool.schema.ToolSchemaValidator;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.persistence.mybatis.mapper.SystemMapper;
import com.arte.ainew.persistence.mybatis.mapper.ToolCatalogMapper;
import com.arte.ainew.persistence.mybatis.mapper.ToolCatalogMapper.Row;
import com.arte.ainew.pojo.control.ExecutionTarget;
import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.ainew.pojo.tool.ToolDefinitionRevision;
import com.arte.ainew.pojo.tool.ToolProviderDefinition;
import com.arte.ainew.pojo.tool.ToolSchemaDefinition;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.serialization.ToolCatalogDigests;
import com.arte.ainew.spi.persistence.ExecutionRecordCodec;
import com.arte.ainew.spi.persistence.ToolCatalogStore;
import com.arte.core.application.platform.PlatformTimes;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;

import static com.arte.ainew.pojo.execution.StoreOutcome.Code.*;
import static com.arte.ainew.serialization.CanonicalJson.key;

/**
 * 受管目录的不可变版本存储：确定顺序的数据库锁、精确引用、同库事务和封闭快照。
 * 不执行 DDL、不开放工具、不提供当前主体授权；管理入口必须先经现有公共授权链路。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 13:52 ✾
 */
public final class MybatisToolCatalogStore implements ToolCatalogStore {

    public static final int MAX_RECORD_BYTES = 1_000_000;
    public static final int MAX_BATCH_BYTES = 8 * 1024 * 1024;

    private final DataSource dataSource;
    private final ToolCatalogMapper mapper;
    private final SystemMapper locks;
    private final ExecutionRecordCodec codec;
    private final TransactionTemplate transaction;
    private final Scheduler scheduler;
    private final Clock clock;
    private final ToolSchemaValidator schemas = new ToolSchemaValidator();

    public MybatisToolCatalogStore(DataSource dataSource, ExecutionRecordCodec codec, Scheduler scheduler, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource);
        var sessions = new SqlSessionTemplate(ExecutionSqlSessionFactory.create(dataSource));
        mapper = sessions.getMapper(ToolCatalogMapper.class);
        locks = sessions.getMapper(SystemMapper.class);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setTimeout(30);
        this.codec = Objects.requireNonNull(codec, "codec");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    boolean uses(DataSource source) {
        return dataSource == source;
    }

    private <T> Mono<T> io(Supplier<T> work) {
        return Mono.fromCallable(() -> {
            if (Schedulers.isInNonBlockingThread())
                throw new IllegalStateException("Tool catalog requires a bounded worker");
            return work.get();
        }).subscribeOn(scheduler);
    }

    private record Prepared(DefinitionRef ref, Object value, Row row) {
    }

    @Override
    public Mono<StoreOutcome<Batch>> put(Batch batch) {
        Objects.requireNonNull(batch, "batch");
        return io(() -> {
            // 新原文的基础校验与编码在持锁前完成；依赖用途及默认值在事务内按固定版本核对。
            var prepared = prepare(batch);
            return transaction.execute(status -> write(batch, prepared));
        });
    }

    private List<Prepared> prepare(Batch batch) {
        var values = new ArrayList<Object>();
        values.addAll(batch.providers());
        values.addAll(batch.schemas());
        values.addAll(batch.tools());
        var now = PlatformTimes.businessNow(clock);
        long micros = Math.addExact(Math.multiplyExact(now.getEpochSecond(), 1_000_000L), now.getNano() / 1000);
        var result = new ArrayList<Prepared>();
        long total = 0;
        for (var value : values) {
            validateContent(value);
            String encoded = CanonicalJson.normalize(codec.encode(value));
            int bytes = encoded.getBytes(StandardCharsets.UTF_8).length;
            ContractChecks.require(bytes <= MAX_RECORD_BYTES, "Tool catalog record exceeds byte limit");
            total += bytes;
            ContractChecks.require(total <= MAX_BATCH_BYTES, "Tool catalog batch exceeds byte limit");
            var ref = reference(value);
            var tool = value instanceof ToolDefinitionRevision revision ? revision : null;
            var row = new Row(id(ref), key(ref.type(), ref.id()), key(ref.version()),
                    tool == null ? null : id(tool.provider()), tool == null ? null : id(tool.tool().inputSchema()),
                    tool == null ? null : id(tool.tool().outputSchema()), tool == null ? null : id(tool.tool().configurationSchema()),
                    CanonicalJson.sha256(encoded), encoded, micros);
            result.add(new Prepared(ref, value, row));
        }
        return result;
    }

    private StoreOutcome<Batch> write(Batch batch, List<Prepared> prepared) {
        var lockKeys = new TreeSet<String>();
        for (var item : prepared) lockKeys.add(id(item.ref()));
        for (var tool : batch.tools()) {
            for (var dependency : List.of(tool.provider(), tool.tool().inputSchema(), tool.tool().outputSchema(), tool.tool().configurationSchema()))
                lockKeys.add(id(dependency));
        }
        // 全部写入和依赖采用同一排序，跨实例登记共享 Schema 时也不反向持锁。
        for (String lock : lockKeys) {
            String lockKey = key("tool-catalog-lock", lock);
            mapper.ensureCatalogLock(lockKey);
            if (!lockKey.equals(locks.lock(lockKey)))
                throw new IllegalStateException("Tool catalog lock was not acquired");
        }
        var known = new HashMap<DefinitionRef, Object>();
        var missing = new ArrayList<Prepared>();
        for (var item : prepared) {
            Object existing = load(item.ref(), true);
            // 比较解码后的值，不用 Set 序列化顺序判断跨进程重放。
            if (existing != null && !existing.equals(item.value())) return StoreOutcome.rejected(IDEMPOTENCY_CONFLICT);
            known.put(item.ref(), item.value());
            if (existing == null) missing.add(item);
        }
        for (var revision : batch.tools()) {
            var provider = dependency(known, revision.provider(), ToolProviderDefinition.class);
            var input = dependency(known, revision.tool().inputSchema(), ToolSchemaDefinition.class);
            var output = dependency(known, revision.tool().outputSchema(), ToolSchemaDefinition.class);
            var configuration = dependency(known, revision.tool().configurationSchema(), ToolSchemaDefinition.class);
            if (provider == null || input == null || output == null || configuration == null)
                return StoreOutcome.rejected(NOT_FOUND);
            boolean local = revision.target() instanceof ExecutionTarget.Local;
            if (local != (provider.kind() == ToolProviderDefinition.Kind.LOCAL))
                return StoreOutcome.rejected(INVALID_STATE);
            if (revision.target() instanceof ExecutionTarget.Remote remote && !remote.connection().equals(provider.connection()))
                return StoreOutcome.rejected(INVALID_STATE);
            schemas.compile(input.definition(), input.document(), ToolSchemaValidator.Purpose.INPUT);
            schemas.compile(output.definition(), output.document(), ToolSchemaValidator.Purpose.OUTPUT);
            var plan = schemas.compile(configuration.definition(), configuration.document(), ToolSchemaValidator.Purpose.CONFIGURATION);
            ContractChecks.require(plan.validate(revision.tool().defaultConfiguration(), MAX_RECORD_BYTES).valid(),
                    "Tool default configuration violates its fixed Schema");
        }
        // 所有可预期拒绝都发生在业务 INSERT 之前；SQL 故障仍抛出，使三张表及锁记录一起回滚。
        for (var item : missing) {
            int affected = switch (item.ref().type()) {
                case "tool-provider" -> mapper.insertProvider(item.row());
                case "schema" -> mapper.insertSchema(item.row());
                case "tool" -> mapper.insertTool(item.row());
                default -> throw new IllegalStateException("Unregistered tool catalog reference");
            };
            if (affected != 1) throw new IllegalStateException("Tool catalog insert was not applied");
        }
        return missing.isEmpty() ? StoreOutcome.replayed(batch) : StoreOutcome.applied(batch);
    }

    private <T> T dependency(Map<DefinitionRef, Object> known, DefinitionRef ref, Class<T> type) {
        if (!known.containsKey(ref)) known.put(ref, load(ref, true));
        return type.cast(known.get(ref));
    }

    @Override
    public Mono<ToolProviderDefinition> findProvider(DefinitionRef provider) {
        Objects.requireNonNull(provider).requireType("tool-provider");
        return io(() -> ToolProviderDefinition.class.cast(load(provider, false)));
    }

    @Override
    public Mono<ToolSchemaDefinition> findSchema(DefinitionRef schema) {
        Objects.requireNonNull(schema).requireType("schema");
        return io(() -> ToolSchemaDefinition.class.cast(load(schema, false)));
    }

    @Override
    public Mono<ToolDefinitionRevision> findTool(DefinitionRef tool) {
        Objects.requireNonNull(tool).requireType("tool");
        return io(() -> ToolDefinitionRevision.class.cast(load(tool, false)));
    }

    private Object load(DefinitionRef ref, boolean lock) {
        Row row = switch (ref.type()) {
            case "tool-provider" -> mapper.provider(id(ref), lock);
            case "schema" -> mapper.schema(id(ref), lock);
            case "tool" -> mapper.tool(id(ref), lock);
            default -> throw new IllegalArgumentException("Unsupported tool catalog reference");
        };
        Object value = row == null ? null : decode(row, type(ref.type()));
        if (value != null && !ref.equals(reference(value)))
            throw new IllegalStateException("Tool catalog identity mismatch");
        return value;
    }

    private <T> T decode(Row row, Class<T> type) {
        if (row.workspaceSnapshot().getBytes(StandardCharsets.UTF_8).length > MAX_RECORD_BYTES
                || !row.payloadDigest().equals(CanonicalJson.sha256(row.workspaceSnapshot())))
            throw new IllegalStateException("Tool catalog snapshot integrity failure");
        T value;
        try {
            value = Objects.requireNonNull(codec.decode(row.workspaceSnapshot(), type));
            validateContent(value);
        } catch (RuntimeException error) {
            throw new IllegalStateException("Tool catalog snapshot is invalid", error);
        }
        var ref = reference(value);
        if (!row.idKey().equals(id(ref)) || !row.identityKey().equals(key(ref.type(), ref.id()))
                || !row.versionKey().equals(key(ref.version())))
            throw new IllegalStateException("Tool catalog index identity mismatch");
        if (value instanceof ToolDefinitionRevision revision && (!id(revision.provider()).equals(row.providerKey())
                || !id(revision.tool().inputSchema()).equals(row.inputSchemaKey())
                || !id(revision.tool().outputSchema()).equals(row.outputSchemaKey())
                || !id(revision.tool().configurationSchema()).equals(row.configurationSchemaKey())))
            throw new IllegalStateException("Tool catalog dependency index mismatch");
        return value;
    }

    private void validateContent(Object value) {
        if (value instanceof ToolSchemaDefinition schema) {
            ContractChecks.require(schema.checksum().equals(ToolCatalogDigests.schema(schema.document())), "Schema checksum mismatch");
            schemas.compile(schema.definition(), schema.document(), ToolSchemaValidator.Purpose.OUTPUT);
        } else if (value instanceof ToolDefinitionRevision revision) {
            ContractChecks.require(revision.tool().checksum().equals(ToolCatalogDigests.tool(revision.tool())), "Tool checksum mismatch");
        }
    }

    @Override
    public Mono<Page<ToolProviderDefinition>> listProviders(String afterKey, int limit) {
        pageArguments(afterKey, limit);
        return io(() -> page(mapper.providers(afterKey, limit + 1), limit, ToolProviderDefinition.class));
    }

    @Override
    public Mono<Page<ToolDefinitionRevision>> listTools(DefinitionRef provider, String afterKey, int limit) {
        Objects.requireNonNull(provider).requireType("tool-provider");
        pageArguments(afterKey, limit);
        return io(() -> page(mapper.tools(id(provider), afterKey, limit + 1), limit, ToolDefinitionRevision.class));
    }

    private static void pageArguments(String after, int limit) {
        ContractChecks.range(limit, "limit", 1, 100);
        if (after != null) ContractChecks.digest(after, "afterKey");
    }

    private <T> Page<T> page(List<Row> rows, int limit, Class<T> type) {
        boolean more = rows.size() > limit;
        var selected = more ? rows.subList(0, limit) : rows;
        return new Page<>(selected.stream().map(row -> decode(row, type)).toList(),
                more ? selected.getLast().idKey() : null);
    }

    private static Class<?> type(String type) {
        return switch (type) {
            case "tool-provider" -> ToolProviderDefinition.class;
            case "schema" -> ToolSchemaDefinition.class;
            case "tool" -> ToolDefinitionRevision.class;
            default -> throw new IllegalArgumentException("Unsupported tool catalog type");
        };
    }

    private static DefinitionRef reference(Object value) {
        if (value instanceof ToolProviderDefinition provider) return provider.definition();
        if (value instanceof ToolSchemaDefinition schema) return schema.definition();
        if (value instanceof ToolDefinitionRevision tool) return tool.tool().definition();
        throw new IllegalArgumentException("Unsupported tool catalog value");
    }

    private static String id(DefinitionRef ref) {
        return key(ref.type(), ref.id(), ref.version());
    }

    /**
     * 同物理数据源的生命周期事务读取固定事实；不派发异步任务，不锁定或修改不可变定义行。
     */
    ToolCatalogStore.Batch releaseArtifacts(DefinitionRef ref) {
        var tool = (ToolDefinitionRevision) load(ref, false);
        if (tool == null) return null;
        var documents = new LinkedHashMap<DefinitionRef, ToolSchemaDefinition>();
        for (var schema : List.of(tool.tool().inputSchema(), tool.tool().outputSchema(), tool.tool().configurationSchema()))
            documents.put(schema, Objects.requireNonNull((ToolSchemaDefinition) load(schema, false), "Missing release Schema"));
        var provider = Objects.requireNonNull((ToolProviderDefinition) load(tool.provider(), false), "Missing release provider");
        return new ToolCatalogStore.Batch(List.of(provider), List.copyOf(documents.values()), List.of(tool));
    }
}
