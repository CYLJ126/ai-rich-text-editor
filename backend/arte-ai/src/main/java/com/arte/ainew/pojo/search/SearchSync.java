package com.arte.ainew.pojo.search;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.validation.ContractChecks;

import java.util.List;
import java.util.Objects;

/**
 * 会话搜索后台同步的数据模型集合，描述数据源、处理租约、任务进度及索引变更。
 * 持久化的后台状态不保存搜索正文，也不作为查询授权依据。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
public final class SearchSync {

    private SearchSync() {
    }

    /**
     * 源数据类型：会话（C）、轮次（T）和模型调用（I）。
     */
    public enum Kind {C, T, I}

    /**
     * 源数据当前资格：有效、已移除、已归档或无需索引。
     */
    public enum Disposition {LIVE, REMOVED, ARCHIVED, SKIP}

    /**
     * 索引目标状态：可搜索记录、无正文墓碑或物理删除。
     */
    public enum Desired {LIVE, TOMBSTONE, DELETE}

    /**
     * 一个待扫描或同步的源数据单元，由源类型及稳定键共同标识。
     */
    public record Unit(Kind kind, String key) {
        public Unit {
            Objects.requireNonNull(kind);
            ContractChecks.require(key != null && key.matches("[a-f0-9]{64}"), "Invalid source key");
        }

        /**
         * 将源类型与稳定键组合为后台任务的唯一标识。
         */
        public String taskKey() {
            return kind.name() + key;
        }
    }

    /**
     * 源数据所属主体及会话范围，用于归属校验和归档清理。
     */
    public record Scope(ExecutionOwner owner, String conversationId) {
        public Scope {
            Objects.requireNonNull(owner);
            ContractChecks.id(conversationId, "conversationId");
        }
    }

    /**
     * 源数据的当前资格及抽取记录，供差异比对和发送前复核。
     */
    public record Snapshot(Unit unit, Scope scope, Disposition disposition, List<SearchIndexRecord> records) {
        public Snapshot {
            Objects.requireNonNull(unit);
            Objects.requireNonNull(disposition);
            records = List.copyOf(records);
            ContractChecks.require(disposition == Disposition.LIVE || records.isEmpty(), "Ineligible source contains text");
            for (var r : records)
                ContractChecks.require(scope != null && r.owner().equals(scope.owner()) && r.conversationId().equals(scope.conversationId()), "Source scope mismatch");
        }
    }

    /**
     * 指定索引代际的处理租约，携带工作实例标识及防止旧任务更新的租约版本。
     */
    public record Lease(String generation, String worker, long fence) {
    }

    /**
     * 同一 SQL 采样的数据库时间和最早任务时间；无待处理任务时 nextWorkAt 为 null。
     */
    public record Schedule(long databaseNow, Long nextWorkAt) {
    }

    /**
     * 扫描或校准通道的检查点，保存游标、已完成轮数及下次执行时间。
     */
    public record Checkpoint(String lane, String cursor, long rounds, long nextAt) {
    }

    /**
     * 待处理同步任务，携带已知归属、是否强制重写及失败次数。
     */
    public record Work(Unit unit, Scope scope, boolean forceWrite, int failures) {
    }

    /**
     * 单条索引记录的后台账本，保存无正文身份、内容指纹、同步版本及写入确认进度。
     */
    public record Entry(String entryKey, Unit unit, Scope scope, String identity, String fingerprint,
                        long revision, long acknowledged, Desired desired) {
    }

    /**
     * 待发送到 ES 的单条变更，包含目标记录及写入、墓碑或删除意图。
     */
    public record Mutation(SearchIndexRecord record, Desired desired) {
    }

    /**
     * 单条 ES 变更的执行结果，用于逐项确认进度或安排重试。
     */
    public record WriteResult(String entryKey, boolean success, String errorCode) {
    }

    /**
     * 指定索引代际的整体同步状态，汇总积压、失败、记录数及扫描轮数等指标。
     */
    public record Status(String generation, String state, long pending, long failures, long entries,
                         long completedRounds, Long lastSuccessAt, long missingResults) {
    }

    /**
     * 指定主体范围内的同步状态，供观察该范围的积压、失败及缺失结果。
     */
    public record OwnerStatus(ExecutionOwner owner, String state, long pending, long failures, long entries,
                              long missingResults) {
    }

    /**
     * 租约失效信号，要求当前工作实例停止本轮处理及进度确认。
     */
    public static final class LostLease extends RuntimeException {
        public LostLease() {
            super("Search scan lease lost");
        }
    }

    /**
     * 耐久结果缺失信号，携带会话范围，供后台记录缺失状态并重试。
     */
    public static final class MissingResult extends RuntimeException {
        private final Scope scope;

        public MissingResult(Scope scope) {
            super("Durable result is missing");
            this.scope = scope;
        }

        public Scope scope() {
            return scope;
        }
    }

    /**
     * 归档资格无法确认的信号，携带会话范围，避免将未知状态视为允许索引。
     */
    public static final class EligibilityUnavailable extends RuntimeException {
        private final Scope scope;

        public EligibilityUnavailable(Scope scope) {
            super("Archive eligibility unavailable");
            this.scope = scope;
        }

        public Scope scope() {
            return scope;
        }
    }
}
