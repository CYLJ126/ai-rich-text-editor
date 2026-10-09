package com.arte.core.pojo.workspace;

import com.arte.core.enums.workspace.ClosureWorkStateEnum;
import com.arte.core.utils.platform.PlatformChecks;

import java.util.List;
import java.util.Objects;

/**
 * 参与者工作项结果；等待证据及失败不能视为 DONE。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record ClosureProgress(
        ClosureWorkStateEnum state,
        List<ClosureBlocker> blockers) {
    public ClosureProgress {
        Objects.requireNonNull(state, "state");
        blockers = List.copyOf(blockers);
        PlatformChecks.require(state != ClosureWorkStateEnum.DONE || blockers.isEmpty(), "completed work cannot have blockers");
    }
}
