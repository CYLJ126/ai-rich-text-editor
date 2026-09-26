package com.arte.ai.api.tool.observability;

import com.arte.ai.pojo.tool.ToolExecutionEvent;

/**
 * 工具执行事件监听器，用于 Trace、指标、审计日志和实时页面更新。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public interface ToolExecutionListener {

    void onEvent(ToolExecutionEvent event);
}
