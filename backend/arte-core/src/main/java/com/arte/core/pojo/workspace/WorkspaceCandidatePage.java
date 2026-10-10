package com.arte.core.pojo.workspace;

import com.arte.core.utils.platform.PlatformChecks;

import java.util.List;

/**
 * 内部目录扫描批次，最多 100 项，按 WorkspaceCandidateKey 严格递增，nextCursor 指向本批最后一个键的后继。
 * 仅是成员关联候选；API 须统一授权过滤、计算可见 total，不直接返回候选计数。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record WorkspaceCandidatePage(
        List<Workspace> records,
        String nextCursor) {
    public WorkspaceCandidatePage {
        records = List.copyOf(records);
        if (nextCursor != null) PlatformChecks.id(nextCursor, "nextCursor");
        PlatformChecks.require(records.size() <= 100, "candidate batch too large");
    }
}
