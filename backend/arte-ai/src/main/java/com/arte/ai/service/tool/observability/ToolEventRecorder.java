package com.arte.ai.service.tool.observability;

import com.arte.ai.api.tool.observability.ToolExecutionListener;
import com.arte.ai.pojo.tool.ToolExecutionContext;
import com.arte.ai.pojo.tool.ToolExecutionEvent;
import com.arte.ai.pojo.tool.ToolReference;
import com.arte.ai.service.tool.security.ToolDataSanitizer;
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
    private final ToolDataSanitizer sanitizer;

    public void record(ToolExecutionEvent.Type type, String callId, ToolReference tool,
                       ToolExecutionContext context, Map<String, Object> attributes) {
        Map<String, Object> enriched = new java.util.LinkedHashMap<>(attributes == null ? Map.of() : attributes);
        if (context.workflowRunId() != null) enriched.put("workflowRunId", context.workflowRunId());
        ToolExecutionEvent event = new ToolExecutionEvent(UUID.randomUUID().toString(), type,
                Instant.now(), context.traceId(), context.parentSpanId(), callId, tool,
                sanitizer.sanitize(enriched));
        for (ToolExecutionListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException exception) {
                log.error("Failed to persist tool event {} for call {}", type, callId, exception);
            }
        }
    }
}
