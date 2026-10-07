package com.arte.ainew.persistence.mybatis;

import com.arte.ainew.api.execution.BudgetService;
import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.common.execution.ExecutionEvent;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.persistence.mybatis.mapper.*;
import com.arte.ainew.persistence.mybatis.mapper.PersistenceRows.OperationRow;
import com.arte.ainew.pojo.budget.BudgetCommands;
import com.arte.ainew.pojo.budget.BudgetReservation;
import com.arte.ainew.pojo.budget.BudgetSettlement;
import com.arte.ainew.pojo.budget.Money;
import com.arte.ainew.pojo.conversation.Conversation;
import com.arte.ainew.pojo.conversation.ConversationPage;
import com.arte.ainew.pojo.conversation.Turn;
import com.arte.ainew.pojo.execution.*;
import com.arte.ainew.spi.persistence.*;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import static com.arte.ainew.pojo.execution.ExecutionCommands.*;
import static com.arte.ainew.pojo.execution.StoreOutcome.Code.*;

/**
 * 同库权威存储的 MyBatis 实现：所有复合写操作在一个本地事务中，竞争通过数据库行锁仲裁。
 * 没有 JVM 锁／本地余额缓存；多个实例共享数据源可互相 fencing。仅显式构造，调用方管理 Scheduler。
 * 适用 MySQL 8 InnoDB；H2 MySQL 模式用于契约测试。授权、Schema 校验、摘要与外部证据由可信应用层负责。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 23:30 ✾
 */
@Slf4j
public final class MybatisExecutionPersistence implements ExecutionStore, ExecutionEventStore,
        ExecutionOutboxStore, AdmissionCatalogStore, BudgetService {
    private final SystemMapper system;
    private final ExecutionMapper execution;
    private final AdmissionMapper admission;
    private final BudgetMapper budget;
    private final EventMapper event;
    private final OutboxMapper outbox;
    private final PayloadMapper payload;
    private final TransactionTemplate transaction;
    private final ExecutionRecordCodec codec;
    private final Scheduler scheduler;

    private final Runnable eventWakeup;

    public MybatisExecutionPersistence(DataSource dataSource, ExecutionRecordCodec codec, Scheduler scheduler) {
        this(dataSource, codec, scheduler, () -> {
        });
    }

    public MybatisExecutionPersistence(DataSource dataSource, ExecutionRecordCodec codec, Scheduler scheduler, Runnable eventWakeup) {
        this.eventWakeup = Objects.requireNonNull(eventWakeup, "eventWakeup");
        var sessions = new SqlSessionTemplate(ExecutionSqlSessionFactory.create(Objects.requireNonNull(dataSource, "dataSource")));
        this.system = sessions.getMapper(SystemMapper.class);
        this.execution = sessions.getMapper(ExecutionMapper.class);
        this.admission = sessions.getMapper(AdmissionMapper.class);
        this.budget = sessions.getMapper(BudgetMapper.class);
        this.event = sessions.getMapper(EventMapper.class);
        this.outbox = sessions.getMapper(OutboxMapper.class);
        this.payload = sessions.getMapper(PayloadMapper.class);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.transaction.setTimeout(30);
        this.codec = Objects.requireNonNull(codec, "codec");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    private <T> Mono<T> tx(Supplier<T> work) {
        return Mono.fromCallable(() -> {
            if (Schedulers.isInNonBlockingThread()) {
                throw new IllegalStateException("Blocking persistence requires a bounded JDBC worker Scheduler");
            }
            return transaction.execute(status -> work.get());
        }).subscribeOn(scheduler);
    }

    private <T> Mono<StoreOutcome<T>> outcome(Supplier<StoreOutcome<T>> work) {
        return tx(work).onErrorResume(Rejected.class, e -> Mono.just(StoreOutcome.rejected(e.code)));
    }

    private static final class Rejected extends RuntimeException {
        final StoreOutcome.Code code;

        Rejected(StoreOutcome.Code code) {
            super(code.name());
            this.code = code;
        }
    }

    private static void require(boolean valid, StoreOutcome.Code code) {
        if (!valid) {
            throw new Rejected(code);
        }
    }

    private static String hash(String... parts) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var part : parts) {
                var bytes = Objects.requireNonNull(part).getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String ownerKey(ExecutionOwner owner) {
        return hash(owner.tenantId(), owner.workspaceId(), owner.subjectId());
    }

    private Instant now() {
        return Objects.requireNonNull(system.databaseTime())
                .toInstant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    }

    /**
     * INSERT IGNORE 仅用于无业务数据的锁行；所有业务唯一冲突不能忽略。
     */
    private void lock(String key) {
        system.ensureLock(key);
        system.lock(key);
    }

    private <T> T snapshot(String encoded, Class<T> type) {
        return encoded == null ? null : codec.decode(encoded, type);
    }

    private Invocation invocation(Version version) {
        var invocation = snapshot(execution.invocationSnapshot(hash(version.invocationId()), true), Invocation.class);
        require(invocation != null, NOT_FOUND);
        require(ExecutionOwner.from(invocation.request().context()).equals(version.owner()), OWNER_MISMATCH);
        return invocation;
    }

    private void version(Invocation invocation, Version expected) {
        require(invocation.version() == expected.expectedVersion(), VERSION_CONFLICT);
    }

    private Attempt attempt(Invocation invocation, String attemptId, long expectedVersion) {
        var attempt = snapshot(execution.attemptSnapshot(hash(attemptId), true), Attempt.class);
        require(attempt != null && attempt.invocationId().equals(id(invocation)), NOT_FOUND);
        require(attempt.attemptId().equals(invocation.activeAttemptId()), LEASE_LOST);
        require(attempt.version() == expectedVersion, VERSION_CONFLICT);
        return attempt;
    }

    private Attempt guarded(Invocation invocation, Guard guard) {
        version(invocation, guard.invocation());
        var attempt = attempt(invocation, guard.attemptId(), guard.expectedAttemptVersion());
        require(attempt.workerId().equals(guard.workerId()) && attempt.fencingToken() == guard.fencingToken()
                && attempt.leaseExpiresAt().isAfter(now()), LEASE_LOST);
        return attempt;
    }

    private LeasePurpose purpose(Attempt attempt) {
        return LeasePurpose.valueOf(execution.attemptPurpose(hash(attempt.attemptId())));
    }

    private void save(Invocation invocation) {
        execution.saveInvocation(codec.encode(invocation), hash(id(invocation)));
    }

    private void save(Attempt attempt) {
        execution.saveAttempt(codec.encode(attempt), hash(attempt.attemptId()));
    }

    private Invocation state(Invocation source, Invocation.State next, String active, ResultRef result, ExecutionError error) {
        return new Invocation(source.request(), source.requestDigest(), source.conversation(), source.contextSnapshotId(),
                source.replacesInvocationId(), next, source.version() + 1, active, result, error, source.acceptedAt(), now());
    }

    private Attempt changed(Attempt a, Attempt.State state, Attempt.Dispatch dispatch, String remote, String reservation,
                            Usage usage, ExecutionError error, String worker, long fence, Instant lease) {
        return new Attempt(a.attemptId(), a.invocationId(), a.attemptNumber(), worker, fence, lease, a.version() + 1,
                state, dispatch, remote, reservation, usage, error, a.createdAt(), now());
    }

    private Attempt changed(Attempt a, Attempt.State state, Attempt.Dispatch dispatch, String remote, String reservation,
                            Usage usage, ExecutionError error) {
        return changed(a, state, dispatch, remote, reservation, usage, error, a.workerId(), a.fencingToken(), a.leaseExpiresAt());
    }

    @Override
    public Mono<Void> createConversation(Conversation conversation) {
        Objects.requireNonNull(conversation, "conversation");
        return tx(() -> {
            admission.insertConversation(hash(conversation.conversationId()), ownerKey(conversation.owner()), conversation.version(), codec.encode(conversation));
            return true;
        }).then();
    }

    @Override
    public Mono<StoreOutcome<Conversation>> createConversationOnce(Conversation conversation, String idempotencyKey, String requestDigest) {
        Objects.requireNonNull(conversation, "conversation");
        ContractChecks.id(idempotencyKey, "idempotencyKey");
        ContractChecks.digest(requestDigest, "requestDigest");
        ContractChecks.require(conversation.version() == 0 && conversation.state() == Conversation.State.ACTIVE, "Conversation must be pristine");
        return outcome(() -> {
            var owner = ownerKey(conversation.owner());
            var key = hash(idempotencyKey);
            lock(hash("conversation-creation", owner, key));
            var existing = admission.conversationCreation(owner, key);
            if (existing != null) {
                require(existing.requestDigest().equals(requestDigest), IDEMPOTENCY_CONFLICT);
                var original = snapshot(admission.conversationSnapshot(existing.conversationKey(), false), Conversation.class);
                require(original != null && original.owner().equals(conversation.owner()), NOT_FOUND);
                return StoreOutcome.replayed(original);
            }
            admission.insertConversation(hash(conversation.conversationId()), owner, 0, codec.encode(conversation));
            admission.insertConversationCreation(owner, key, requestDigest, hash(conversation.conversationId()));
            return StoreOutcome.applied(conversation);
        });
    }

    @Override
    public Mono<Invocation> findAccepted(ExecutionOwner owner, String capabilityId, String idempotencyKey) {
        Objects.requireNonNull(owner, "owner");
        ContractChecks.id(capabilityId, "capabilityId");
        ContractChecks.id(idempotencyKey, "idempotencyKey");
        return tx(() -> {
            var records = execution.acceptance(hash(ownerKey(owner), capabilityId), hash(idempotencyKey));
            if (records.isEmpty()) { return null; }
            var original = snapshot(execution.invocationSnapshot(records.getFirst().invocationKey(), false), Invocation.class);
            if (original == null || !ExecutionOwner.from(original.request().context()).equals(owner)) {
                throw new IllegalStateException("Broken acceptance record");
            }
            return original;
        });
    }

    @Override
    public Mono<Void> createAccount(BudgetCommands.Account account) {
        Objects.requireNonNull(account, "account");
        ContractChecks.require(account.version() == 0 && account.held().amount().signum() == 0
                && account.charged().amount().signum() == 0, "Account must be pristine");
        return tx(() -> {
            budget.insertAccount(hash(account.budgetRef()), ownerKey(account.owner()), codec.encode(account));
            return true;
        }).then();
    }

    @Override
    public Mono<Conversation> findConversation(ExecutionOwner owner, String conversationId) {
        Objects.requireNonNull(owner, "owner");
        ContractChecks.id(conversationId, "conversationId");
        return tx(() -> {
            var conversation = snapshot(admission.conversationSnapshot(hash(conversationId), false), Conversation.class);
            return conversation != null && conversation.owner().equals(owner) ? conversation : null;
        });
    }

    @Override
    public Mono<ConversationPage<Conversation>> listConversations(ExecutionOwner owner, long current, long size) {
        Objects.requireNonNull(owner, "owner");
        long offset = pageOffset(current, size);
        return tx(() -> {
            var key = ownerKey(owner);
            long total = admission.countConversations(key);
            var records = offset >= total ? List.<Conversation>of() : admission.conversationSnapshots(key, offset, size)
                    .stream().map(json -> codec.decode(json, Conversation.class)).toList();
            if (records.stream().anyMatch(record -> !record.owner().equals(owner))) {
                throw new IllegalStateException("Conversation owner does not match query scope");
            }
            return new ConversationPage<>(current, size, total, records);
        });
    }

    @Override
    public Mono<StoreOutcome<ConversationPage<Turn>>> listTurns(ExecutionOwner owner, String conversationId,
                                                              long expectedVersion, long current, long size) {
        Objects.requireNonNull(owner, "owner");
        ContractChecks.id(conversationId, "conversationId");
        ContractChecks.range(expectedVersion, "expectedVersion", 0, Long.MAX_VALUE);
        long offset = pageOffset(current, size);
        return outcome(() -> {
            var key = hash(conversationId);
            // 与 accept 的会话锁一致；版本检查和本页轮次读取期间不能插入新轮次。
            var conversation = snapshot(admission.conversationSnapshot(key, true), Conversation.class);
            require(conversation != null && conversation.owner().equals(owner), NOT_FOUND);
            require(conversation.version() == expectedVersion, VERSION_CONFLICT);
            long total = admission.countTurns(key);
            var records = offset >= total ? List.<Turn>of() : admission.turnSnapshots(key, offset, size)
                    .stream().map(json -> codec.decode(json, Turn.class)).toList();
            if (records.stream().anyMatch(record -> !record.conversationId().equals(conversationId))) {
                throw new IllegalStateException("Turn does not match conversation");
            }
            return StoreOutcome.applied(new ConversationPage<>(current, size, total, records));
        });
    }

    private static long pageOffset(long current, long size) {
        ContractChecks.range(current, "current", 1, Long.MAX_VALUE);
        ContractChecks.range(size, "size", 1, 100);
        try {
            return Math.multiplyExact(current - 1, size);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Pagination offset exceeds range", overflow);
        }
    }

    @Override
    public Mono<com.arte.ainew.pojo.conversation.Turn> findTurn(ExecutionOwner owner, String conversationId, String turnId) {
        Objects.requireNonNull(owner, "owner");
        ContractChecks.id(conversationId, "conversationId");
        ContractChecks.id(turnId, "turnId");
        return tx(() -> {
            var conversation = snapshot(admission.conversationSnapshot(hash(conversationId), false), Conversation.class);
            if (conversation == null || !conversation.owner().equals(owner)) {
                return null;
            }
            var turn = snapshot(admission.turnSnapshot(hash(turnId), false), com.arte.ainew.pojo.conversation.Turn.class);
            return turn != null && turn.conversationId().equals(conversationId) ? turn : null;
        });
    }

    @Override
    public Mono<StoreOutcome<Invocation>> accept(Accept command) {
        Objects.requireNonNull(command, "command");
        return outcome(() -> {
            var candidate = command.invocation();
            var owner = ExecutionOwner.from(candidate.request().context());
            var capability = candidate.request().capability();
            var scope = hash(ownerKey(owner), capability.id());
            var key = hash(candidate.request().context().idempotencyKey());
            lock(hash("acceptance", scope, key));
            var existing = execution.acceptance(scope, key);
            if (!existing.isEmpty()) {
                require(candidate.requestDigest().equals(existing.getFirst().requestDigest()), IDEMPOTENCY_CONFLICT);
                return StoreOutcome.replayed(snapshot(execution.invocationSnapshot(existing.getFirst().invocationKey(), false), Invocation.class));
            }
            require(candidate.request().options().deadline().isAfter(now()), INVALID_STATE);
            lock(hash("invocation-id", id(candidate)));
            require(snapshot(execution.invocationSnapshot(hash(id(candidate)), false), Invocation.class) == null, IDEMPOTENCY_CONFLICT);
            var link = candidate.conversation();
            if (link != null) {
                var conversation = snapshot(admission.conversationSnapshot(hash(link.conversationId()), true), Conversation.class);
                require(conversation != null, NOT_FOUND);
                require(conversation.owner().equals(owner), OWNER_MISMATCH);
                require(conversation.state() == Conversation.State.ACTIVE, INVALID_STATE);
                var row = admission.conversationGate(hash(link.conversationId()));
                require(row.versionNo() == link.conversationVersion(), VERSION_CONFLICT);
                if (row.activeInvocation() != null) {
                    // 兼容旧版本留下的停止门闩。会话行已锁定，只读旧终态，避免反向领取旧 Invocation 行锁。
                    var stopped = snapshot(execution.invocationSnapshot(row.activeInvocation(), false), Invocation.class);
                    require(stopped != null && ExecutionOwner.from(stopped.request().context()).equals(owner)
                            && stopped.conversation() != null && stopped.conversation().conversationId().equals(link.conversationId())
                            && userStoppedGeneration(stopped), CONVERSATION_BUSY);
                    require(admission.releaseConversation(hash(link.conversationId()), row.activeInvocation()) == 1, CONVERSATION_BUSY);
                }
                var turn = command.turn();
                lock(hash("turn-id", turn.turnId()));
                var oldTurn = snapshot(admission.turnSnapshot(hash(turn.turnId()), true), com.arte.ainew.pojo.conversation.Turn.class);
                if (oldTurn == null) {
                    require(turn.version() == 0 && turn.selectedInvocationId() == null
                            && turn.invocationIds().equals(List.of(id(candidate))), VERSION_CONFLICT);
                    long maximum = admission.maximumTurnSequence(hash(link.conversationId()));
                    require(turn.sequence() == Math.addExact(maximum, 1), VERSION_CONFLICT);
                    require(admission.countTurnSequence(hash(link.conversationId()), turn.sequence()) == 0, VERSION_CONFLICT);
                    admission.insertTurn(hash(turn.turnId()), hash(link.conversationId()), turn.sequence(), codec.encode(turn));
                } else {
                    require(oldTurn.conversationId().equals(turn.conversationId()) && oldTurn.sequence() == turn.sequence()
                            && turn.version() == oldTurn.version() + 1 && oldTurn.userMessage().equals(turn.userMessage())
                            && Objects.equals(oldTurn.parentTurnId(), turn.parentTurnId())
                            && Objects.equals(oldTurn.supersedesTurnId(), turn.supersedesTurnId())
                            && turn.invocationIds().size() == oldTurn.invocationIds().size() + 1
                            && turn.invocationIds().subList(0, oldTurn.invocationIds().size()).equals(oldTurn.invocationIds())
                            && turn.invocationIds().getLast().equals(id(candidate))
                            && turn.createdAt().equals(oldTurn.createdAt())
                            && Objects.equals(turn.selectedInvocationId(), oldTurn.selectedInvocationId()), VERSION_CONFLICT);
                    admission.saveTurn(codec.encode(turn), hash(turn.turnId()));
                }
                var updatedConversation = new Conversation(conversation.conversationId(), conversation.owner(), conversation.title(),
                        Math.addExact(link.conversationVersion(), 1), conversation.chatProfile(), conversation.resources(),
                        conversation.state(), conversation.createdAt(), now());
                admission.advanceConversation(updatedConversation.version(), hash(id(candidate)), codec.encode(updatedConversation), hash(link.conversationId()));
            }
            var acceptedAt = now();
            var accepted = new Invocation(candidate.request(), candidate.requestDigest(), link, candidate.contextSnapshotId(),
                    candidate.replacesInvocationId(), Invocation.State.ACCEPTED, 0, null, null, null, acceptedAt, acceptedAt);
            execution.insertInvocation(hash(id(accepted)), ownerKey(owner), codec.encode(accepted));
            execution.insertAcceptance(scope, key, accepted.requestDigest(), hash(id(accepted)));
            event(accepted, null, new ExecutionPayload.Status(Invocation.State.ACCEPTED));
            outbox(accepted, OutboxMessage.Kind.DISPATCH, 0);
            return StoreOutcome.applied(accepted);
        });
    }

    @Override
    public Mono<Invocation> find(ExecutionOwner owner, String invocationId) {
        Objects.requireNonNull(owner, "owner");
        ContractChecks.id(invocationId, "invocationId");
        return tx(() -> {
            var invocation = snapshot(execution.invocationSnapshot(hash(invocationId), false), Invocation.class);
            return invocation != null && ExecutionOwner.from(invocation.request().context()).equals(owner) ? invocation : null;
        });
    }

    @Override
    public Mono<Attempt> findAttempt(ExecutionOwner owner, String invocationId, String attemptId) {
        Objects.requireNonNull(owner, "owner");
        ContractChecks.id(invocationId, "invocationId");
        ContractChecks.id(attemptId, "attemptId");
        return tx(() -> {
            var invocation = snapshot(execution.invocationSnapshot(hash(invocationId), false), Invocation.class);
            if (invocation == null || !ExecutionOwner.from(invocation.request().context()).equals(owner)) {
                return null;
            }
            var attempt = snapshot(execution.attemptSnapshot(hash(attemptId), false), Attempt.class);
            return attempt != null && attempt.invocationId().equals(invocationId) ? attempt : null;
        });
    }

    @Override
    public Mono<StoreOutcome<Attempt>> createAttempt(CreateAttempt command) {
        return outcome(() -> {
            var invocation = invocation(command.invocation());
            version(invocation, command.invocation());
            require(!invocation.state().terminal() && invocation.request().options().deadline().isAfter(now()), INVALID_STATE);
            if (invocation.activeAttemptId() != null) {
                var previous = snapshot(execution.attemptSnapshot(hash(invocation.activeAttemptId()), true), Attempt.class);
                require(previous.state() == Attempt.State.FAILED || previous.state() == Attempt.State.TIMED_OUT
                        || previous.state() == Attempt.State.INTERRUPTED, INVALID_STATE);
                require(previous.error().retryable() && previous.error().certainty() == ExecutionError.Certainty.KNOWN
                        && (previous.dispatch() == Attempt.Dispatch.NOT_STARTED
                        || previous.error().sideEffect() == ExecutionError.SideEffect.NONE), RECONCILIATION_REQUIRED);
            }
            lock(hash("attempt-id", command.attemptId()));
            require(snapshot(execution.attemptSnapshot(hash(command.attemptId()), false), Attempt.class) == null, IDEMPOTENCY_CONFLICT);
            var counters = execution.attemptCounters(hash(id(invocation)));
            int number = counters.nextAttempt() + 1;
            require(number <= invocation.request().options().maxAttempts(), INVALID_STATE);
            long fence = Math.addExact(counters.nextFence(), 1);
            var createdAt = now();
            var attempt = new Attempt(command.attemptId(), id(invocation), number, command.workerId(), fence,
                    createdAt.plus(command.lease()), 0, Attempt.State.CREATED, Attempt.Dispatch.NOT_STARTED, null, null,
                    Usage.unknown(), null, createdAt, createdAt);
            execution.insertAttempt(hash(attempt.attemptId()), hash(id(invocation)), number, LeasePurpose.EXECUTE.name(), codec.encode(attempt));
            execution.advanceAttemptCounters(number, fence, hash(id(invocation)));
            var running = state(invocation, Invocation.State.RUNNING, attempt.attemptId(), null, null);
            save(running);
            event(running, attempt.attemptId(), new ExecutionPayload.Status(Invocation.State.RUNNING));
            return StoreOutcome.applied(attempt);
        });
    }

    @Override
    public Mono<StoreOutcome<Attempt>> acquireLease(AcquireLease command) {
        return outcome(() -> {
            var invocation = invocation(command.invocation());
            version(invocation, command.invocation());
            var attempt = attempt(invocation, command.attemptId(), command.expectedAttemptVersion());
            require(!attempt.leaseExpiresAt().isAfter(now()), LEASE_LOST);
            require(!invocation.state().terminal() || invocation.state() == Invocation.State.UNKNOWN, INVALID_STATE);
            if (command.purpose() == LeasePurpose.EXECUTE) {
                require(attempt.dispatch() == Attempt.Dispatch.NOT_STARTED, RECONCILIATION_REQUIRED);
                require(attempt.state() == Attempt.State.CREATED || attempt.state() == Attempt.State.RUNNING, INVALID_STATE);
                require(invocation.request().options().deadline().isAfter(now()), INVALID_STATE);
            } else {
                require(attempt.dispatch() != Attempt.Dispatch.NOT_STARTED, INVALID_STATE);
            }
            long fence = Math.addExact(execution.nextFence(hash(id(invocation))), 1);
            var claimed = changed(attempt, attempt.state(), attempt.dispatch(), attempt.remoteRequestId(),
                    attempt.budgetReservationId(), attempt.usage(), attempt.error(), command.workerId(), fence, now().plus(command.lease()));
            save(claimed);
            execution.savePurpose(command.purpose().name(), hash(attempt.attemptId()));
            execution.saveNextFence(fence, hash(id(invocation)));
            return StoreOutcome.applied(claimed);
        });
    }

    @Override
    public Mono<StoreOutcome<Attempt>> renewLease(Guard guard, Duration lease) {
        validLease(lease);
        return outcome(() -> {
            var invocation = invocation(guard.invocation());
            var attempt = guarded(invocation, guard);
            require(!invocation.state().terminal() || invocation.state() == Invocation.State.UNKNOWN, INVALID_STATE);
            var renewed = changed(attempt, attempt.state(), attempt.dispatch(), attempt.remoteRequestId(), attempt.budgetReservationId(),
                    attempt.usage(), attempt.error(), attempt.workerId(), attempt.fencingToken(), now().plus(lease));
            save(renewed);
            return StoreOutcome.applied(renewed);
        });
    }

    @Override
    public Mono<StoreOutcome<Attempt>> markDispatch(Dispatch command) {
        return outcome(() -> {
            var invocation = invocation(command.guard().invocation());
            var attempt = guarded(invocation, command.guard());
            require(operation(invocation, CANCEL_REQUEST_KEY) == null, INVALID_STATE);
            require(!invocation.state().terminal() && invocation.request().options().deadline().isAfter(now()), INVALID_STATE);
            require(purpose(attempt) == LeasePurpose.EXECUTE && attempt.dispatch() == Attempt.Dispatch.NOT_STARTED
                    && (attempt.state() == Attempt.State.CREATED || attempt.state() == Attempt.State.RUNNING), INVALID_STATE);
            if (invocation.request().context().budgetRef() != null) {
                require(attempt.budgetReservationId() != null, INSUFFICIENT_BUDGET);
                var reservation = snapshot(budget.reservationSnapshot(hash(attempt.budgetReservationId()), true), BudgetReservation.class);
                require(reservation != null && reservation.state() == BudgetReservation.State.RESERVED
                        && reservation.expiresAt().isAfter(now()), INSUFFICIENT_BUDGET);
            }
            var dispatched = changed(attempt, Attempt.State.RUNNING, Attempt.Dispatch.MAY_HAVE_EXECUTED,
                    command.remoteRequestId(), attempt.budgetReservationId(), attempt.usage(), null);
            save(dispatched);
            return StoreOutcome.applied(dispatched);
        });
    }

    private static final String CANCEL_REQUEST_KEY = hash("control-cancel-requested", "v1");

    private boolean userStoppedGeneration(Invocation invocation) {
        return invocation.userStoppedGeneration() && operation(invocation, CANCEL_REQUEST_KEY) != null;
    }

    @Override
    public Mono<Boolean> cancellationRequested(ExecutionOwner owner, String invocationId) {
        return find(owner, invocationId).flatMap(value -> tx(() -> operation(value, CANCEL_REQUEST_KEY) != null))
                .defaultIfEmpty(false);
    }

    @Override
    public Mono<StoreOutcome<ControlReceipt>> requestControl(ExecutionOwner owner, ExecutionControlRequest request) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(request, "request");
        return outcome(() -> {
            var invocation = snapshot(execution.invocationSnapshot(hash(request.invocationId()), true), Invocation.class);
            // 不存在和不属于当前主体均返回 NOT_FOUND，不泄露其他主体的执行记录。
            require(invocation != null && ExecutionOwner.from(invocation.request().context()).equals(owner), NOT_FOUND);
            var key = hash("control", request.command().name(), request.commandKey());
            var digest = hash(ownerKey(owner), id(invocation), request.command().name());
            var previous = operation(invocation, key);
            if (previous != null) {
                require(previous.digest().equals(digest), IDEMPOTENCY_CONFLICT);
                return StoreOutcome.replayed(codec.decode(previous.resultSnapshot(), ControlReceipt.class));
            }
            var result = request.command() != ControlReceipt.Command.CANCEL ? ControlReceipt.Outcome.UNSUPPORTED
                    : invocation.state().terminal() ? ControlReceipt.Outcome.ALREADY_TERMINAL : ControlReceipt.Outcome.ACCEPTED;
            var receipt = new ControlReceipt(request.commandId(), id(invocation), request.command(), result, now());
            var control = event(invocation, invocation.activeAttemptId(), new ExecutionPayload.Control(receipt));
            execution.insertCompletion(hash(id(invocation)), key, digest, control.sequence(), 1, codec.encode(receipt));
            if (result == ControlReceipt.Outcome.ACCEPTED && operation(invocation, CANCEL_REQUEST_KEY) == null) {
                // 固定标记不随 CONTROL 事件裁剪消失；其他实例和重启后的 Worker 均可读取。
                execution.insertCompletion(hash(id(invocation)), CANCEL_REQUEST_KEY, digest, control.sequence(), 1, codec.encode(receipt));
                var attempt = invocation.activeAttemptId() == null ? null
                        : snapshot(execution.attemptSnapshot(hash(invocation.activeAttemptId()), true), Attempt.class);
                if (attempt == null || attempt.dispatch() == Attempt.Dispatch.NOT_STARTED) {
                    var error = new ExecutionError("INVOCATION_CANCELLED", ExecutionError.Phase.INVOCATION, false,
                            ExecutionError.SideEffect.NONE, ExecutionError.Certainty.KNOWN, invocation.request().context().traceId());
                    if (attempt != null) {
                        long fence = Math.addExact(execution.nextFence(hash(id(invocation))), 1);
                        save(changed(attempt, Attempt.State.CANCELLED, attempt.dispatch(), attempt.remoteRequestId(),
                                attempt.budgetReservationId(), attempt.usage(), error, attempt.workerId(), fence, attempt.leaseExpiresAt()));
                        execution.saveNextFence(fence, hash(id(invocation)));
                    }
                    var cancelled = state(invocation, Invocation.State.CANCELLED, invocation.activeAttemptId(), null, error);
                    save(cancelled);
                    event(cancelled, cancelled.activeAttemptId(), new ExecutionPayload.Terminal(cancelled.state(), null, error));
                    if (cancelled.conversation() != null) {
                        admission.releaseConversation(hash(cancelled.conversation().conversationId()), hash(id(cancelled)));
                    }
                    // 原 DISPATCH Outbox 负责恢复预算释放，不在控制事务中调用外部服务。
                }
            }
            return StoreOutcome.applied(receipt);
        });
    }

    @Override
    public Mono<StoreOutcome<Invocation>> stopExpired(Version target) {
        return outcome(() -> {
            var invocation = invocation(target);
            version(invocation, target);
            require(!invocation.state().terminal() && invocation.activeAttemptId() != null, INVALID_STATE);
            var attempt = snapshot(execution.attemptSnapshot(hash(invocation.activeAttemptId()), true), Attempt.class);
            require(!attempt.leaseExpiresAt().isAfter(now()), LEASE_LOST);
            boolean uncertain = attempt.dispatch() != Attempt.Dispatch.NOT_STARTED;
            var cancelled = operation(invocation, CANCEL_REQUEST_KEY) != null;
            var error = new ExecutionError(cancelled ? "INVOCATION_CANCELLED" : "WORKER_LEASE_EXPIRED", ExecutionError.Phase.DISPATCH, false,
                    uncertain ? ExecutionError.SideEffect.POSSIBLE : ExecutionError.SideEffect.NONE,
                    uncertain ? ExecutionError.Certainty.UNKNOWN : ExecutionError.Certainty.KNOWN,
                    invocation.request().context().traceId());
            var terminal = new ExecutionPayload.Terminal(uncertain ? Invocation.State.UNKNOWN : cancelled ? Invocation.State.CANCELLED : Invocation.State.INTERRUPTED, null, error);
            long fence = Math.addExact(execution.nextFence(hash(id(invocation))), 1);
            save(changed(attempt, Attempt.State.valueOf(terminal.state().name()), attempt.dispatch(), attempt.remoteRequestId(),
                    attempt.budgetReservationId(), attempt.usage(), error, attempt.workerId(), fence, attempt.leaseExpiresAt()));
            execution.saveNextFence(fence, hash(id(invocation)));
            var completed = state(invocation, terminal.state(), attempt.attemptId(), null, error);
            save(completed);
            event(completed, attempt.attemptId(), terminal);
            if (completed.conversation() != null && (!uncertain || cancelled && completed.userStoppedGeneration())) {
                admission.releaseConversation(hash(completed.conversation().conversationId()), hash(id(completed)));
            }
            return StoreOutcome.applied(completed);
        });
    }

    @Override
    public Mono<StoreOutcome<Attempt>> updateConditionally(FailAttempt command) {
        return outcome(() -> {
            var invocation = invocation(command.guard().invocation());
            var attempt = guarded(invocation, command.guard());
            require(!invocation.state().terminal() && purpose(attempt) == LeasePurpose.EXECUTE
                    && (attempt.state() == Attempt.State.CREATED || attempt.state() == Attempt.State.RUNNING), INVALID_STATE);
            require(attempt.dispatch() == Attempt.Dispatch.NOT_STARTED || command.error().sideEffect() == ExecutionError.SideEffect.NONE,
                    RECONCILIATION_REQUIRED);
            var failed = changed(attempt, command.state(), attempt.dispatch(), attempt.remoteRequestId(), attempt.budgetReservationId(),
                    command.usage(), command.error());
            save(failed);
            return StoreOutcome.applied(failed);
        });
    }

    private String operationKey(String kind, String key) {
        return hash(kind, key);
    }

    private OperationRow operation(Invocation invocation, String key) {
        var rows = execution.operation(hash(id(invocation)), key);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Override
    public Mono<StoreOutcome<Invocation>> commitCompletion(CompleteBeforeAttempt command) {
        return outcome(() -> {
            var invocation = invocation(command.invocation());
            var key = operationKey("completion", command.completionKey());
            var digest = hash("before-attempt", command.terminal().toString());
            var previous = operation(invocation, key);
            if (previous != null) {
                require(digest.equals(previous.digest()), IDEMPOTENCY_CONFLICT);
                return StoreOutcome.replayed(codec.decode(previous.resultSnapshot(), Invocation.class));
            }
            version(invocation, command.invocation());
            require(invocation.activeAttemptId() == null && invocation.state().canTransitionTo(command.terminal().state()), INVALID_STATE);
            var completed = state(invocation, command.terminal().state(), null, null, command.terminal().error());
            save(completed);
            var terminal = event(completed, null, command.terminal());
            execution.insertCompletion(hash(id(invocation)), key, digest, terminal.sequence(), 1, codec.encode(completed));
            if (completed.conversation() != null) {
                admission.releaseConversation(hash(completed.conversation().conversationId()), hash(id(completed)));
            }
            return StoreOutcome.applied(completed);
        });
    }

    @Override
    public Mono<StoreOutcome<Invocation>> commitCompletion(Complete command) {
        return outcome(() -> {
            var invocation = invocation(command.guard().invocation());
            var key = operationKey("completion", command.completionKey());
            String digest = hash(command.guard().attemptId(), command.terminal().toString(), command.usage().toString(),
                    Objects.toString(command.evidenceRef(), ""));
            var previous = operation(invocation, key);
            if (previous != null) {
                require(digest.equals(previous.digest()), IDEMPOTENCY_CONFLICT);
                return StoreOutcome.replayed(codec.decode(previous.resultSnapshot(), Invocation.class));
            }
            var attempt = guarded(invocation, command.guard());
            require(invocation.state().canTransitionTo(command.terminal().state()), INVALID_STATE);
            boolean reconciliation = purpose(attempt) == LeasePurpose.RECONCILE;
            require(!reconciliation || command.evidenceRef() != null && command.terminal().state() != Invocation.State.UNKNOWN, INVALID_STATE);
            require(invocation.state() != Invocation.State.UNKNOWN || reconciliation, RECONCILIATION_REQUIRED);
            require(command.terminal().state() != Invocation.State.SUCCEEDED || attempt.dispatch() != Attempt.Dispatch.NOT_STARTED
                    && (reconciliation || attempt.state() == Attempt.State.RUNNING), INVALID_STATE);
            require(command.terminal().state() != Invocation.State.UNKNOWN || attempt.dispatch() != Attempt.Dispatch.NOT_STARTED,
                    INVALID_STATE);
            // 已校验并保存的模型终止响应可证明输出不完整；其他可能执行的失败仍需 NONE 或远端核对。
            if (!reconciliation && attempt.dispatch() != Attempt.Dispatch.NOT_STARTED
                    && command.terminal().state() != Invocation.State.SUCCEEDED && command.terminal().state() != Invocation.State.UNKNOWN) {
                var terminal = command.terminal();
                boolean verifiedOutput = terminal.state() == Invocation.State.FAILED && terminal.result() != null
                        && terminal.result().partial() && terminal.result().resultType().equals("model-result") && terminal.error() != null
                        && terminal.error().code().equals("MODEL_OUTPUT_INCOMPLETE")
                        && terminal.error().phase() == ExecutionError.Phase.OUTPUT
                        && terminal.error().certainty() == ExecutionError.Certainty.KNOWN
                        && terminal.error().sideEffect() == ExecutionError.SideEffect.CONFIRMED
                        && ("model-result:" + terminal.result().resultId()).equals(command.evidenceRef());
                if (verifiedOutput) {
                    var ref = terminal.result();
                    var row = payload.result(ref.resultId());
                    verifiedOutput = row != null && row.partial() == 1 && row.resultType().equals("model-result")
                            && row.ownerKey().equals(ownerKey(command.guard().invocation().owner()))
                            && row.invocationKey().equals(hash(id(invocation))) && row.attemptKey().equals(hash(attempt.attemptId()))
                            && row.schemaVersion() == ref.schemaVersion() && row.payloadDigest().equals(ref.contentDigest())
                            && row.payloadDigest().equals(com.arte.ainew.serialization.CanonicalJson.sha256(row.snapshot()));
                    if (verifiedOutput) {
                        var value = codec.decode(row.snapshot(), InvocationResult.class);
                        verifiedOutput = value instanceof InvocationResult.Generation generation && !generation.value().complete()
                                && generation.value().usage().equals(command.usage());
                    }
                }
                require(terminal.error() != null && terminal.error().sideEffect() == ExecutionError.SideEffect.NONE
                        || verifiedOutput, RECONCILIATION_REQUIRED);
            }
            var completed = state(invocation, command.terminal().state(), attempt.attemptId(), command.terminal().result(), command.terminal().error());
            var attemptState = Attempt.State.valueOf(completed.state().name());
            var dispatch = completed.state() == Invocation.State.SUCCEEDED ? Attempt.Dispatch.CONFIRMED : attempt.dispatch();
            save(changed(attempt, attemptState, dispatch, attempt.remoteRequestId(), attempt.budgetReservationId(),
                    command.usage(), completed.error()));
            save(completed);
            var terminal = event(completed, attempt.attemptId(), command.terminal());
            execution.insertVerifiedCompletion(hash(id(invocation)), key, digest, terminal.sequence(), 1, codec.encode(completed), command.evidenceRef());
            if (completed.conversation() != null && (completed.state() != Invocation.State.UNKNOWN || userStoppedGeneration(completed))) {
                admission.releaseConversation(hash(completed.conversation().conversationId()), hash(id(completed)));
            }
            // 费用结算有独立证据与去重键；执行结束不能隐式释放预算。
            return StoreOutcome.applied(completed);
        });
    }

    private ExecutionEvent<?> event(Invocation invocation, String attemptId, ExecutionPayload payload) {
        long sequence = Math.addExact(event.nextSequence(hash(id(invocation))), 1);
        String type = switch (payload) {
            case ExecutionPayload.OutputBatch ignored -> "output-batch";
            case ExecutionPayload.Status ignored -> "status";
            case ExecutionPayload.Control ignored -> "control";
            case ExecutionPayload.Terminal ignored -> "terminal";
            case ExecutionPayload.BudgetChanged ignored -> "budget-changed";
        };
        var event = new ExecutionEvent<>(1, id(invocation), attemptId, sequence, payload.eventKind(), now(), type, 1, payload);
        persistEvent(invocation, event, codec.encode(event));
        return event;
    }

    private void persistEvent(Invocation invocation, ExecutionEvent<?> event, String encoded) {
        this.event.insertEvent(hash(id(invocation)), event.sequence(), encoded);
        this.event.saveNextSequence(event.sequence(), hash(id(invocation)));
        outbox(invocation, OutboxMessage.Kind.EVENT, event.sequence());
    }

    private void outbox(Invocation invocation, OutboxMessage.Kind kind, long sequence) {
        var key = hash(id(invocation), kind.name(), Long.toString(sequence));
        var owner = ExecutionOwner.from(invocation.request().context());
        outbox.insertMessage(key, hash(id(invocation)), id(invocation), owner.tenantId(), owner.workspaceId(), owner.subjectId(), kind.name(), sequence);
        if (kind == OutboxMessage.Kind.EVENT) {
            // 回滚不通知。唤醒失败不改变已提交的业务结果，由统一 Outbox 恢复扫描兜底。
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            try {
                                eventWakeup.run();
                            } catch (RuntimeException error) {
                                log.warn("AI event wakeup failed; outbox scan will recover, invocationId={}, sequence={}, type={}",
                                        id(invocation), sequence, error.getClass().getName());
                            }
                        }
                    });
        }
    }

    @Override
    public Mono<StoreOutcome<List<ExecutionEvent<?>>>> appendBatch(Append command) {
        return outcome(() -> {
            var invocation = invocation(command.guard().invocation());
            var key = operationKey("append", command.batchKey());
            var fingerprint = new ArrayList<String>();
            fingerprint.add(command.guard().attemptId());
            for (var batch : command.batches()) {
                fingerprint.add(codec.encode(new ExecutionEvent<>(1, id(invocation), command.guard().attemptId(), 1,
                        ExecutionEvent.Kind.OUTPUT, Instant.EPOCH, "output-batch", 1, batch)));
            }
            var digest = hash(fingerprint.toArray(String[]::new));
            var previous = operation(invocation, key);
            if (previous != null) {
                require(digest.equals(previous.digest()), IDEMPOTENCY_CONFLICT);
                long first = previous.firstSequence();
                int count = previous.eventCount();
                var values = events(invocation, first - 1, count);
                require(values.size() == count && values.getFirst().sequence() == first, CURSOR_EXPIRED);
                return StoreOutcome.replayed(values);
            }
            var attempt = guarded(invocation, command.guard());
            require(!invocation.state().terminal() && purpose(attempt) == LeasePurpose.EXECUTE
                    && attempt.state() == Attempt.State.RUNNING && attempt.dispatch() != Attempt.Dispatch.NOT_STARTED, INVALID_STATE);
            long sequence = event.nextSequence(hash(id(invocation)));
            var saved = new ArrayList<ExecutionEvent<?>>();
            var encoded = new ArrayList<String>();
            for (var batch : command.batches()) {
                sequence = Math.addExact(sequence, 1);
                var event = new ExecutionEvent<>(1, id(invocation), attempt.attemptId(), sequence, ExecutionEvent.Kind.OUTPUT,
                        now(), "output-batch", 1, batch);
                saved.add(event);
                encoded.add(codec.encode(event));
            }
            long bytes = encoded.stream().mapToLong(v -> v.getBytes(StandardCharsets.UTF_8).length).sum();
            long written = event.writtenBytes(hash(id(invocation)));
            require(bytes <= invocation.request().options().maxOutputBytes() - written, INVALID_STATE);
            for (int i = 0; i < saved.size(); i++) {
                persistEvent(invocation, saved.get(i), encoded.get(i));
            }
            event.addWrittenBytes(bytes, hash(id(invocation)));
            execution.insertAppend(hash(id(invocation)), key, digest, saved.getFirst().sequence(), saved.size());
            return StoreOutcome.applied(List.copyOf(saved));
        });
    }

    private List<ExecutionEvent<?>> events(Invocation invocation, long after, int limit) {
        return event.events(hash(id(invocation)), after, limit).stream().<ExecutionEvent<?>>map(value -> codec.decode(value, ExecutionEvent.class)).toList();
    }

    @Override
    public Mono<StoreOutcome<ExecutionEventStore.Page>> replay(ExecutionOwner owner, ExecutionEvent.Cursor cursor, int limit) {
        ContractChecks.range(limit, "limit", 1, 256);
        return outcome(() -> {
            var invocation = invocation(new Version(owner, cursor.executionId(), 0));
            long retained = event.retainedAfter(hash(id(invocation)));
            require(cursor.afterSequence() >= retained, CURSOR_EXPIRED);
            var events = events(invocation, cursor.afterSequence(), limit);
            long next = events.isEmpty() ? cursor.afterSequence() : events.getLast().sequence();
            return StoreOutcome.applied(new ExecutionEventStore.Page(events, new ExecutionEvent.Cursor(id(invocation), next), retained));
        });
    }

    @Override
    public Mono<List<OutboxMessage>> claim(OutboxMessage.Kind kind, String workerId, Duration lease, int limit) {
        Objects.requireNonNull(kind, "kind");
        ContractChecks.id(workerId, "workerId");
        validLease(lease);
        ContractChecks.range(limit, "limit", 1, 256);
        return tx(() -> {
            lock(hash("outbox", kind.name()));
            long current = now().toEpochMilli();
            long expiry = current + lease.toMillis();
            var messages = outbox.pendingMessages(kind.name(), current, limit).stream().map(row -> new OutboxMessage(row.messageKey(), row.invocationId(),
                    new ExecutionOwner(row.ownerTenant(), row.ownerWorkspace(), row.ownerSubject()), kind, row.sequenceNo(),
                    workerId, Math.addExact(row.token(), 1), Instant.ofEpochMilli(expiry))).toList();
            for (var message : messages) {
                outbox.claimMessage(workerId, message.fencingToken(), expiry, message.messageId());
            }
            return List.copyOf(messages);
        });
    }

    @Override
    public Mono<StoreOutcome<OutboxMessage>> acknowledge(OutboxMessage message) {
        Objects.requireNonNull(message, "message");
        return outcome(() -> {
            var rows = outbox.lockMessage(message.messageId());
            require(!rows.isEmpty(), NOT_FOUND);
            var row = rows.getFirst();
            require(message.workerId().equals(row.workerId())
                    && message.fencingToken() == row.token()
                    && hash(message.invocationId()).equals(row.invocationKey())
                    && message.owner().equals(new ExecutionOwner(row.ownerTenant(), row.ownerWorkspace(),
                    row.ownerSubject()))
                    && message.kind().name().equals(row.kind())
                    && message.eventSequence() == row.sequenceNo(), LEASE_LOST);
            if (row.delivered() == 1) {
                return StoreOutcome.replayed(message);
            }
            require(row.leaseUntil() > now().toEpochMilli(), LEASE_LOST);
            outbox.deliverMessage(message.messageId());
            return StoreOutcome.applied(message);
        });
    }

    private void claimed(OutboxMessage message, com.arte.ainew.persistence.mybatis.mapper.PersistenceRows.OutboxRow row) {
        require(message.workerId().equals(row.workerId()) && message.fencingToken() == row.token()
                && hash(message.invocationId()).equals(row.invocationKey())
                && message.owner().equals(new ExecutionOwner(row.ownerTenant(), row.ownerWorkspace(), row.ownerSubject()))
                && message.kind().name().equals(row.kind()) && message.eventSequence() == row.sequenceNo()
                && row.delivered() == 0 && row.leaseUntil() > now().toEpochMilli(), LEASE_LOST);
    }

    @Override
    public Mono<StoreOutcome<OutboxMessage>> validateClaim(OutboxMessage message) {
        Objects.requireNonNull(message, "message");
        return outcome(() -> {
            var rows = outbox.lockMessage(message.messageId());
            require(!rows.isEmpty(), NOT_FOUND);
            claimed(message, rows.getFirst());
            return StoreOutcome.applied(message);
        });
    }

    @Override
    public Mono<StoreOutcome<OutboxMessage>> renewClaim(OutboxMessage message, Duration lease) {
        Objects.requireNonNull(message, "message");
        validLease(lease);
        return outcome(() -> {
            var rows = outbox.lockMessage(message.messageId());
            require(!rows.isEmpty(), NOT_FOUND);
            claimed(message, rows.getFirst());
            var expiry = now().plus(lease);
            outbox.claimMessage(message.workerId(), message.fencingToken(), expiry.toEpochMilli(), message.messageId());
            return StoreOutcome.applied(new OutboxMessage(message.messageId(), message.invocationId(), message.owner(), message.kind(),
                    message.eventSequence(), message.workerId(), message.fencingToken(), expiry));
        });
    }

    @Override
    public Mono<BudgetCommands.Account> account(ExecutionOwner owner, String budgetRef) {
        Objects.requireNonNull(owner, "owner");
        ContractChecks.id(budgetRef, "budgetRef");
        return tx(() -> {
            var account = snapshot(budget.accountSnapshot(hash(budgetRef), false), BudgetCommands.Account.class);
            return account != null && account.owner().equals(owner) ? account : null;
        });
    }

    @Override
    public Mono<BudgetReservation> reservation(ExecutionOwner owner, String reservationId) {
        Objects.requireNonNull(owner, "owner");
        ContractChecks.id(reservationId, "reservationId");
        return tx(() -> {
            var reservation = snapshot(budget.reservationSnapshot(hash(reservationId), false), BudgetReservation.class);
            if (reservation == null) {
                return null;
            }
            var invocation = snapshot(execution.invocationSnapshot(hash(reservation.invocationId()), false), Invocation.class);
            return invocation != null && ExecutionOwner.from(invocation.request().context()).equals(owner) ? reservation : null;
        });
    }

    private void save(BudgetCommands.Account account) {
        budget.saveAccount(codec.encode(account), hash(account.budgetRef()));
    }

    private void save(BudgetReservation reservation) {
        budget.saveReservation(codec.encode(reservation), hash(reservation.reservationId()));
    }

    @Override
    public Mono<StoreOutcome<BudgetReservation>> reserve(BudgetCommands.Reserve command) {
        return outcome(() -> {
            var invocation = invocation(command.guard().invocation());
            var budgetRef = invocation.request().context().budgetRef();
            require(budgetRef != null, NOT_FOUND);
            var account = snapshot(budget.accountSnapshot(hash(budgetRef), true), BudgetCommands.Account.class);
            require(account != null, NOT_FOUND);
            require(account.owner().equals(command.guard().invocation().owner()), OWNER_MISMATCH);
            var duplicates = budget.reservationForAttempt(hash(command.guard().attemptId()), hash(id(invocation))).stream().map(value -> codec.decode(value, BudgetReservation.class)).toList();
            if (!duplicates.isEmpty()) {
                var previous = duplicates.getFirst();
                require(previous.reservationId().equals(command.reservationId()) && previous.reserved().equals(command.amount())
                        && previous.rateVersion().equals(command.rateVersion())
                        && Duration.between(previous.reservedAt(), previous.expiresAt()).equals(command.retention()), IDEMPOTENCY_CONFLICT);
                return StoreOutcome.replayed(previous);
            }
            var attempt = guarded(invocation, command.guard());
            require(!invocation.state().terminal() && purpose(attempt) == LeasePurpose.EXECUTE
                    && attempt.dispatch() == Attempt.Dispatch.NOT_STARTED
                    && (attempt.state() == Attempt.State.CREATED || attempt.state() == Attempt.State.RUNNING)
                    && invocation.request().options().deadline().isAfter(now()), INVALID_STATE);
            require(account.limit().currency().equals(command.amount().currency()), CURRENCY_MISMATCH);
            require(account.rateVersion().equals(command.rateVersion()), RATE_MISMATCH);
            require(account.available().compareTo(command.amount().amount()) >= 0, INSUFFICIENT_BUDGET);
            lock(hash("reservation-id", command.reservationId()));
            require(snapshot(budget.reservationSnapshot(hash(command.reservationId()), false), BudgetReservation.class) == null,
                    IDEMPOTENCY_CONFLICT);
            var reservedAt = now();
            var reservation = new BudgetReservation(command.reservationId(), budgetRef, id(invocation), attempt.attemptId(),
                    command.amount(), command.rateVersion(), BudgetReservation.State.RESERVED, 0, reservedAt,
                    reservedAt.plus(command.retention()));
            budget.insertReservation(hash(reservation.reservationId()), hash(budgetRef), hash(id(invocation)), hash(attempt.attemptId()), codec.encode(reservation));
            save(new BudgetCommands.Account(account.budgetRef(), account.owner(), account.limit(),
                    new Money(account.held().amount().add(command.amount().amount()), account.held().currency()),
                    account.charged(), account.rateVersion(), account.version() + 1));
            save(changed(attempt, attempt.state(), attempt.dispatch(), attempt.remoteRequestId(), reservation.reservationId(),
                    attempt.usage(), attempt.error()));
            event(invocation, attempt.attemptId(), new ExecutionPayload.BudgetChanged(InvocationBudgetState.RESERVED,
                    reservation.version(), account.version() + 1));
            return StoreOutcome.applied(reservation);
        });
    }

    @Override
    public Mono<StoreOutcome<BudgetReservation>> settle(BudgetCommands.Settle command) {
        return outcome(() -> {
            var settlement = command.settlement();
            // 先只读定位，再按统一顺序锁 Invocation -> Account -> Reservation，避免与预留／发送互锁。
            var pointer = snapshot(budget.reservationSnapshot(hash(settlement.reservationId()), false), BudgetReservation.class);
            require(pointer != null, NOT_FOUND);
            var invocation = invocation(new Version(command.owner(), pointer.invocationId(), 0));
            var account = snapshot(budget.accountSnapshot(hash(pointer.budgetRef()), true), BudgetCommands.Account.class);
            require(account != null && account.owner().equals(command.owner()), OWNER_MISMATCH);
            var reservation = snapshot(budget.reservationSnapshot(hash(pointer.reservationId()), true), BudgetReservation.class);
            var key = hash(settlement.settlementKey());
            var digest = hash(codec.encode(settlement), command.evidence().name(), Objects.toString(command.evidenceRef(), ""));
            var previous = budget.settlement(hash(reservation.reservationId()), key);
            if (!previous.isEmpty()) {
                require(digest.equals(previous.getFirst().digest()), IDEMPOTENCY_CONFLICT);
                return StoreOutcome.replayed(codec.decode(previous.getFirst().resultSnapshot(), BudgetReservation.class));
            }
            require(reservation.version() == command.expectedReservationVersion(), VERSION_CONFLICT);
            require(reservation.state() == BudgetReservation.State.RESERVED
                    || reservation.state() == BudgetReservation.State.PENDING_RECONCILIATION, INVALID_STATE);
            require(settlement.charge() == null || settlement.charge().currency().equals(reservation.reserved().currency()), CURRENCY_MISMATCH);
            if (command.evidence() == BudgetCommands.Evidence.PROVEN_NOT_DISPATCHED) {
                var attempt = snapshot(execution.attemptSnapshot(hash(reservation.attemptId()), true), Attempt.class);
                require(attempt != null && attempt.dispatch() == Attempt.Dispatch.NOT_STARTED, RECONCILIATION_REQUIRED);
            }
            boolean finalSettlement = settlement.state() != BudgetSettlement.State.PENDING_RECONCILIATION;
            if (finalSettlement) {
                require(account.held().amount().compareTo(reservation.reserved().amount()) >= 0, INVALID_STATE);
                save(new BudgetCommands.Account(account.budgetRef(), account.owner(), account.limit(),
                        new Money(account.held().amount().subtract(reservation.reserved().amount()), account.held().currency()),
                        new Money(account.charged().amount().add(settlement.charge().amount()), account.charged().currency()),
                        account.rateVersion(), account.version() + 1));
            }
            var updated = new BudgetReservation(reservation.reservationId(), reservation.budgetRef(), reservation.invocationId(),
                    reservation.attemptId(), reservation.reserved(), reservation.rateVersion(),
                    BudgetReservation.State.valueOf(settlement.state().name()), reservation.version() + 1,
                    reservation.reservedAt(), reservation.expiresAt());
            save(updated);
            budget.insertSettlement(hash(reservation.reservationId()), key, digest, codec.encode(settlement), codec.encode(updated), command.evidence().name(), command.evidenceRef());
            // 只有首次应用结算才写事件；幂等重放在上面返回，不重复扣费、释放或发布。
            event(invocation, updated.attemptId(), new ExecutionPayload.BudgetChanged(InvocationBudgetState.valueOf(updated.state().name()),
                    updated.version(), account.version() + (finalSettlement ? 1 : 0)));
            return StoreOutcome.applied(updated);
        });
    }

    /**
     * 仅裁剪已确认发布的、已知终态历史；调用者根据保留策略选边界，所有裁剪与游标检查同一行锁。
     */
    @Override
    public Mono<StoreOutcome<ExecutionEvent.Cursor>> discardThrough(ExecutionOwner owner, String invocationId, long throughSequence) {
        ContractChecks.range(throughSequence, "throughSequence", 0, Long.MAX_VALUE);
        return outcome(() -> {
            var invocation = invocation(new Version(owner, invocationId, 0));
            require(invocation.state().terminal() && invocation.state() != Invocation.State.UNKNOWN, INVALID_STATE);
            long next = event.nextSequence(hash(invocationId));
            long retained = event.retainedAfter(hash(invocationId));
            require(throughSequence >= retained && throughSequence <= next, VERSION_CONFLICT);
            require(outbox.countUndelivered(hash(invocationId), OutboxMessage.Kind.EVENT.name(), throughSequence) == 0,
                    INVALID_STATE);
            event.discardEvents(hash(invocationId), throughSequence);
            event.saveRetainedAfter(throughSequence, hash(invocationId));
            return StoreOutcome.applied(new ExecutionEvent.Cursor(invocationId, throughSequence));
        });
    }
}
