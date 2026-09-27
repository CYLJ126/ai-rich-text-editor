package com.arte.ai.service.tool.observability;

import com.arte.ai.api.tool.observability.ToolTraceRepository;
import com.arte.ai.mapper.tool.ToolExecutionEventMapper;
import com.arte.ai.pojo.tool.ToolExecutionEvent;
import com.arte.ai.pojo.tool.ToolExecutionTrace;
import com.arte.ai.pojo.tool.ToolReference;
import com.arte.ai.pojo.tool.po.ToolExecutionEventPo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * 基于追加事件表的调用轨迹仓库。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
@Repository
@RequiredArgsConstructor
public class DatabaseToolTraceRepository implements ToolTraceRepository {
    private final ToolExecutionEventMapper mapper;
    private final DatabaseToolExecutionListener listener;

    @Override
    public void append(ToolExecutionEvent event) {
        listener.onEvent(event);
    }

    @Override
    public Optional<ToolExecutionTrace> findByTraceId(String traceId) {
        return trace(mapper.selectByTraceId(traceId));
    }

    @Override
    public Optional<ToolExecutionTrace> findByCallId(String callId) {
        return trace(mapper.selectByCallId(callId));
    }

    public Optional<ToolExecutionTrace> findByTraceId(String traceId, String ownerId) {
        return trace(mapper.selectByTraceIdAndOwner(traceId, ownerId));
    }

    private Optional<ToolExecutionTrace> trace(List<ToolExecutionEventPo> values) {
        if (values.isEmpty()) return Optional.empty();
        List<ToolExecutionEvent> events = values.stream().map(this::event).toList();
        return Optional.of(new ToolExecutionTrace(events.getFirst().traceId(),
                events.getFirst().callId(), events));
    }

    private ToolExecutionEvent event(ToolExecutionEventPo po) {
        String[] identity = po.getToolId().split(":", 2);
        return new ToolExecutionEvent(po.getEventId(),
                ToolExecutionEvent.Type.valueOf(po.getEventType().toUpperCase()),
                po.getOccurredAt().atZone(ZoneId.systemDefault()).toInstant(), po.getTraceId(),
                po.getSpanId(), po.getCallId(), new ToolReference(identity[0], identity[1],
                po.getToolVersion()), po.getAttributes());
    }
}
