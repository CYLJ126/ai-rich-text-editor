package com.arte.core.application.workspace;

import com.arte.core.enums.platform.PrincipalKindEnum;
import com.arte.core.enums.platform.PrincipalStatusEnum;
import com.arte.core.pojo.platform.*;
import com.arte.core.pojo.workspace.*;
import com.arte.core.spi.platform.*;
import com.arte.core.spi.workspace.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 仅测试：串行本地事务、状态回滚及唯一／版本条件模拟；不注册生产内存适配器。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 22:35 ✾
 */
final class InMemoryWorkspaceFixture {
    static final Instant NOW = Instant.parse("2026-10-09T00:00:00Z");
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    final Map<String, Principal> identities = new HashMap<>();
    final Map<String, Tenant> tenants = new LinkedHashMap<>();
    final Map<Scope, Workspace> workspaces = new LinkedHashMap<>();
    final Map<List<String>, TenantMembership> tenantMembers = new HashMap<>();
    final Map<List<String>, WorkspaceMembership> workspaceMembers = new HashMap<>();
    final Map<String, TenantPolicy> tenantPolicies = new HashMap<>();
    final Map<Scope, WorkspacePolicy> workspacePolicies = new HashMap<>();
    final Map<String, WorkspacePreference> preferences = new HashMap<>();
    final Map<CommandIdentity, StoredCommandReceipt> receipts = new HashMap<>();
    final List<PublicEvent> events = new ArrayList<>();
    final List<AuditEntry> audits = new ArrayList<>();
    final List<String> lockOrder = new ArrayList<>();
    boolean failEvents;
    boolean failReceipt;
    boolean failIdentity;
    boolean scanAll;
    boolean loopCursor;
    private final AtomicLong sequence = new AtomicLong();
    private final ReentrantLock transactionLock = new ReentrantLock();
    private final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);
    private final ThreadLocal<Boolean> rollbackOnly = ThreadLocal.withInitial(() -> false);

    final IdentityProvider identity = new IdentityProvider() {
        public Optional<Principal> resolveAuthenticatedName(String name) {
            return findBySubjectId(name);
        }

        public Optional<Principal> findBySubjectId(String id) {
            if (failIdentity) throw new IllegalStateException("Identity store unavailable");
            return Optional.ofNullable(identities.get(id));
        }
    };

    final PublicTransactionRunner transactions = new PublicTransactionRunner() {
        public boolean isTransactionActive() {
            return depth.get() > 0;
        }

        public <T> T required(Supplier<T> work) {
            transactionLock.lock();
            boolean root = depth.get() == 0;
            Snapshot before = root ? snapshot() : null;
            depth.set(depth.get() + 1);
            try {
                T value = work.get();
                if (root && rollbackOnly.get()) throw new IllegalStateException("Transaction is rollback-only");
                return value;
            } catch (RuntimeException | Error failure) {
                rollbackOnly.set(true);
                if (root) restore(before);
                throw failure;
            } finally {
                depth.set(depth.get() - 1);
                if (root) {
                    depth.remove();
                    rollbackOnly.remove();
                }
                transactionLock.unlock();
            }
        }
    };

    final TenantStore tenantStore = new TenantStore() {
        public Optional<Tenant> find(String id) {
            return Optional.ofNullable(tenants.get(id));
        }

        public Optional<Tenant> findPersonal(String subject) {
            return tenants.values().stream().filter(t -> subject.equals(t.personalSubjectId())).findFirst();
        }

        public Optional<Tenant> lock(String id) {
            transactions.requireActive();
            lockOrder.add("tenant:" + id);
            return find(id);
        }

        public boolean insert(Tenant tenant) {
            transactions.requireActive();
            if (tenants.containsKey(tenant.id()) || (tenant.personalSubjectId() != null && findPersonal(tenant.personalSubjectId()).isPresent()))
                return false;
            tenants.put(tenant.id(), tenant);
            return true;
        }

        public boolean replace(Tenant tenant, long version) {
            transactions.requireActive();
            Tenant old = tenants.get(tenant.id());
            if (old == null || old.version() != version || tenant.version() != version + 1) return false;
            tenants.put(tenant.id(), tenant);
            return true;
        }
    };

    final WorkspaceStore workspaceStore = new WorkspaceStore() {
        public Optional<Workspace> find(Scope scope) {
            return Optional.ofNullable(workspaces.get(scope));
        }

        public Optional<Workspace> lock(Scope scope) {
            transactions.requireActive();
            lockOrder.add("workspace:" + scope.workspaceId());
            return find(scope);
        }

        public boolean insert(Workspace workspace) {
            transactions.requireActive();
            return workspaces.putIfAbsent(workspace.scope(), workspace) == null;
        }

        public boolean replace(Workspace workspace, long version) {
            transactions.requireActive();
            Workspace old = workspaces.get(workspace.scope());
            if (old == null || old.version() != version || workspace.version() != version + 1) return false;
            workspaces.put(workspace.scope(), workspace);
            return true;
        }

        public WorkspaceCandidatePage scanMembershipCandidates(String subject, String cursor, int limit) {
            var candidates = workspaces.values().stream().filter(w -> scanAll || workspaceMembers.containsKey(workspaceKey(w.scope(), subject)))
                    .sorted(Comparator.comparing(Workspace::updatedAt).reversed().thenComparing(w -> w.scope().workspaceId())).toList();
            int offset = cursor == null ? 0 : Integer.parseInt(cursor);
            int end = Math.min(candidates.size(), offset + limit);
            return new WorkspaceCandidatePage(candidates.subList(Math.min(offset, candidates.size()), end),
                    loopCursor ? "0" : end < candidates.size() ? Integer.toString(end) : null);
        }
    };

    final TenantMembershipStore tenantMemberStore = new TenantMembershipStore() {
        public PageResult<TenantMembership> list(String tenant, PageQuery query) {
            return page(tenantMembers.values().stream().filter(m -> m.tenantId().equals(tenant)).toList(), query);
        }

        public Optional<TenantMembership> find(String tenant, String subject) {
            return Optional.ofNullable(tenantMembers.get(List.of(tenant, subject)));
        }

        public Optional<TenantMembership> findById(String tenant, String id) {
            return tenantMembers.values().stream().filter(m -> m.tenantId().equals(tenant) && m.id().equals(id)).findFirst();
        }

        public Optional<TenantMembership> lock(String tenant, String subject) {
            transactions.requireActive();
            lockOrder.add("tenant-member:" + tenant);
            return find(tenant, subject);
        }

        public boolean insert(TenantMembership member) {
            transactions.requireActive();
            return tenantMembers.putIfAbsent(List.of(member.tenantId(), member.subjectId()), member) == null;
        }

        public boolean replace(TenantMembership member, long version) {
            transactions.requireActive();
            var key = List.of(member.tenantId(), member.subjectId());
            var old = tenantMembers.get(key);
            if (old == null || old.version() != version || member.version() != version + 1) return false;
            tenantMembers.put(key, member);
            return true;
        }
    };

    final WorkspaceMembershipStore workspaceMemberStore = new WorkspaceMembershipStore() {
        public PageResult<WorkspaceMembership> list(Scope scope, PageQuery query) {
            return page(workspaceMembers.values().stream().filter(m -> m.scope().equals(scope)).toList(), query);
        }

        public Optional<WorkspaceMembership> find(Scope scope, String subject) {
            return Optional.ofNullable(workspaceMembers.get(workspaceKey(scope, subject)));
        }

        public Optional<WorkspaceMembership> findById(Scope scope, String id) {
            return workspaceMembers.values().stream().filter(m -> m.scope().equals(scope) && m.id().equals(id)).findFirst();
        }

        public Optional<WorkspaceMembership> lock(Scope scope, String subject) {
            transactions.requireActive();
            lockOrder.add("workspace-member:" + scope.workspaceId());
            return find(scope, subject);
        }

        public boolean insert(WorkspaceMembership member) {
            transactions.requireActive();
            return workspaceMembers.putIfAbsent(workspaceKey(member.scope(), member.subjectId()), member) == null;
        }

        public boolean replace(WorkspaceMembership member, long version) {
            transactions.requireActive();
            var key = workspaceKey(member.scope(), member.subjectId());
            var old = workspaceMembers.get(key);
            if (old == null || old.version() != version || member.version() != version + 1) return false;
            workspaceMembers.put(key, member);
            return true;
        }
    };

    final PolicyStore policyStore = new PolicyStore() {
        public Optional<TenantPolicy> findTenantPolicy(String id) {
            return Optional.ofNullable(tenantPolicies.get(id));
        }

        public Optional<WorkspacePolicy> findWorkspacePolicy(Scope scope) {
            return Optional.ofNullable(workspacePolicies.get(scope));
        }

        public boolean insertTenantPolicy(TenantPolicy policy) {
            transactions.requireActive();
            return tenantPolicies.putIfAbsent(policy.tenantId(), policy) == null;
        }

        public boolean insertWorkspacePolicy(WorkspacePolicy policy) {
            transactions.requireActive();
            return workspacePolicies.putIfAbsent(policy.scope(), policy) == null;
        }

        public boolean replaceTenantPolicy(TenantPolicy policy, long version) {
            transactions.requireActive();
            var old = tenantPolicies.get(policy.tenantId());
            if (old == null || old.version() != version || policy.version() != version + 1) return false;
            tenantPolicies.put(policy.tenantId(), policy);
            return true;
        }

        public boolean replaceWorkspacePolicy(WorkspacePolicy policy, long version) {
            transactions.requireActive();
            var old = workspacePolicies.get(policy.scope());
            if (old == null || old.version() != version || policy.version() != version + 1) return false;
            workspacePolicies.put(policy.scope(), policy);
            return true;
        }
    };

    final PreferenceStore preferenceStore = new PreferenceStore() {
        public void lockSubject(String subject) {
            transactions.requireActive();
            lockOrder.add("subject:" + subject);
        }

        public Optional<WorkspacePreference> find(String subject) {
            return Optional.ofNullable(preferences.get(subject));
        }

        public boolean insert(WorkspacePreference preference) {
            transactions.requireActive();
            if (preferences.values().stream().anyMatch(p -> p.defaultScope().equals(preference.defaultScope())))
                return false;
            return preferences.putIfAbsent(preference.subjectId(), preference) == null;
        }

        public boolean replace(WorkspacePreference preference, long version) {
            transactions.requireActive();
            var old = preferences.get(preference.subjectId());
            if (old == null || old.version() != version || preference.version() != version + 1
                    || !old.defaultScope().equals(preference.defaultScope())) return false;
            preferences.put(preference.subjectId(), preference);
            return true;
        }
    };

    final CommandReceiptStore receiptStore = new CommandReceiptStore() {
        public Optional<StoredCommandReceipt> find(CommandIdentity identity) {
            return Optional.ofNullable(receipts.get(identity));
        }

        public Optional<StoredCommandReceipt> findByOperationId(String id) {
            return receipts.values().stream().filter(r -> r.receipt().operationId().equals(id)).findFirst();
        }

        public boolean insertIfAbsent(StoredCommandReceipt receipt) {
            transactions.requireActive();
            if (failReceipt) return false;
            return receipts.putIfAbsent(receipt.identity(), receipt) == null;
        }

        public boolean replace(StoredCommandReceipt receipt, long version) {
            transactions.requireActive();
            var old = receipts.get(receipt.identity());
            if (old == null || old.version() != version || receipt.version() != version + 1) return false;
            receipts.put(receipt.identity(), receipt);
            return true;
        }
    };

    final PublicOutboxStore outbox = event -> {
        transactions.requireActive();
        if (failEvents) throw new IllegalStateException("Outbox unavailable");
        events.add(event);
    };
    final AuditSink audit = entry -> {
        transactions.requireActive();
        audits.add(entry);
    };
    final DefaultWorkspaceServices services = new DefaultWorkspaceServices(identity, transactions, tenantStore, workspaceStore,
            tenantMemberStore, workspaceMemberStore, policyStore, preferenceStore, receiptStore, outbox, audit,
            () -> "id-" + sequence.incrementAndGet(), clock, DefaultWorkspacePolicies.conservativePersonalDefaults());

    TrustedActor user(String id) {
        Principal principal = new Principal(id, PrincipalKindEnum.USER, PrincipalStatusEnum.ACTIVE);
        identities.put(id, principal);
        return new TrustedActor(principal, NOW);
    }

    WorkspaceViews.PersonalWorkspaceView initialize(TrustedActor actor) {
        return services.personal().initialize(actor, meta("initialize")).value();
    }

    static CommandMeta meta(String key) {
        return new CommandMeta(key, "trace-" + key);
    }

    static List<String> workspaceKey(Scope scope, String subject) {
        return List.of(scope.tenantId(), scope.workspaceId(), subject);
    }

    private static <T> PageResult<T> page(List<T> all, PageQuery query) {
        int from = (int) Math.min(all.size(), (query.current() - 1) * query.size());
        int end = (int) Math.min(all.size(), from + query.size());
        return new PageResult<>(query.current(), query.size(), all.size(), all.subList(from, end));
    }

    private record Snapshot(Map<String, Tenant> tenants, Map<Scope, Workspace> workspaces,
                            Map<List<String>, TenantMembership> tenantMembers,
                            Map<List<String>, WorkspaceMembership> workspaceMembers,
                            Map<String, TenantPolicy> tenantPolicies, Map<Scope, WorkspacePolicy> workspacePolicies,
                            Map<String, WorkspacePreference> preferences,
                            Map<CommandIdentity, StoredCommandReceipt> receipts,
                            List<PublicEvent> events, List<AuditEntry> audits) {
    }

    private Snapshot snapshot() {
        return new Snapshot(new LinkedHashMap<>(tenants), new LinkedHashMap<>(workspaces), new HashMap<>(tenantMembers),
                new HashMap<>(workspaceMembers), new HashMap<>(tenantPolicies), new HashMap<>(workspacePolicies),
                new HashMap<>(preferences), new HashMap<>(receipts), new ArrayList<>(events), new ArrayList<>(audits));
    }

    private void restore(Snapshot before) {
        restoreMap(tenants, before.tenants());
        restoreMap(workspaces, before.workspaces());
        restoreMap(tenantMembers, before.tenantMembers());
        restoreMap(workspaceMembers, before.workspaceMembers());
        restoreMap(tenantPolicies, before.tenantPolicies());
        restoreMap(workspacePolicies, before.workspacePolicies());
        restoreMap(preferences, before.preferences());
        restoreMap(receipts, before.receipts());
        events.clear();
        events.addAll(before.events());
        audits.clear();
        audits.addAll(before.audits());
    }

    private static <K, V> void restoreMap(Map<K, V> target, Map<K, V> original) {
        target.clear();
        target.putAll(original);
    }
}
