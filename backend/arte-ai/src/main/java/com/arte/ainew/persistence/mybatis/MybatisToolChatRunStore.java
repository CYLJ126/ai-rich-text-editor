package com.arte.ainew.persistence.mybatis;

import com.arte.ainew.application.tool.binding.ToolBindingConstraints;
import com.arte.ainew.application.tool.schema.ToolSchemaValidator;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.persistence.mybatis.mapper.SystemMapper;
import com.arte.ainew.persistence.mybatis.mapper.ToolChatRunMapper;
import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.ainew.pojo.tool.*;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.serialization.ToolCatalogDigests;
import com.arte.ainew.serialization.ToolChatRunDigests;
import com.arte.ainew.spi.persistence.ExecutionRecordCodec;
import com.arte.ainew.spi.persistence.ToolChatRunStore;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import javax.sql.DataSource;
import java.io.Serial;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.arte.ainew.pojo.execution.StoreOutcome.Code.*;
import static com.arte.ainew.pojo.tool.ToolChatRun.Phase.*;
import static com.arte.ainew.serialization.CanonicalJson.key;

/**
 * Run→当前资格→工作项→步骤／回执的短事务；数据库毫秒时钟仲裁期限和租约。
 * 只固定逻辑子操作，不调用模型／工具，不把两次异步提交伪装为原子受理。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 16:20 ✾
 */
public final class MybatisToolChatRunStore implements ToolChatRunStore {

    private final ToolChatRunMapper mapper;
    private final SystemMapper system;
    private final ExecutionRecordCodec codec;
    private final Scheduler scheduler;
    private final TransactionTemplate tx;
    private final ToolSchemaValidator schemas = new ToolSchemaValidator();

    public MybatisToolChatRunStore(DataSource source, ExecutionRecordCodec codec, Scheduler scheduler) {
        var session = new SqlSessionTemplate(ExecutionSqlSessionFactory.create(Objects.requireNonNull(source)));
        mapper = session.getMapper(ToolChatRunMapper.class);
        system = session.getMapper(SystemMapper.class);
        this.codec = Objects.requireNonNull(codec);
        this.scheduler = Objects.requireNonNull(scheduler);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setTimeout(10);
    }

    private <T> Mono<T> io(Supplier<T> work) {
        return Mono.defer(() -> Mono.fromCallable(() -> {
            if (Schedulers.isInNonBlockingThread())
                throw new IllegalStateException("Run JDBC requires a bounded worker");
            return tx.execute(status -> work.get());
        }).subscribeOn(scheduler));
    }

    private <T> Mono<StoreOutcome<T>> write(Supplier<StoreOutcome<T>> work) {
        return io(work).onErrorResume(Rejected.class, rejected -> Mono.just(StoreOutcome.rejected(rejected.code)));
    }

    private static final class Rejected extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 3387037586425730651L;

        final StoreOutcome.Code code;

        Rejected(StoreOutcome.Code code) {
            super(code.name());
            this.code = code;
        }
    }

    private static void require(boolean valid, StoreOutcome.Code code) {
        if (!valid) throw new Rejected(code);
    }

    private long now() {
        return system.databaseTime().toInstant().toEpochMilli();
    }

    private static String ownerKey(ExecutionOwner o) {
        return key(o.tenantId(), o.workspaceId(), o.subjectId());
    }

    private static void id(String value) {
        ContractChecks.id(value, "Run identifier");
    }

    private String encode(Object value) {
        String text = codec.encode(value);
        ContractChecks.require(text.getBytes(StandardCharsets.UTF_8).length <= 8 * 1024 * 1024, "Run record exceeds byte limit");
        return text;
    }

    private <T> T decode(String text, String digest, Class<T> type) {
        if (!CanonicalJson.sha256(text).equals(digest)) throw new IllegalStateException("Run record checksum mismatch");
        return codec.decode(text, type);
    }

    private ToolChatRun read(ToolChatRunMapper.Row row) {
        var run = decode(row.stateSnapshot(), row.stateDigest(), ToolChatRun.class);
        if (!key(run.runId()).equals(row.runKey()) || !ownerKey(run.owner()).equals(row.ownerKey()) || row.version() != run.version())
            throw new IllegalStateException("Run index mismatch");
        return run;
    }

    private ToolChatRunSnapshot input(ToolChatRunMapper.Row row) {
        var s = decode(row.inputSnapshot(), row.inputDigest(), ToolChatRunSnapshot.class);
        if (!key(s.context().executionId()).equals(row.runKey()) || !ownerKey(ExecutionOwner.from(s.context())).equals(row.ownerKey())
                || !key(s.context().idempotencyKey()).equals(row.idempotencyKey()) || s.context().deadline().toEpochMilli() != row.deadline()
                || !ToolChatRunDigests.submission(s).equals(row.submissionDigest()))
            throw new IllegalStateException("Run fixed input mismatch");
        return s;
    }

    private ToolChatRunMapper.Row owned(ExecutionOwner owner, String runId, boolean lock) {
        Objects.requireNonNull(owner);
        id(runId);
        var row = mapper.find(key(runId), lock);
        return row != null && row.ownerKey().equals(ownerKey(owner)) ? row : null;
    }

    private List<ToolChatStep> steps(ToolChatRunMapper.Row row) {
        var run = read(row);
        List<ToolChatStep> steps = mapper.steps(row.runKey()).stream().map(s -> {
            ToolChatStep step = decode(s.snapshot(), s.payloadDigest(), ToolChatStep.class);
            if (!step.runId().equals(run.runId()) || !key(step.childExecutionId()).equals(s.childKey()) || !s.runKey().equals(row.runKey()))
                throw new IllegalStateException("Run step identity mismatch");
            return step;
        }).toList();
        if (steps.size() != run.modelSteps() + run.toolSteps()
                || steps.stream().filter(s -> s.input() instanceof ToolChatStep.Model).count() != run.modelSteps())
            throw new IllegalStateException("Run step count mismatch");
        return steps;
    }

    @Override
    public Mono<ToolChatRun> find(ExecutionOwner owner, String runId) {
        Objects.requireNonNull(owner);
        id(runId);
        return io(() -> {
            var row = owned(owner, runId, false);
            return row == null ? null : read(row);
        });
    }

    @Override
    public Mono<ToolChatRunSnapshot> snapshot(ExecutionOwner owner, String runId) {
        Objects.requireNonNull(owner);
        id(runId);
        return io(() -> {
            var row = owned(owner, runId, false);
            return row == null ? null : input(row);
        });
    }

    @Override
    public Mono<List<ToolChatStep>> steps(ExecutionOwner owner, String runId) {
        Objects.requireNonNull(owner);
        id(runId);
        return io(() -> {
            var row = owned(owner, runId, true);
            return row == null ? List.of() : steps(row);
        });
    }

    @Override
    public Mono<StoreOutcome<ToolChatRun>> accept(ToolChatRunSnapshot candidate, Consumer<ToolChatRunSnapshot> authorization) {
        Objects.requireNonNull(candidate);
        Objects.requireNonNull(authorization);
        // Schema 编译和大载荷编码先在有界线程完成，避免占用连接及治理锁做 CPU 预检。
        return Mono.fromCallable(() -> {
            validate(candidate);
            return encode(candidate);
        }).subscribeOn(scheduler).flatMap(encoded -> write(() -> {
            String owner = ownerKey(ExecutionOwner.from(candidate.context()));
            String idem = key(candidate.context().idempotencyKey());
            system.ensureLock(key("tool-chat-accept", owner, idem));
            var previous = mapper.accepted(owner, idem);
            if (previous != null) {
                var original = input(previous);
                authorization.accept(original);
                require(previous.submissionDigest().equals(ToolChatRunDigests.submission(candidate)), IDEMPOTENCY_CONFLICT);
                return StoreOutcome.replayed(read(previous));
            }
            require(mapper.find(key(candidate.context().executionId()), true) == null, IDEMPOTENCY_CONFLICT);
            authorization.accept(candidate);
            long time = now();
            require(time < candidate.context().deadline().toEpochMilli(), INVALID_STATE);
            var run = new ToolChatRun(candidate.context().executionId(), ExecutionOwner.from(candidate.context()), ToolChatRun.State.ACCEPTED, 0,
                    new ToolChatRun.Checkpoint(0, READY_MODEL, List.of()), 0, 0, null, null, Instant.ofEpochMilli(time), Instant.ofEpochMilli(time));
            String state = encode(run), hash = key(run.runId());
            mapper.insert(new ToolChatRunMapper.Row(hash, owner, idem, ToolChatRunDigests.submission(candidate), CanonicalJson.sha256(encoded), encoded,
                    CanonicalJson.sha256(state), state, 0, candidate.context().deadline().toEpochMilli()));
            mapper.insertWork(new ToolChatRunMapper.Work(hash, time, true, "", 0, 0, 0));
            require(now() < candidate.context().deadline().toEpochMilli(), INVALID_STATE);
            return StoreOutcome.applied(run);
        }));
    }

    private void validate(ToolChatRunSnapshot s) {
        for (var e : s.tools().entries()) {
            ContractChecks.require(e.binding().tool().tool().checksum().equals(ToolCatalogDigests.tool(e.binding().tool().tool())), "Run tool checksum mismatch");
            ContractChecks.require(ToolBindingConstraints.intersect(e.binding().effectivePolicy(), e.policy()).equals(e.policy()), "Run policy cannot loosen binding");
            ContractChecks.require(e.inputSchema().checksum().equals(ToolCatalogDigests.schema(e.inputSchema().document())), "Run schema checksum mismatch");
            ContractChecks.require(e.binding().configurationDigest().equals(ToolCatalogDigests.configuration(e.binding().effectiveConfiguration())), "Run configuration checksum mismatch");
            schemas.compile(e.inputSchema().definition(), e.inputSchema().document(), ToolSchemaValidator.Purpose.INPUT);
        }
    }

    private static long leaseMillis(Duration duration) {
        Objects.requireNonNull(duration);
        ContractChecks.require(duration.compareTo(Duration.ofMillis(1)) >= 0 && duration.compareTo(Duration.ofMinutes(1)) <= 0, "Run lease must be within 1 ms and 1 minute");
        return duration.toMillis();
    }

    @Override
    public Mono<List<ToolChatCommands.Lease>> claim(String workerId, int limit, Duration duration) {
        id(workerId);
        ContractChecks.range(limit, "claim limit", 1, 32);
        long millis = leaseMillis(duration);
        return io(() -> {
            var leases = new ArrayList<ToolChatCommands.Lease>();
            // 全批按主键持锁，避免两个领取者以不同到期顺序反向持有多个 Run 锁。
            var candidates = new ArrayList<>(mapper.due(now(), limit));
            Collections.sort(candidates);
            for (var hash : candidates) {
                var row = mapper.find(hash, true);
                if (row == null) continue;
                var work = mapper.work(hash);
                long time = now();
                if (work == null || !work.enabled() || work.availableAt() > time || work.leaseUntil() > time) continue;
                var run = read(row);
                if (run.state().terminal()) throw new IllegalStateException("Terminal Run has enabled work");
                var lease = new ToolChatCommands.Lease(run.runId(), run.owner(), workerId, Math.incrementExact(work.fence()), work.signalVersion(), Instant.ofEpochMilli(time + millis));
                mapper.updateWork(new ToolChatRunMapper.Work(hash, work.availableAt(), true, workerId, lease.until().toEpochMilli(), lease.fence(), work.signalVersion()));
                leases.add(lease);
            }
            return List.copyOf(leases);
        });
    }

    private static boolean holds(ToolChatRunMapper.Work w, ToolChatCommands.Lease lease, long time) {
        return w != null && w.enabled() && w.fence() == lease.fence() && w.workerId().equals(lease.workerId())
                && lease.signalVersion() <= w.signalVersion() && w.leaseUntil() > time;
    }

    @Override
    public Mono<StoreOutcome<ToolChatCommands.Lease>> renew(ToolChatCommands.Lease lease, Duration duration) {
        Objects.requireNonNull(lease);
        long millis = leaseMillis(duration);
        return write(() -> {
            var row = owned(lease.owner(), lease.runId(), true);
            require(row != null, NOT_FOUND);
            var w = mapper.work(row.runKey());
            long time = now();
            require(holds(w, lease, time), LEASE_LOST);
            long until = Math.max(w.leaseUntil(), time + millis);
            mapper.updateWork(new ToolChatRunMapper.Work(w.runKey(), w.availableAt(), true, w.workerId(), until, w.fence(), w.signalVersion()));
            return StoreOutcome.applied(new ToolChatCommands.Lease(lease.runId(), lease.owner(), lease.workerId(), lease.fence(), lease.signalVersion(), Instant.ofEpochMilli(until)));
        });
    }

    @Override
    public Mono<StoreOutcome<ToolChatRun>> wake(ExecutionOwner owner, String runId) {
        Objects.requireNonNull(owner);
        id(runId);
        return write(() -> {
            var row = owned(owner, runId, true);
            require(row != null, NOT_FOUND);
            var run = read(row);
            if (run.state().terminal()) return StoreOutcome.replayed(run);
            var w = mapper.work(row.runKey());
            mapper.updateWork(new ToolChatRunMapper.Work(w.runKey(), now(), true, w.workerId(), w.leaseUntil(), w.fence(), Math.incrementExact(w.signalVersion())));
            return StoreOutcome.applied(run);
        });
    }

    @Override
    public Mono<StoreOutcome<ToolChatCommands.Receipt>> advance(ToolChatCommands.Advance c, Consumer<ToolChatCommands.Guard> authorization) {
        Objects.requireNonNull(c);
        Objects.requireNonNull(authorization);
        return write(() -> {
            var row = owned(c.lease().owner(), c.lease().runId(), true);
            require(row != null, NOT_FOUND);
            var run = read(row);
            var snapshot = input(row);
            authorization.accept(new ToolChatCommands.Guard(snapshot, run));
            String digest = ToolChatRunDigests.advance(c), commandKey = key(c.commandKey());
            var receipt = mapper.receipt(row.runKey(), commandKey);
            if (receipt != null) {
                require(receipt.requestDigest().equals(digest), IDEMPOTENCY_CONFLICT);
                var original = decode(receipt.snapshot(), receipt.payloadDigest(), ToolChatCommands.Receipt.class);
                if (!original.run().runId().equals(run.runId()) || !original.run().owner().equals(run.owner()))
                    throw new IllegalStateException("Run receipt identity mismatch");
                return StoreOutcome.replayed(original);
            }
            var work = mapper.work(row.runKey());
            long time = now();
            require(holds(work, c.lease(), time), LEASE_LOST);
            require(run.version() == c.expectedVersion(), VERSION_CONFLICT);
            require(!run.state().terminal(), INVALID_STATE);
            require(time < row.deadline() || c.state() == ToolChatRun.State.TIMED_OUT || c.state() == ToolChatRun.State.CANCELLED, INVALID_STATE);
            require(c.state() != ToolChatRun.State.TIMED_OUT || time >= row.deadline(), INVALID_STATE);
            var previousSteps = steps(row);
            var plans = prepareSteps(c, snapshot, run, previousSteps, Instant.ofEpochMilli(time));
            var children = !plans.isEmpty() ? plans.stream().map(ToolChatStep::childExecutionId).toList()
                    : c.phase() == READY_MODEL || c.phase() == FINISHED ? List.<String>of() : run.checkpoint().children();
            var checkpoint = new ToolChatRun.Checkpoint(c.roundIndex(), c.phase(), children);
            var active = new ArrayList<>(previousSteps);
            active.addAll(plans);
            for (var child : children) {
                var step = active.stream().filter(s -> s.childExecutionId().equals(child)).findFirst().orElseThrow(() -> new IllegalStateException("Checkpoint child is missing"));
                require(step.roundIndex() == checkpoint.roundIndex(), INVALID_STATE);
                require(c.phase() != WAITING_MODEL || step.input() instanceof ToolChatStep.Model, INVALID_STATE);
                require(c.phase() != WAITING_TOOLS && c.phase() != WAITING_APPROVAL || step.input() instanceof ToolChatStep.Tool, INVALID_STATE);
            }
            int models = run.modelSteps() + (int) plans.stream().filter(p -> p.input() instanceof ToolChatStep.Model).count();
            int tools = run.toolSteps() + (int) plans.stream().filter(p -> p.input() instanceof ToolChatStep.Tool).count();
            require(models <= snapshot.limits().maxModelRounds() && tools <= snapshot.limits().maxToolCalls(), INVALID_STATE);
            require(c.roundIndex() < snapshot.limits().maxModelRounds(), INVALID_STATE);
            var next = new ToolChatRun(run.runId(), run.owner(), c.state(), Math.incrementExact(run.version()), checkpoint, models, tools,
                    c.result(), c.error(), run.acceptedAt(), Instant.ofEpochMilli(Math.max(time, run.updatedAt().toEpochMilli())));
            var result = new ToolChatCommands.Receipt(next, plans);
            String state = encode(next), response = encode(result);
            for (var step : plans) {
                String text = encode(step);
                mapper.insertStep(new ToolChatRunMapper.Step(key(step.childExecutionId()), row.runKey(), CanonicalJson.sha256(text), text));
            }
            require(mapper.update(row.runKey(), run.version(), CanonicalJson.sha256(state), state) == 1, VERSION_CONFLICT);
            long due = c.nextWakeAt() == null ? row.deadline() : Math.min(row.deadline(), Math.max(time, c.nextWakeAt().toEpochMilli()));
            if (work.signalVersion() > c.lease().signalVersion()) due = time;
            mapper.updateWork(new ToolChatRunMapper.Work(row.runKey(), due, !next.state().terminal(), "", 0, work.fence(), work.signalVersion()));
            mapper.insertReceipt(row.runKey(), commandKey, new ToolChatRunMapper.Receipt(digest, CanonicalJson.sha256(response), response));
            require(holds(work, c.lease(), now()), LEASE_LOST);
            require(now() < row.deadline() || next.state() == ToolChatRun.State.TIMED_OUT || next.state() == ToolChatRun.State.CANCELLED, INVALID_STATE);
            return StoreOutcome.applied(result);
        });
    }

    private List<ToolChatStep> prepareSteps(ToolChatCommands.Advance c, ToolChatRunSnapshot snapshot, ToolChatRun run,
                                            List<ToolChatStep> previous, Instant time) {
        var phase = run.checkpoint().phase();
        int round = run.checkpoint().roundIndex();
        if (c.phase() == FINISHED) {
            require(c.plans().isEmpty() && c.roundIndex() == round, INVALID_STATE);
            require(c.state() != ToolChatRun.State.SUCCEEDED || phase == WAITING_MODEL, INVALID_STATE);
            return List.of();
        }
        if (c.phase() == READY_MODEL) {
            require((phase == WAITING_TOOLS || phase == RECONCILING) && c.roundIndex() == round + 1 && c.plans().isEmpty(), INVALID_STATE);
            return List.of();
        }
        if (c.plans().isEmpty()) {
            require(c.roundIndex() == round && (c.phase() == phase || c.phase() == RECONCILING
                    || phase == WAITING_TOOLS && c.phase() == WAITING_APPROVAL || phase == WAITING_APPROVAL && c.phase() == WAITING_TOOLS
                    || phase == RECONCILING && (c.phase() == WAITING_MODEL || c.phase() == WAITING_TOOLS)), INVALID_STATE);
            return List.of();
        }
        require(c.roundIndex() == round, INVALID_STATE);
        require(phase == READY_MODEL && c.phase() == WAITING_MODEL && c.plans().size() == 1
                && c.plans().getFirst().input() instanceof ToolChatStep.Model
                || phase == WAITING_MODEL && c.phase() == WAITING_TOOLS && c.plans().stream().allMatch(p -> p.input() instanceof ToolChatStep.Tool), INVALID_STATE);
        var added = new ArrayList<ToolChatStep>();
        var ids = new HashSet<String>();
        var callIds = new HashSet<String>();
        previous.forEach(p -> {
            ids.add(p.childExecutionId());
            if (p.input() instanceof ToolChatStep.Tool t) callIds.add(t.call().callId());
        });
        for (var plan : c.plans()) {
            require(plan.roundIndex() == round, INVALID_STATE);
            var step = ToolChatStep.planned(run.runId(), plan, time);
            require(ids.add(step.childExecutionId()), IDEMPOTENCY_CONFLICT);
            if (plan.input() instanceof ToolChatStep.Model model) {
                require(model.context().modelBinding().equals(snapshot.model().definition()) && model.context().expiresAt().isAfter(time)
                        && (round != 0 || model.context().equals(snapshot.initialContext())), INVALID_STATE);
            } else if (plan.input() instanceof ToolChatStep.Tool tool) {
                require(callIds.add(tool.call().callId()), IDEMPOTENCY_CONFLICT);
                ToolSetSnapshot.Entry entry;
                try {
                    entry = snapshot.tools().tool(tool.call().tool());
                } catch (IllegalArgumentException missing) {
                    throw new Rejected(INVALID_STATE);
                }
                require(schemas.compile(entry.inputSchema().definition(), entry.inputSchema().document(), ToolSchemaValidator.Purpose.INPUT)
                        .validate(tool.call().arguments(), entry.policy().maxInputBytes()).valid(), INVALID_STATE);
            }
            added.add(step);
        }
        return List.copyOf(added);
    }
}
