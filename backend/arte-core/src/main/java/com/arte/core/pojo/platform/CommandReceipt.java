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
 * 返回写命令的操作标识、处理状态及结果快照；当前命令执行器将领域写入与成功回执在同一事务中保存。
 *
 * @param operationId   服务端生成的操作标识，不能为空，用于关联回执查询、领域事件和审计；重放时保留原标识。
 * @param status        命令处理状态，不能为空；PENDING 表示已受理待完成，APPLIED 表示已提交，当前命令执行器保存成功的 APPLIED 回执。
 * @param delivery      本次回执的返回方式，不能为空；APPLIED 表示首次提交返回，REPLAYED 表示返回已保存的原回执。
 * @param value         命令结果的不可变公开快照，须符合该命令约定的结果类型且不含凭据；Void 命令为空，重放返回原结果并重新校验当前可见权限。
 * @param resultVersion 非负的命令结果版本，具体对应的记录或结果由命令定义，用于后续版本校验；重放保留原值，不代表查询时的最新版本。
 * @param acceptedAt    首次执行的受理时间，不能为空；重放保留原时间。
 * @param completedAt   命令完成时间；APPLIED 时必填，PENDING 时必须为空，存在时不得早于受理时间，重放保留原时间。
 * @param <T>           命令结果的公开视图类型，无结果的命令使用 Void。
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
