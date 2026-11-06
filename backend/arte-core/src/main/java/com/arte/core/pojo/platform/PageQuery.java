package com.arte.core.pojo.platform;

import com.arte.core.pojo.PageParam;
import com.arte.core.utils.platform.PlatformChecks;

/**
 * 公共分页值对象
 * <p>
 * 不依赖 MyBatis Page，HTTP 适配可由现有 {@link PageParam} 转换。
 *
 * @param current 请求页码，从 1 开始
 * @param size 请求的每页条数，范围为 1～100
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record PageQuery(long current, long size) {

    public PageQuery {
        PlatformChecks.require(current >= 1 && size >= 1 && size <= 100, "invalid page bounds");
        Math.multiplyExact(current - 1, size);
    }
}
