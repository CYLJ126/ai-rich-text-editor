package com.arte.core.pojo.workspace;

import com.arte.core.utils.platform.PlatformChecks;

import java.util.List;

/**
 * 工作空间扫描结果
 *
 * @param records      扫描结果
 * @param nextBoundary 下一个边界
 * @param exhausted    所有结果已扫描完毕
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/14 12:35 ✾
 */
public record WorkspaceBrowseResult(List<WorkspaceViews.WorkspaceSummary> records,
                                    WorkspaceCandidateKey nextBoundary,
                                    boolean exhausted) {

    public WorkspaceBrowseResult {
        records = List.copyOf(records);
        PlatformChecks.require(records.size() <= 100 && (exhausted == (nextBoundary == null)), "invalid browse result");
    }
}
