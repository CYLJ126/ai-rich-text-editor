package com.arte.app.web.platform;

import com.arte.core.pojo.platform.CommandReceipt;
import com.arte.core.pojo.platform.PageResult;

import java.util.function.Function;

/**
 * 仅转换公开值；保留原命令身份、结果版本、时间和分页边界。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 15:00 ✾
 */
public final class PlatformHttpViews {

    private PlatformHttpViews() {
    }

    public static <T, R> CommandReceipt<R> receipt(CommandReceipt<T> value, Function<T, R> convert) {
        return new CommandReceipt<>(value.operationId(), value.status(), value.delivery(), convert.apply(value.value()),
                value.resultVersion(), value.acceptedAt(), value.completedAt());
    }

    public static <T, R> PageResult<R> page(PageResult<T> value, Function<T, R> convert) {
        return new PageResult<>(value.current(), value.size(), value.total(), value.records().stream().map(convert).toList());
    }
}
