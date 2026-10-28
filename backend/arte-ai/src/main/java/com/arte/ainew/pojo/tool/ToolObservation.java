package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.core.pojo.platform.ExecutionOwner;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 本人当前空间的有界观测契约
 * <p>
 * 时间窗口按受理时刻筛选，状态为查询时的权威事实。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/22 14:40 ✾
 */
public final class ToolObservation {

    private ToolObservation() {
    }

    public static final int LIST_SCAN_LIMIT = 50;
    public static final int STATISTICS_SCAN_LIMIT = 100;

    public record Query(Instant from, Instant until, String bindingId, String toolId, Invocation.State state) {
        public Query {
            Objects.requireNonNull(from);
            Objects.requireNonNull(until);
            ContractChecks.require(from.isBefore(until) && Duration.between(from, until).compareTo(Duration.ofDays(31)) <= 0,
                    "Observation window must be positive and at most 31 days");
            ContractChecks.require(from.toEpochMilli() > 0, "Observation window must follow epoch");
            ContractChecks.require(from.equals(Instant.ofEpochMilli(from.toEpochMilli())) && until.equals(Instant.ofEpochMilli(until.toEpochMilli())),
                    "Observation window requires millisecond precision");
            ContractChecks.optionalId(bindingId, "bindingId");
            ContractChecks.optionalId(toolId, "toolId");
        }

        public String fingerprint(ExecutionOwner owner) {
            return CanonicalJson.digest(CanonicalJson.fields("format", "tool-observation-query-v1", "owner", owner, "query", this));
        }
    }

    public record Cost(String state, String currency, String settledAmount, String reservedAmount, boolean unknown) {
    }

    public record Timings(Long admissionToAttemptMillis, Long approvalWaitMillis, Long attemptSpanMillis,
                          Long terminalSpanMillis, Long elapsedMillis) {
    }

    public record Item(String invocationId, String traceId, String parentExecutionId, String source, String toolId,
                       String toolVersion, String bindingId, String title, String providerId, Invocation.State state,
                       String version, Instant acceptedAt, Instant updatedAt, int attemptCount, int retryCount,
                       String errorCode, String billingMode, Cost cost, Timings timings, Long inputBytes,
                       Long outputBytes) {
    }

    public record Page(List<Item> records, String nextCursor, boolean exhausted, int scanned, int scanLimit,
                       boolean historicalIncomplete, Instant observedAt, Query query, String scope) {
    }

    public record CurrencyTotals(String currency, String settledAmount, String reservedAmount) {
    }

    public record Statistics(Query query, String scope, Instant observedAt, int invocations, int attempts, int retries,
                             Map<Invocation.State, Long> states, long determinedTerminals, String successRate,
                             int freeCalls, int chargedCalls, int unknownCostCalls, int knownZeroCostCalls,
                             List<CurrencyTotals> currencies, Long terminalSpanP50Millis, Long terminalSpanP95Millis,
                             long knownInputBytes, int unknownInputBytesCalls, Long outputBytes,
                             int scanned, int scanLimit, boolean truncated, boolean historicalIncomplete) {
    }
}
