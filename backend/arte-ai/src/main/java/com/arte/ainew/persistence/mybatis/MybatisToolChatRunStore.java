package com.arte.ainew.persistence.mybatis;

import com.arte.ainew.application.tool.binding.ToolBindingConstraints;
import com.arte.ainew.application.tool.schema.ToolSchemaValidator;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.persistence.mybatis.mapper.*;
import com.arte.ainew.pojo.conversation.AnswerCandidate;
import com.arte.ainew.pojo.conversation.Conversation;
import com.arte.ainew.pojo.conversation.Turn;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.execution.InvocationResult;
import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.ainew.pojo.generation.ChatMessage;
import com.arte.ainew.pojo.generation.GenerationRequest;
import com.arte.ainew.pojo.generation.ModelResult;
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
    private final AdmissionMapper admission;
    private final ExecutionMapper executions;
    private final PayloadMapper payloads;
    private final OutboxMapper outbox;
    private final ConversationMetadataPersistence metadata;
    private final ToolChatBudgetPersistence budgets;
    private final ExecutionRecordCodec codec;
    private final Scheduler scheduler;
    private final TransactionTemplate tx;
    private final ToolSchemaValidator schemas = new ToolSchemaValidator();

    public MybatisToolChatRunStore(DataSource source, ExecutionRecordCodec codec, Scheduler scheduler) {
        var session = new SqlSessionTemplate(ExecutionSqlSessionFactory.create(Objects.requireNonNull(source)));
        mapper = session.getMapper(ToolChatRunMapper.class);
        budgets = new ToolChatBudgetPersistence(session, codec);
        system = session.getMapper(SystemMapper.class);
        admission = session.getMapper(AdmissionMapper.class);
        executions = session.getMapper(ExecutionMapper.class);
        payloads = session.getMapper(PayloadMapper.class);
        outbox = session.getMapper(OutboxMapper.class);
        metadata = new ConversationMetadataPersistence(session.getMapper(ConversationMetadataMapper.class), admission,
                executions, session.getMapper(EventMapper.class), codec, system::ensureLock, () -> Instant.ofEpochMilli(now()), mapper);
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
    public Mono<ToolChatStep> step(ExecutionOwner owner, String runId, String childExecutionId) {
        Objects.requireNonNull(owner);
        id(runId);
        id(childExecutionId);
        return io(() -> {
            if (owned(owner, runId, false) == null) return null;
            var row = mapper.step(key(childExecutionId));
            if (row == null || !row.runKey().equals(key(runId))) return null;
            var value = decode(row.snapshot(), row.payloadDigest(), ToolChatStep.class);
            if (!value.runId().equals(runId) || !value.childExecutionId().equals(childExecutionId))
                throw new IllegalStateException("Run single step identity mismatch");
            return value;
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
        return accept(candidate, null, 0, authorization);
    }

    @Override
    public Mono<StoreOutcome<ToolChatRun>> acceptChat(ToolChatRunSnapshot candidate, Turn turn, long expectedVersion,
                                                      Consumer<ToolChatRunSnapshot> authorization) {
        Objects.requireNonNull(turn);
        ContractChecks.range(expectedVersion, "conversation version", 0, Long.MAX_VALUE - 1);
        return accept(candidate, turn, expectedVersion, authorization);
    }

    private Mono<StoreOutcome<ToolChatRun>> accept(ToolChatRunSnapshot candidate, Turn turn, long expectedVersion,
                                                   Consumer<ToolChatRunSnapshot> authorization) {
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
                var link = mapper.chat(previous.runKey());
                require(turn == null ? link == null : link != null && link.requestDigest().equals(chatDigest(turn, expectedVersion)), IDEMPOTENCY_CONFLICT);
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
            budgets.initialize(candidate);
            if (turn != null) attach(candidate, run, turn, expectedVersion);
            notifyRun(run);
            require(now() < candidate.context().deadline().toEpochMilli(), INVALID_STATE);
            return StoreOutcome.applied(run);
        }));
    }

    private String chatDigest(Turn turn, long version) {
        return key(Long.toString(version), encode(turn));
    }

    /**
     * 持锁顺序为 Run→会话→Turn；子调用不直接占用会话。
     */
    private void attach(ToolChatRunSnapshot snapshot, ToolChatRun run, Turn turn, long version) {
        var bytes = admission.conversationSnapshot(key(turn.conversationId()), true);
        require(bytes != null, NOT_FOUND);
        var conversation = codec.decode(bytes, Conversation.class);
        require(conversation.owner().equals(run.owner()), NOT_FOUND);
        require(conversation.state() == Conversation.State.ACTIVE, INVALID_STATE);
        var gate = admission.conversationGateCurrent(key(turn.conversationId()));
        require(gate.versionNo() == version && conversation.version() == version, VERSION_CONFLICT);
        require(gate.activeInvocation() == null, CONVERSATION_BUSY);
        var history = snapshot.initialContext().history();
        require(history == null || history.conversationId().equals(turn.conversationId()) && history.conversationVersion() == version, INVALID_STATE);
        var candidate = AnswerCandidate.run(run.runId());
        require(!turn.candidates().isEmpty() && turn.candidates().getLast().equals(candidate), INVALID_STATE);
        var messages = snapshot.initialContext().messages();
        require(!messages.isEmpty() && turn.userMessage().equals(messages.getLast()), INVALID_STATE);
        String prior = admission.turnSnapshot(key(turn.turnId()), true);
        String title = conversation.title();
        if (prior == null) {
            require(turn.version() == 0 && turn.selectedCandidate() == null && turn.candidates().equals(List.of(candidate)), INVALID_STATE);
            require(turn.sequence() == admission.maximumTurnSequence(key(turn.conversationId())) + 1, VERSION_CONFLICT);
            for (String ref : new String[]{turn.parentTurnId(), turn.supersedesTurnId()})
                if (ref != null) {
                    String linked = admission.turnSnapshot(key(ref), false);
                    require(linked != null && codec.decode(linked, Turn.class).conversationId().equals(turn.conversationId()), INVALID_STATE);
                }
            admission.insertTurn(key(turn.turnId()), key(turn.conversationId()), turn.sequence(), encode(turn));
            title = metadata.userMessage(conversation, turn);
        } else {
            var old = codec.decode(prior, Turn.class);
            var expected = new ArrayList<>(old.candidates());
            expected.add(candidate);
            require(turn.equals(Turn.typed(old.turnId(), old.conversationId(), old.sequence(), old.parentTurnId(), old.supersedesTurnId(),
                    old.userMessage(), expected, old.effectiveCandidate(), old.version() + 1, old.createdAt(), turn.updatedAt())), VERSION_CONFLICT);
            admission.saveTurn(encode(turn), key(turn.turnId()));
        }
        var updated = new Conversation(conversation.conversationId(), conversation.owner(), title, version + 1,
                conversation.chatProfile(), conversation.resources(), conversation.state(), conversation.createdAt(),
                Instant.ofEpochMilli(Math.max(now(), conversation.updatedAt().toEpochMilli())));
        admission.advanceTypedConversation(updated.version(), key(run.runId()), "TOOL_CHAT_RUN", encode(updated), key(turn.conversationId()));
        mapper.insertChat(new ToolChatRunMapper.Chat(key(run.runId()), turn.conversationId(), turn.turnId(), version, chatDigest(turn, version), null));
    }

    private void notifyRun(ToolChatRun run) {
        var event = ToolChatRunEvent.of(run);
        mapper.insertEvent(key(run.runId()), event.sequence(), encode(event));
        outbox.insertMessage(key(run.runId(), "RUN_EVENT", Long.toString(event.sequence())), key(run.runId()), run.runId(),
                run.owner().tenantId(), run.owner().workspaceId(), run.owner().subjectId(), "RUN_EVENT", event.sequence());
    }

    @Override
    public Mono<ToolChatLink> link(ExecutionOwner owner, String runId) {
        Objects.requireNonNull(owner);
        id(runId);
        return io(() -> {
            if (owned(owner, runId, false) == null) return null;
            var c = mapper.chat(key(runId));
            return c == null ? null : new ToolChatLink(runId, c.conversationId(), c.turnId(), c.conversationVersion(), c.finalInvocationId());
        });
    }

    @Override
    public Mono<List<ToolChatRunEvent>> events(ExecutionOwner owner, String runId, long after, int limit) {
        Objects.requireNonNull(owner);
        id(runId);
        ContractChecks.range(after, "Run cursor", 0, Long.MAX_VALUE);
        ContractChecks.range(limit, "Run event limit", 1, 100);
        return io(() -> owned(owner, runId, false) == null ? List.of() : mapper.events(key(runId), after, limit).stream()
                .map(b -> codec.decode(b, ToolChatRunEvent.class)).peek(e -> {
                    if (!e.runId().equals(runId) || e.sequence() <= after)
                        throw new IllegalStateException("Run event identity mismatch");
                }).toList());
    }

    private void completeChat(ToolChatRunSnapshot snapshot, ToolChatRun previous, ToolChatRun next, List<ToolChatStep> steps) {
        var link = mapper.chat(key(next.runId()));
        if (link == null || !next.state().terminal()) return;
        var cb = admission.conversationSnapshot(key(link.conversationId()), true);
        require(cb != null, NOT_FOUND);
        var conversation = codec.decode(cb, Conversation.class);
        require(conversation.owner().equals(next.owner()), NOT_FOUND);
        var turn = codec.decode(admission.turnSnapshot(key(link.turnId()), true), Turn.class);
        var candidate = AnswerCandidate.run(next.runId());
        require(turn.conversationId().equals(link.conversationId()) && turn.candidates().getLast().equals(candidate), INVALID_STATE);
        var gate = admission.conversationGateCurrent(key(link.conversationId()));
        require(key(next.runId()).equals(gate.activeInvocation()) && "TOOL_CHAT_RUN".equals(gate.activeKind()), INVALID_STATE);
        if (next.state() == ToolChatRun.State.SUCCEEDED) {
            require(previous.checkpoint().children().size() == 1, INVALID_STATE);
            String child = previous.checkpoint().children().getFirst();
            String bytes = executions.invocationSnapshot(key(child), false);
            require(bytes != null, INVALID_STATE);
            var invocation = codec.decode(bytes, Invocation.class);
            var modelStep = steps.stream().filter(s -> s.childExecutionId().equals(child)).findFirst().orElseThrow();
            require(modelStep.input() instanceof ToolChatStep.Model, INVALID_STATE);
            var modelContext = ((ToolChatStep.Model) modelStep.input()).context();
            require(invocation.request().context().executionId().equals(child)
                    && next.runId().equals(invocation.request().context().parentExecutionId())
                    && next.owner().equals(ExecutionOwner.from(invocation.request().context()))
                    && snapshot.context().traceId().equals(invocation.request().context().traceId())
                    && !invocation.request().context().deadline().isAfter(snapshot.context().deadline())
                    && snapshot.context().authorization().scopes().containsAll(invocation.request().context().authorization().scopes())
                    && Objects.equals(invocation.contextSnapshotId(), modelContext.snapshotId())
                    && invocation.request().input() instanceof GenerationRequest generation
                    && generation.messages().equals(modelContext.messages()) && generation.options().equals(snapshot.generation())
                    && invocation.request().capability().equals(snapshot.model().capability().definition())
                    && invocation.conversation() == null && invocation.state() == Invocation.State.SUCCEEDED
                    && invocation.request().kind() == com.arte.ainew.pojo.control.CapabilityDescriptor.Kind.GENERATION
                    && invocation.request().binding().equals(snapshot.model().definition())
                    && Objects.equals(invocation.result(), next.result()) && !next.result().partial(), INVALID_STATE);
            var ref = next.result();
            var row = payloads.result(ref.resultId());
            require(row != null && row.ownerKey().equals(ownerKey(next.owner())) && row.invocationKey().equals(key(child))
                    && row.attemptKey().equals(key(invocation.activeAttemptId())) && row.resultType().equals("model-result")
                    && ref.resultType().equals(row.resultType()) && ref.schemaVersion() == row.schemaVersion()
                    && row.partial() == 0 && ref.contentDigest().equals(row.payloadDigest()), INVALID_STATE);
            var result = decode(row.workspaceSnapshot(), row.payloadDigest(), InvocationResult.class);
            require(result instanceof InvocationResult.Generation, INVALID_STATE);
            var model = ((InvocationResult.Generation) result).value();
            require(model.complete() && model.finishReason() == ModelResult.FinishReason.STOP && !model.outputs().isEmpty()
                    && model.outputs().stream().allMatch(m -> m.toolCalls().isEmpty() && m.toolCallId() == null
                    && !m.content().isEmpty() && m.content().stream().allMatch(ChatMessage.Text.class::isInstance)), INVALID_STATE);
            require(mapper.finishChat(key(next.runId()), child) == 1, INVALID_STATE);
            admission.saveTurn(encode(Turn.typed(turn.turnId(), turn.conversationId(), turn.sequence(), turn.parentTurnId(), turn.supersedesTurnId(),
                    turn.userMessage(), turn.candidates(), candidate, turn.version() + 1, turn.createdAt(), next.updatedAt())), key(turn.turnId()));
            metadata.runAssistantMessage(conversation, invocation, next.updatedAt());
        }
        var updated = new Conversation(conversation.conversationId(), conversation.owner(), conversation.title(), conversation.version() + 1,
                conversation.chatProfile(), conversation.resources(), conversation.state(), conversation.createdAt(),
                Instant.ofEpochMilli(Math.max(now(), conversation.updatedAt().toEpochMilli())));
        admission.advanceTypedConversation(updated.version(), null, "TOOL_CHAT_RUN", encode(updated), key(link.conversationId()));
    }

    @Override
    public Mono<List<ToolChatBudget>> budgets(ExecutionOwner owner, String runId) {
        Objects.requireNonNull(owner);
        id(runId);
        return io(() -> {
            var row = owned(owner, runId, false);
            if (row == null) return List.of();
            var plan = input(row).budgetPlan();
            var values = budgets.balances(runId, false);
            if (plan == null ? !values.isEmpty() : !values.stream().map(ToolChatBudget::limit).toList().equals(plan.limits()))
                throw new IllegalStateException("Run budget projection is missing or differs from fixed plan");
            return values;
        });
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

    @Override
    public Mono<ToolChatRun> accepted(ExecutionOwner owner, String idempotencyKey) {
        Objects.requireNonNull(owner);
        id(idempotencyKey);
        return io(() -> {
            var row = mapper.accepted(ownerKey(owner), key(idempotencyKey));
            return row == null ? null : read(row);
        });
    }

    @Override
    public Mono<StoreOutcome<ToolChatCommands.Lease>> park(ToolChatCommands.Lease lease, Duration delay) {
        Objects.requireNonNull(lease);
        ContractChecks.require(!delay.isNegative() && delay.compareTo(Duration.ofMinutes(1)) <= 0, "Invalid Run park delay");
        return write(() -> {
            var row = owned(lease.owner(), lease.runId(), true);
            require(row != null, NOT_FOUND);
            var work = mapper.work(row.runKey());
            long time = now();
            require(holds(work, lease, time), LEASE_LOST);
            long due = work.signalVersion() > lease.signalVersion() ? time : Math.min(row.deadline(), time + delay.toMillis());
            mapper.updateWork(new ToolChatRunMapper.Work(row.runKey(), due, true, "", 0, work.fence(), work.signalVersion()));
            return StoreOutcome.applied(lease);
        });
    }

    @Override
    public Mono<Boolean> cancellationRequested(ExecutionOwner owner, String runId) {
        return io(() -> owned(owner, runId, false) != null && mapper.receipt(key(runId), ToolChatCommands.CANCEL_MARKER) != null);
    }

    @Override
    public Mono<StoreOutcome<ToolChatRun>> cancel(ExecutionOwner owner, String runId, String commandKey,
                                                  Consumer<ToolChatCommands.Guard> authorization) {
        Objects.requireNonNull(authorization);
        id(commandKey);
        return write(() -> {
            var preview = owned(owner, runId, false);
            require(preview != null, NOT_FOUND);
            // 先取得宿主公共资格锁，再锁根；与子受理的公共资格→子→根顺序一致。
            authorization.accept(new ToolChatCommands.Guard(input(preview), read(preview)));
            var row = owned(owner, runId, true);
            require(row != null, NOT_FOUND);
            var run = read(row);
            authorization.accept(new ToolChatCommands.Guard(input(row), run));
            String key = key("tool-chat-cancel-command-v1", commandKey);
            var previous = mapper.receipt(row.runKey(), key);
            if (previous != null) {
                var receipt = decode(previous.snapshot(), previous.payloadDigest(), ToolChatCommands.Receipt.class);
                require(receipt.run().runId().equals(runId) && receipt.run().owner().equals(owner), OWNER_MISMATCH);
                return StoreOutcome.replayed(receipt.run());
            }
            String text = encode(new ToolChatCommands.Receipt(run, List.of()));
            var receipt = new ToolChatRunMapper.Receipt(key, CanonicalJson.sha256(text), text);
            mapper.insertReceipt(row.runKey(), key, receipt);
            if (!run.state().terminal() && mapper.receipt(row.runKey(), ToolChatCommands.CANCEL_MARKER) == null)
                mapper.insertReceipt(row.runKey(), ToolChatCommands.CANCEL_MARKER, receipt);
            if (!run.state().terminal()) {
                var w = mapper.work(row.runKey());
                mapper.updateWork(new ToolChatRunMapper.Work(row.runKey(), now(), true, w.workerId(), w.leaseUntil(), w.fence(), Math.incrementExact(w.signalVersion())));
            }
            return StoreOutcome.applied(run);
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
            var preview = owned(c.lease().owner(), c.lease().runId(), false);
            require(preview != null, NOT_FOUND);
            authorization.accept(new ToolChatCommands.Guard(input(preview), read(preview)));
            var row = owned(c.lease().owner(), c.lease().runId(), true);
            require(row != null, NOT_FOUND);
            var run = read(row);
            var snapshot = input(row);
            authorization.accept(new ToolChatCommands.Guard(snapshot, run));
            String digest = ToolChatRunDigests.advance(c), commandKey = key(c.commandKey());
            require(!commandKey.equals(ToolChatCommands.CANCEL_MARKER), INVALID_STATE);
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
            require(mapper.receipt(row.runKey(), ToolChatCommands.CANCEL_MARKER) == null
                    || c.state() == ToolChatRun.State.CANCELLED || c.state() == ToolChatRun.State.TIMED_OUT, INVALID_STATE);
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
            completeChat(snapshot, run, next, previousSteps);
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
            notifyRun(next);
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
