package com.arte.ai.service.tool.observability;

import com.arte.ai.api.tool.observability.ToolExecutionListener;
import com.arte.ai.mapper.tool.ToolExecutionEventMapper;
import com.arte.ai.pojo.tool.ToolExecutionEvent;
import com.arte.ai.pojo.tool.po.ToolExecutionEventPo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 将追加式工具执行事件写入数据库。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
@Component
@RequiredArgsConstructor
public class DatabaseToolExecutionListener implements ToolExecutionListener {

    private final ToolExecutionEventMapper eventMapper;

    @Override
    public void onEvent(ToolExecutionEvent event) {
        ToolExecutionEventPo po = new ToolExecutionEventPo()
                .setEventId(event.eventId())
                .setEventType(event.type().name().toLowerCase())
                .setOccurredAt(LocalDateTime.ofInstant(event.occurredAt(), ZoneId.systemDefault()))
                .setTraceId(event.traceId())
                .setSpanId(event.spanId())
                .setCallId(event.callId())
                .setTaskId(string(event.attributes().get("taskId")))
                .setWorkflowRunId(string(event.attributes().get("workflowRunId")))
                .setToolId(event.tool().namespace() + ":" + event.tool().name())
                .setToolVersion(event.tool().version())
                .setAttributes(event.attributes());
        eventMapper.insert(po);
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
