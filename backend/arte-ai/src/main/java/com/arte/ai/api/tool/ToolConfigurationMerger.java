package com.arte.ai.api.tool;

import java.util.Map;

/**
 * 工具配置分层合并接口。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public interface ToolConfigurationMerger {

    Map<String, Object> merge(Map<String, Object> defaults, Map<String, Object> overrides);
}
