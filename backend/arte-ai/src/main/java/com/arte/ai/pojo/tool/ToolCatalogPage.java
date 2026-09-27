package com.arte.ai.pojo.tool;

import java.util.List;

/**
 * 工具管理目录分页结果。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
public record ToolCatalogPage(
        List<ToolCatalogItem> records,
        long total,
        int current,
        int size
) {
    public ToolCatalogPage {
        records = records == null ? List.of() : List.copyOf(records);
    }
}
