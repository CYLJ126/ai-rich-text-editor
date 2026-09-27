package com.arte.ai.pojo.tool;

import com.arte.ai.pojo.tool.po.ToolCallPo;
import com.arte.ai.pojo.tool.po.ToolCallResultPo;

/**
 * 一期工具调用明细及关联结果、重试次数。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
public record ToolCallDetail(ToolCallPo call, ToolCallResultPo result, long retryCount) {
}
