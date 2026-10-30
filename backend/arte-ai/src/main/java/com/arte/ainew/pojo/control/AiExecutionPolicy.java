package com.arte.ainew.pojo.control;

import com.arte.ainew.common.validation.ContractChecks;

import java.time.Duration;
import java.util.Objects;

/**
 * 本次执行的有效限制
 * <p>
 * 与部署上限及受信空间收紧值取交集，不进入原执行快照或账本。
 *
 * @param maxInputBytes     最大输入字节数
 * @param maxOutputTokens   最大模型输出 Token 数
 * @param maxOutputBytes    最大输出字节数
 * @param maximumTimeout    最大单次执行期限
 * @param snapshotRetention 输入快照最大保留时间，不能短于本政策的最大执行期限
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 15:00 ✾
 */
public record AiExecutionPolicy(int maxInputBytes, int maxOutputTokens, long maxOutputBytes,
                                Duration maximumTimeout, Duration snapshotRetention) {

    public AiExecutionPolicy {
        ContractChecks.range(maxInputBytes, "maxInputBytes", 1, 1_000_000);
        ContractChecks.range(maxOutputTokens, "maxOutputTokens", 1, 1_000_000);
        ContractChecks.range(maxOutputBytes, "maxOutputBytes", 1, 32 * 1024 * 1024);
        Objects.requireNonNull(maximumTimeout);
        Objects.requireNonNull(snapshotRetention);
        ContractChecks.require(maximumTimeout.compareTo(Duration.ofSeconds(1)) >= 0
                && maximumTimeout.compareTo(Duration.ofHours(1)) <= 0, "Invalid maximum timeout");
        ContractChecks.require(snapshotRetention.compareTo(maximumTimeout) >= 0
                && snapshotRetention.compareTo(Duration.ofDays(30)) <= 0, "Invalid snapshot retention");
    }

    /**
     * 与上限取交集
     *
     * @param ceiling 上限，来自配置
     * @return 政策配置交集
     */
    public AiExecutionPolicy intersect(AiExecutionPolicy ceiling) {
        return new AiExecutionPolicy(Math.min(maxInputBytes, ceiling.maxInputBytes), Math.min(maxOutputTokens, ceiling.maxOutputTokens),
                Math.min(maxOutputBytes, ceiling.maxOutputBytes), minimum(maximumTimeout, ceiling.maximumTimeout),
                minimum(snapshotRetention, ceiling.snapshotRetention));
    }

    private static Duration minimum(Duration a, Duration b) {
        return a.compareTo(b) < 0 ? a : b;
    }
}
