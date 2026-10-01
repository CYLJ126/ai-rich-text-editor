package com.arte.ai.pojo.tool;

import java.util.List;

/**
 * 工具兼容升级的发布预览结果，包含兼容检查结论及用户绑定的影响统计。
 *
 * @param compatible        目标版本是否通过相对于兼容基准的契约及风险权限检查
 * @param problems          未通过兼容检查的原因列表，通过时为空
 * @param followingBindings 基准兼容链中采用跟随兼容升级策略的绑定数
 * @param pinnedBindings    该工具采用锁定版本策略的绑定数
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/1 ✾
 */
public record ToolUpgradePreview(boolean compatible, List<String> problems,
                                 long followingBindings, long pinnedBindings) {
}
