package com.arte.ainew.web.response;

import com.arte.ainew.common.execution.ExecutionEvent;
import com.arte.ainew.pojo.execution.ExecutionPayload;
import com.arte.ainew.pojo.generation.GenerationEvent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 已授权耐久事件的 SSE 投影。只有 OUTPUT 附带文字；不暴露工具参数、上下文、供应商原文或账本金额。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/8 05:00 ✾
 */
public final class InvocationNotification {

    private InvocationNotification() {
    }

    public static Map<String, Object> from(ExecutionEvent<?> event) {
        var result = new LinkedHashMap<String, Object>();
        result.put("executionId", event.executionId());
        result.put("sequence", event.sequence());
        result.put("kind", event.kind().name());
        if (event.payload() instanceof ExecutionPayload.OutputBatch(List<GenerationEvent> events)) {
            var text = new StringBuilder();
            for (var delta : events) {
                if (delta instanceof GenerationEvent.TextDelta(String text1)) text.append(text1);
            }
            result.put("text", text.toString());
        }
        return result;
    }
}
