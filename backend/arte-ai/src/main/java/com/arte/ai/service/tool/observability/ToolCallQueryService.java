package com.arte.ai.service.tool.observability;

import com.arte.ai.common.enums.tool.ToolResultStatusEnum;
import com.arte.ai.mapper.tool.ToolCallMapper;
import com.arte.ai.mapper.tool.ToolCallResultMapper;
import com.arte.ai.mapper.tool.ToolExecutionEventMapper;
import com.arte.ai.pojo.tool.ToolCallDetail;
import com.arte.ai.pojo.tool.ToolCallPage;
import com.arte.ai.pojo.tool.ToolCallStatistics;
import com.arte.ai.pojo.tool.ToolExecutionTrace;
import com.arte.ai.pojo.tool.po.ToolCallPo;
import com.arte.ai.pojo.tool.po.ToolCallResultPo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 所有查询都强制携带当前 ownerId 的调用明细与一期实时统计服务。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
@Service
@RequiredArgsConstructor
public class ToolCallQueryService {
    private static final int MAX_PAGE_SIZE = 200;
    private static final Set<String> STATUSES = Arrays.stream(ToolResultStatusEnum.values())
            .map(ToolResultStatusEnum::getValue).collect(java.util.stream.Collectors.toUnmodifiableSet());

    private final ToolCallMapper calls;
    private final ToolCallResultMapper results;
    private final ToolExecutionEventMapper events;
    private final DatabaseToolTraceRepository traces;

    public List<ToolCallDetail> details(String ownerId, String toolId, String status, int requestedLimit) {
        int limit = Math.clamp(requestedLimit, 1, 500);
        List<ToolCallPo> callValues = calls.selectDetails(ownerId, normalizeText(toolId),
                normalizeStatus(status), limit);
        return details(callValues);
    }

    public ToolCallPage page(String ownerId, String toolId, String status, int current, int size) {
        String normalizedToolId = normalizeText(toolId);
        String normalizedStatus = normalizeStatus(status);
        int safeCurrent = Math.max(1, current);
        int safeSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        long offset = (long) (safeCurrent - 1) * safeSize;
        List<ToolCallPo> callValues = calls.selectDetailsPage(ownerId, normalizedToolId,
                normalizedStatus, offset, safeSize);
        return new ToolCallPage(details(callValues),
                calls.countDetails(ownerId, normalizedToolId, normalizedStatus), safeCurrent, safeSize);
    }

    private List<ToolCallDetail> details(List<ToolCallPo> callValues) {
        if (callValues.isEmpty()) return List.of();
        List<String> callIds = callValues.stream().map(ToolCallPo::getCallId).toList();
        Map<String, ToolCallResultPo> resultByCall = new HashMap<>();
        results.selectByCallIds(callIds).forEach(result -> resultByCall.put(result.getCallId(), result));
        Map<String, Long> retriesByCall = retryCounts(callIds);
        return callValues.stream().map(call -> new ToolCallDetail(call,
                resultByCall.get(call.getCallId()), retriesByCall.getOrDefault(call.getCallId(), 0L))).toList();
    }

    public Optional<ToolCallDetail> detail(String ownerId, String callId) {
        return calls.selectOwned(callId, ownerId).map(this::detail);
    }

    public Optional<ToolExecutionTrace> trace(String ownerId, String traceId) {
        return traces.findByTraceId(traceId, ownerId);
    }

    public ToolCallStatistics statistics(String ownerId, String toolId, int limit) {
        int boundedLimit = Math.clamp(limit, 1, 500);
        List<ToolCallPo> values = calls.selectDetails(ownerId, normalizeText(toolId), null, boundedLimit);
        long succeeded = values.stream().filter(value -> value.getStatus() == ToolResultStatusEnum.SUCCEEDED).count();
        long denied = values.stream().filter(value -> value.getStatus() == ToolResultStatusEnum.DENIED).count();
        Set<ToolResultStatusEnum> failureStates = EnumSet.of(ToolResultStatusEnum.FAILED,
                ToolResultStatusEnum.CANCELLED, ToolResultStatusEnum.TIMED_OUT);
        long failed = values.stream().filter(value -> failureStates.contains(value.getStatus())).count();
        long measuredLatencyCount = values.stream().filter(value -> value.getLatencyMs() != null).count();
        long latency = values.stream().map(ToolCallPo::getLatencyMs)
                .filter(Objects::nonNull).mapToLong(Long::longValue).sum();
        Map<String, Long> reasons = new LinkedHashMap<>();
        values.stream().filter(value -> value.getErrorCode() != null)
                .forEach(value -> reasons.merge(value.getErrorCode(), 1L, Long::sum));
        List<String> callIds = values.stream().map(ToolCallPo::getCallId).toList();
        return new ToolCallStatistics(values.size(), succeeded, failed, denied,
                values.isEmpty() ? 0 : (double) succeeded / values.size(),
                measuredLatencyCount == 0 ? 0 : (double) latency / measuredLatencyCount,
                values.stream().mapToLong(value -> integer(value.getInputTokens())).sum(),
                values.stream().mapToLong(value -> integer(value.getOutputTokens())).sum(),
                retryCounts(callIds).values().stream().mapToLong(Long::longValue).sum(),
                Map.copyOf(reasons));
    }

    private ToolCallDetail detail(ToolCallPo call) {
        long retries = events.selectByCallId(call.getCallId()).stream()
                .filter(event -> "retried".equals(event.getEventType())).count();
        return new ToolCallDetail(call, results.selectByCallId(call.getCallId()).orElse(null), retries);
    }

    private Map<String, Long> retryCounts(List<String> callIds) {
        if (callIds.isEmpty()) return Map.of();
        Map<String, Long> counts = new HashMap<>();
        events.selectRetriesByCallIds(callIds)
                .forEach(event -> counts.merge(event.getCallId(), 1L, Long::sum));
        return counts;
    }

    private long integer(Integer value) {
        return value == null ? 0 : value;
    }

    private String normalizeStatus(String value) {
        String status = normalizeText(value);
        if (status == null) return null;
        status = status.toLowerCase(Locale.ROOT);
        if (!STATUSES.contains(status)) {
            throw new IllegalArgumentException("unsupported tool call status: " + value);
        }
        return status;
    }

    private String normalizeText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
