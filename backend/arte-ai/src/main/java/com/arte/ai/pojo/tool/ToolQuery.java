package com.arte.ai.pojo.tool;

import java.util.Set;

/**
 * 工具目录查询条件。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public record ToolQuery(
        String namespace,
        String keyword,
        Set<String> tags,
        ToolRiskProfile.RiskLevel maximumRiskLevel,
        boolean includeDeprecated
) {

    public ToolQuery {
        tags = tags == null ? Set.of() : Set.copyOf(tags);
    }
}
