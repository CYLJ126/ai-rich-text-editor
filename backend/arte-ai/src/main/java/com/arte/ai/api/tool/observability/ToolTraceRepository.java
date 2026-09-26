package com.arte.ai.api.tool.observability;

import com.arte.ai.pojo.tool.ToolExecutionEvent;
import com.arte.ai.pojo.tool.ToolExecutionTrace;

import java.util.Optional;

/**
 * 工具轨迹的持久化端口。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public interface ToolTraceRepository {

    void append(ToolExecutionEvent event);

    Optional<ToolExecutionTrace> findByTraceId(String traceId);

    Optional<ToolExecutionTrace> findByCallId(String callId);
}
