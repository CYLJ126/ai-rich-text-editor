package com.arte.ai.api.tool;

import com.arte.ai.pojo.tool.ToolReference;

/**
 * 判断工具版本是否已发布且其工具、提供者均处于可用状态。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
public interface ToolAvailabilityService {

    boolean isAvailable(ToolReference reference);
}
