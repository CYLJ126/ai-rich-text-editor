package com.arte.ai.service.tool.observability;

import com.arte.ai.api.tool.observability.ToolExecutionListener;
import com.arte.ai.pojo.tool.ToolExecutionContext;
import com.arte.ai.pojo.tool.ToolExecutionEvent;
import com.arte.ai.pojo.tool.ToolReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 工具执行事件统一分发器。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ToolEventRecorder {

    private final List<ToolExecutionListener> listeners;

    public void record(ToolExecutionEvent.Type type, String callId, ToolReference tool,
                       ToolExecutionContext context, Map<String, Object> attributes) {
        ToolExecutionEvent event = new ToolExecutionEvent(UUID.randomUUID().toString(), type,
                Instant.now(), context.traceId(), context.parentSpanId(), callId, tool, attributes);
        for (ToolExecutionListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException exception) {
                log.error("Failed to persist tool event {} for call {}", type, callId, exception);
            }
        }
    }
}
