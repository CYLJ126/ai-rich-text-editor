package com.arte.core.pojo.workspace;

import com.arte.core.utils.platform.PlatformChecks;

import java.util.List;

/**
 * 轻量内部扫描批次；创建键严格递增，exhausted 表示已观察到候选尾部。
 * 边界由调用方直接传递，不保存网络游标或授权许可。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record WorkspaceCandidatePage(List<WorkspaceCandidateKey> records, boolean exhausted) {

    public WorkspaceCandidatePage {
        records = List.copyOf(records);
        PlatformChecks.require(records.size() <= 100, "candidate batch too large");
        PlatformChecks.require(exhausted || !records.isEmpty(), "nonterminal batch must advance");
    }
}
