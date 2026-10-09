package com.arte.core.pojo.platform;

import com.arte.core.utils.platform.PlatformChecks;

import java.util.List;

/**
 * 公共分页结果；API 的 total 必须仅计可见数据，records 应为不可变 View。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record PageResult<T>(
        long current,
        long size,
        long total,
        List<T> records) {
    public PageResult {
        PlatformChecks.version(total, "total");
        records = List.copyOf(records);
        PlatformChecks.require(current >= 1 && size >= 1 && size <= 100, "invalid page bounds");
        PlatformChecks.require(records.size() <= size && records.size() <= total, "records exceed page or total");
    }
}
