package com.arte.ai.api.tool;

import com.arte.ai.pojo.tool.ToolExecutionPolicy;
import com.arte.ai.pojo.tool.ToolPolicyOverride;

import java.util.Map;

/**
 * 工具策略解析与安全收紧接口。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public interface ToolPolicyMerger {

    ToolExecutionPolicy decodePolicy(Map<String, Object> value);

    ToolPolicyOverride decodeOverride(Map<String, Object> value);

    Map<String, Object> encodeOverride(ToolPolicyOverride value);

    ToolExecutionPolicy tighten(ToolExecutionPolicy base, ToolPolicyOverride override);
}
