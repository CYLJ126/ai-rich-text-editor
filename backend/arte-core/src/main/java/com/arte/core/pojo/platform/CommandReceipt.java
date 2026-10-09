package com.arte.core.pojo.platform;

import com.arte.core.enums.platform.CommandDeliveryEnum;
import com.arte.core.enums.platform.CommandStatusEnum;
import com.arte.core.utils.platform.PlatformChecks;

import java.time.Instant;
import java.util.Objects;

/**
 * 命令回执
 * <p>
 * Void 结果可空，value 必须是无凭据的不可变公开 View。重放不重复执行、不恢复已撤销权限。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record CommandReceipt<T>(
        String operationId,
        CommandStatusEnum status,
        CommandDeliveryEnum delivery,
        T value,
        long resultVersion,
        Instant acceptedAt,
        Instant completedAt) {

    public CommandReceipt {
        PlatformChecks.id(operationId, "operationId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(delivery, "delivery");
        PlatformChecks.version(resultVersion, "resultVersion");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
        PlatformChecks.require(status != CommandStatusEnum.APPLIED || completedAt != null, "applied receipt requires completion time");
        PlatformChecks.require(status != CommandStatusEnum.PENDING || completedAt == null, "pending receipt cannot be completed");
        PlatformChecks.require(completedAt == null || !completedAt.isBefore(acceptedAt), "completion precedes acceptance");
    }
}
