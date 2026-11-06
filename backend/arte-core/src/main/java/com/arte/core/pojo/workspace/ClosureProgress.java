package com.arte.core.pojo.workspace;

import com.arte.core.enums.workspace.ClosureWorkStateEnum;
import com.arte.core.utils.platform.PlatformChecks;

import java.util.List;
import java.util.Objects;

/**
 * 参与者工作项结果；等待证据及失败不能视为 DONE。
 *
 * @param state    关闭参与者报告的工作项状态
 * @param blockers 当前工作项的阻塞项，已完成时为空
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record ClosureProgress(ClosureWorkStateEnum state, List<ClosureBlocker> blockers) {

    public ClosureProgress {
        Objects.requireNonNull(state, "state");
        blockers = List.copyOf(blockers);
        PlatformChecks.require(state != ClosureWorkStateEnum.DONE || blockers.isEmpty(), "completed work cannot have blockers");
    }
}
