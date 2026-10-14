package com.arte.core.pojo.workspace;

import com.arte.core.utils.platform.PlatformChecks;

import java.time.Duration;

/**
 * 单次空间目录浏览的扫描预算
 * 用于限制候选处理数量、批次数和批次之间的工作时间。
 * <p>
 * 核心服务至少处理首批候选以推进扫描边界，后续批次开始前检查时间预算。
 * 时间预算不取消正在执行的 SQL，也不包含响应式调度等待时间，不是整个 HTTP 请求的硬超时。
 *
 * @param batchSize     每批候选数量上限，范围为 1～100；实际批量还受请求结果条数和剩余候选预算限制
 * @param maxCandidates 单次调用最多处理的候选数量，范围为 1～10000；包括无访问资格和搜索不匹配的候选，
 *                      不包括仅用于判断末尾的额外预读候选，也不代表数据库实际扫描行数
 * @param maxBatches    单次调用最多执行的候选扫描及授权验证批次数，范围为 1～100
 * @param maxWorkTime   基于单调时钟计量的工作时间预算，必须大于零且不超过 30 秒；仅在首批之后的批次开始前检查
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/14 12:35 ✾
 */
public record WorkspaceBrowseBudget(int batchSize, int maxCandidates, int maxBatches, Duration maxWorkTime) {

    public WorkspaceBrowseBudget {
        PlatformChecks.require(batchSize >= 1 && batchSize <= 100 && maxCandidates >= 1 && maxCandidates <= 10000
                && maxBatches >= 1 && maxBatches <= 100 && maxWorkTime != null && !maxWorkTime.isNegative()
                && !maxWorkTime.isZero() && maxWorkTime.compareTo(Duration.ofSeconds(30)) <= 0, "invalid browse budget");
    }

    /**
     * 返回默认浏览预算。
     *
     * @return 每批最多 100 个候选、单次最多处理 200 个候选、最多 4 批、工作时间预算为 1 秒的配置
     */
    public static WorkspaceBrowseBudget defaults() {
        return new WorkspaceBrowseBudget(100, 200, 4, Duration.ofSeconds(1));
    }
}
