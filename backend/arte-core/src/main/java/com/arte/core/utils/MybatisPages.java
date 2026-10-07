package com.arte.core.utils;

import com.arte.core.pojo.PageParam;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

/**
 * 将客户端分页条件转换为独立的 MyBatis-Plus 分页容器，不修改请求对象。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/7 13:35 ✾
 */
public final class MybatisPages {

    private MybatisPages() {
    }

    /**
     * 不依赖 HTTP 校验，直接调用 Service 的入口也不能使用非法分页条件。
     * <p>
     * 转换前检查 {@code (current - 1) * size} 是否超出 long 范围，
     * 避免 MyBatis-Plus 计算偏移量时溢出并回到第一页。
     * 每次返回新容器，查询回填的记录和总数不会进入原请求。
     */
    public static <T> Page<T> from(PageParam param) {
        if (param == null || param.getCurrent() == null || param.getSize() == null) {
            throw new IllegalArgumentException("分页参数不能为空");
        }
        long current = param.getCurrent();
        long size = param.getSize();
        if (current < 1 || size < 1 || size > PageParam.MAX_SIZE) {
            throw new IllegalArgumentException("分页参数超出允许范围");
        }
        try {
            // 检查偏移是否可表示，溢出时拒绝请求
            Math.multiplyExact(current - 1, size);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("分页偏移量超出允许范围", overflow);
        }
        return new Page<>(current, size);
    }
}
