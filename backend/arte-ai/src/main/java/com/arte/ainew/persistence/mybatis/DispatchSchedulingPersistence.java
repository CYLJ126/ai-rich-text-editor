package com.arte.ainew.persistence.mybatis;

import com.arte.ainew.application.execution.DispatchSchedulingException;
import com.arte.ainew.common.execution.ExecutionOwners;
import com.arte.ainew.config.NewAiSchedulingProperties;
import com.arte.ainew.persistence.mybatis.mapper.OutboxMapper;
import com.arte.ainew.persistence.mybatis.mapper.PersistenceRows.OutboxRow;
import com.arte.ainew.persistence.mybatis.mapper.SchedulingMapper;
import com.arte.ainew.persistence.mybatis.mapper.SystemMapper;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.execution.DispatchPlan;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.execution.OutboxMessage;
import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.core.pojo.platform.ExecutionOwner;

import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;

import static com.arte.ainew.application.execution.DispatchSchedulingException.Reason.*;
import static com.arte.ainew.pojo.execution.OutboxMessage.WorkClass.*;

/**
 * 与 Invocation／Outbox 共用真实写事务的分类调度；无 JVM 权威计数，公平游标、许可及队列预留均在数据库。
 * 调用方管理事务和 JDBC 调度器；所有分类领取先持全局调度锁，不在锁内读取 Invocation 或执行工具。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 21:47 ✾
 */
final class DispatchSchedulingPersistence {

    private final NewAiSchedulingProperties policy;
    private final SchedulingMapper mapper;
    private final OutboxMapper outbox;
    private final SystemMapper locks;
    private final Supplier<java.time.Instant> clock;
    private final String policyDigest;

    DispatchSchedulingPersistence(NewAiSchedulingProperties policy, SchedulingMapper mapper, OutboxMapper outbox,
                                  SystemMapper locks, Supplier<java.time.Instant> clock) {
        this.policy = policy;
        this.mapper = mapper;
        this.outbox = outbox;
        this.locks = locks;
        this.clock = clock;
        policyDigest = CanonicalJson.key(policy.toString());
    }

    boolean enabled() {
        return policy.enabled();
    }

    private void lock() {
        var key = CanonicalJson.key("dispatch-scheduling", "v1");
        // upsert 在新建／重复键分支均取得排他记录锁，事务内无需再 SELECT FOR UPDATE。
        locks.ensureLock(key);
    }

    private void pool() {
        var found = mapper.pool();
        var manifest = Objects.requireNonNullElse(policy.toolManifest(), "");
        if (found == null) mapper.createPool(policyDigest, manifest);
        else if (!found.policyDigest().equals(policyDigest) || !found.manifestDigest().equals(manifest))
            throw new DispatchSchedulingException(NODE_NOT_READY);
    }

    /**
     * 幂等重放已在外层先行返回；新受理才检查并占用队列，禁止超限后留下半份 Invocation。
     */
    void admit(Invocation invocation, DispatchPlan plan) {
        if (!enabled()) return;
        try {
            validate(invocation, plan);
            lock();
            pool();
            var owner = ExecutionOwners.from(invocation.request().context());
            var keys = queueKeys(plan.workClass().name(), space(owner), subject(owner));
            int[] limits = {policy.maxQueued(), policy.queue(plan.workClass()), policy.workspaceQueued(), policy.subjectQueued()};
            // 四个维度一次锁读；限额仍按各维度独立校验，不复用事务外的计数。
            var counts = new HashMap<String, Long>();
            mapper.queueCounts(keys).forEach(row -> counts.put(row.key(), row.reservations()));
            var missing = keys.stream().filter(key -> !counts.containsKey(key)).sorted().toList();
            for (int i = 0; i < keys.size(); i++) {
                if (counts.getOrDefault(keys.get(i), 0L) >= limits[i])
                    throw new DispatchSchedulingException(QUEUE_FULL);
            }
            if (!missing.isEmpty() && mapper.createQueueCounts(missing) != missing.size())
                throw new IllegalStateException("Queue capacity counters were not created");
            if (mapper.incrementQueueCounts(keys) != keys.size())
                throw new IllegalStateException("Queue capacity facts are missing");

        } catch (org.springframework.dao.DataAccessException unavailable) {
            throw new DispatchSchedulingException(UNAVAILABLE, unavailable);
        }
    }

    private void validate(Invocation invocation, DispatchPlan plan) {
        if (invocation.request().kind() != CapabilityDescriptor.Kind.GENERATION && invocation.request().kind() != CapabilityDescriptor.Kind.TOOL)
            throw new DispatchSchedulingException(PLAN_REQUIRED);
        if (plan == null || (invocation.request().kind() == CapabilityDescriptor.Kind.GENERATION) != (plan.workClass() == GENERATION))
            throw new DispatchSchedulingException(PLAN_REQUIRED);
        if (invocation.request().kind() == CapabilityDescriptor.Kind.TOOL) {
            if (plan.workClass() != TOOL_SHORT && plan.workClass() != TOOL_LONG)
                throw new DispatchSchedulingException(PLAN_REQUIRED);
            if (policy.toolManifest() == null) throw new DispatchSchedulingException(NODE_NOT_READY);
        }
    }

    DispatchPlan toolPlan(String provider, Duration declaredTimeout) {
        return new DispatchPlan(declaredTimeout.compareTo(policy.shortThreshold()) <= 0 ? TOOL_SHORT : TOOL_LONG, provider);
    }

    void classify(Invocation invocation, DispatchPlan plan) {
        classify(invocation, plan, invocation.acceptedAt());
    }

    /**
     * 审批等待不计入队列等待；排队起点改变，但原逻辑 deadline 仍是硬上限。
     */
    void classify(Invocation invocation, DispatchPlan plan, java.time.Instant queuedAt) {
        if (!enabled()) return;
        var owner = ExecutionOwners.from(invocation.request().context());
        long deadline = invocation.request().options().deadline().toEpochMilli();
        var type = plan.workClass();
        if (type == TOOL_SHORT || type == TOOL_LONG) {
            var wait = type == TOOL_SHORT ? policy.shortQueueTimeout() : policy.longQueueTimeout();
            deadline = Math.min(deadline, Math.addExact(queuedAt.toEpochMilli(), wait.toMillis()));
        }
        mapper.classify(CanonicalJson.key(invocation.request().context().executionId(), "DISPATCH", "0"), type.name(),
                CanonicalJson.key(plan.providerKey()), space(owner), subject(owner), queuedAt.toEpochMilli(), deadline);
    }

    static String space(ExecutionOwner owner) {
        return CanonicalJson.key(owner.tenantId(), owner.workspaceId());
    }

    static String subject(ExecutionOwner owner) {
        return CanonicalJson.key(owner.tenantId(), owner.subjectId());
    }

    private static List<String> queueKeys(String type, String space, String subject) {
        return List.of(CanonicalJson.key("queue-count", "global"), CanonicalJson.key("queue-count", "class", type),
                CanonicalJson.key("queue-count", "space", space), CanonicalJson.key("queue-count", "subject", subject));
    }

    /**
     * 行锁及计数都在原 Invocation 写事务内；只释放仍持有的逻辑队列预留，重复终态不会重复减计数。
     */
    private void releaseQueue(Invocation invocation) {
        var key = CanonicalJson.key(invocation.request().context().executionId(), "DISPATCH", "0");
        var meta = mapper.metadata(key);
        if (meta == null || meta.queueReserved() == 0) return;
        var keys = queueKeys(meta.workClass(), meta.spaceKey(), meta.subjectKey());
        // 与受理共用批量锁定顺序，避免释放与新预留交错时反向取锁。
        var counts = mapper.queueCounts(keys);
        if (counts.size() != keys.size() || counts.stream().anyMatch(row -> row.reservations() <= 0))
            throw new IllegalStateException("Queue capacity facts are missing");
        if (mapper.decrementQueueCounts(keys) != keys.size())
            throw new IllegalStateException("Queue capacity facts are missing");
    }

    void started(Invocation invocation) {
        if (!enabled()) return;
        boolean release = invocation.request().kind() == CapabilityDescriptor.Kind.TOOL;
        if (release) releaseQueue(invocation);
        mapper.started(CanonicalJson.key(invocation.request().context().executionId()), release);
    }

    void finished(Invocation invocation) {
        if (enabled() && invocation.state().terminal()) {
            releaseQueue(invocation);
            mapper.finish(CanonicalJson.key(invocation.request().context().executionId()));
        }
    }

    void retry(Invocation invocation) {
        if (enabled()) mapper.requeue(CanonicalJson.key(invocation.request().context().executionId()));
    }

    void release(OutboxMessage message) {
        if (enabled() && message.kind() == OutboxMessage.Kind.DISPATCH)
            mapper.release(message.messageId(), message.fencingToken());
    }

    void renew(OutboxMessage message, long expiry) {
        if (!enabled() || message.kind() != OutboxMessage.Kind.DISPATCH) return;
        int changed = mapper.renew(message.messageId(), message.fencingToken(), expiry);
        if (changed != 1 && mapper.metadata(message.messageId()).finished() == 0)
            throw new MybatisExecutionPersistence.Rejected(StoreOutcome.Code.LEASE_LOST);
    }

    void beforeStart(Invocation invocation) {
        if (!enabled()) return;
        var meta = mapper.metadata(CanonicalJson.key(invocation.request().context().executionId(), "DISPATCH", "0"));
        if (meta == null || meta.queueDeadline() <= clock.get().toEpochMilli())
            throw new MybatisExecutionPersistence.Rejected(StoreOutcome.Code.INVALID_STATE);
    }

    /**
     * 复用本事务已锁定的 Outbox 行；许可及数据库时间仍在每个校验边界重新读取。
     */
    void validate(OutboxMessage message, OutboxRow row) {
        if (!enabled() || message.kind() != OutboxMessage.Kind.DISPATCH) return;
        if (!row.workClass().equals(message.workClass().name()))
            throw new MybatisExecutionPersistence.Rejected(StoreOutcome.Code.LEASE_LOST);
        if (row.finished() == 1) return;
        long now = clock.get().toEpochMilli();
        var permit = mapper.findPermit(message.messageId());
        // 精确身份比较留在 Java，避免数据库大小写不敏感排序规则放宽原校验。
        if (permit == null || !permit.messageKey().equals(message.messageId()) || permit.token() != message.fencingToken()
                || !permit.workClass().equals(row.workClass()) || permit.leaseUntil() <= now)
            throw new MybatisExecutionPersistence.Rejected(StoreOutcome.Code.LEASE_LOST);
    }

    /**
     * 容量与空间轮转在同一短事务仲裁；额度不足的空间／提供者跳过，不先领取再等待。
     */
    List<OutboxMessage> claim(OutboxMessage.WorkClass type, String workerId, Duration lease, int limit, String nodeManifest) {
        if (!enabled()) throw new DispatchSchedulingException(NODE_NOT_READY);
        if (type == OUTCOME_CHECK) return List.of(); // 仅保留槽位，S3 前不伪造业务核对工作项。
        if ((type == TOOL_SHORT || type == TOOL_LONG) && (policy.toolManifest() == null || !policy.toolManifest().equals(nodeManifest)))
            throw new DispatchSchedulingException(NODE_NOT_READY);
        lock();
        pool();
        long now = clock.get().toEpochMilli(), expiry = Math.addExact(now, lease.toMillis());
        var ready = mapper.ready(type.name(), now, policy.maxQueued() + policy.generationConcurrency() + policy.shortConcurrency() + policy.longConcurrency() + 256);
        // 空队列仍核验调度池和清单，只省去不会参与本轮仲裁的许可与游标读取。
        if (ready.isEmpty()) return List.of();
        var activeBySpace = new HashMap<String, Integer>();
        var activeByProvider = new HashMap<String, Integer>();
        int activeClass = 0;
        for (var permit : mapper.permits(now)) {
            if (permit.workClass().equals(type.name())) activeClass++;
            activeBySpace.merge(permit.spaceKey(), 1, Integer::sum);
            activeByProvider.merge(permit.providerKey(), 1, Integer::sum);
        }
        var groups = new TreeMap<String, List<SchedulingMapper.Queued>>();
        for (var row : ready) {
            if (row.spaceKey().isEmpty() || row.providerKey().isEmpty())
                throw new DispatchSchedulingException(PLAN_REQUIRED);
            groups.computeIfAbsent(row.spaceKey(), ignored -> new ArrayList<>()).add(row);
        }
        var last = mapper.cursor(type.name());
        var order = new ArrayList<String>();
        groups.keySet().stream().filter(k -> last == null || k.compareTo(last) > 0).forEach(order::add);
        groups.keySet().stream().filter(k -> last != null && k.compareTo(last) <= 0).forEach(order::add);
        var result = new ArrayList<OutboxMessage>();
        String nextCursor = null;
        boolean progress = true;
        while (progress && result.size() < limit) {
            progress = false;
            for (var space : order) {
                if (result.size() == limit) break;
                var candidates = groups.get(space);
                for (var row : List.copyOf(candidates)) {
                    boolean cleanup = row.finished() == 1;
                    if (!cleanup && (activeClass >= policy.cluster(type)
                            || activeBySpace.getOrDefault(space, 0) >= policy.workspaceConcurrency()
                            || activeByProvider.getOrDefault(row.providerKey(), 0) >= policy.providerConcurrency()))
                        continue;
                    // 只有调度锁持有者修改领取；其他消费者必须使用分类端口，旧 claim 在启用后拒绝派发领取。
                    var locked = outbox.lockMessage(row.messageKey());
                    if (locked.isEmpty() || locked.getFirst().delivered() != 0 || locked.getFirst().leaseUntil() > now) {
                        candidates.remove(row);
                        continue;
                    }
                    long token = Math.addExact(locked.getFirst().token(), 1);
                    mapper.release(row.messageKey(), locked.getFirst().token());
                    outbox.claimMessage(workerId, token, expiry, row.messageKey());
                    if (!cleanup) {
                        mapper.permit(row.messageKey(), token, type.name(), space, row.providerKey(), expiry);
                        activeClass++;
                        activeBySpace.merge(space, 1, Integer::sum);
                        activeByProvider.merge(row.providerKey(), 1, Integer::sum);
                    }
                    result.add(new OutboxMessage(row.messageKey(), row.invocationId(), new ExecutionOwner(row.ownerTenant(), row.ownerWorkspace(), row.ownerSubject()),
                            OutboxMessage.Kind.DISPATCH, 0, workerId, token, java.time.Instant.ofEpochMilli(expiry), type));
                    candidates.remove(row);
                    nextCursor = space;
                    progress = true;
                    break; // 每轮每空间最多一条，游标跨实例持久化。
                }
            }
        }
        // 仍持有原调度锁；一次提交本批最后位置，失败时领取、许可和游标一起回滚。
        if (nextCursor != null) {
            if (last == null) mapper.createCursor(type.name(), nextCursor);
            else mapper.saveCursor(type.name(), nextCursor);
        }
        return List.copyOf(result);
    }
}
