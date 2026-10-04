package com.arte.ainew.pojo.media;

import com.arte.ainew.common.execution.RemoteTaskRef;
import com.arte.ainew.common.reference.ArtifactRef;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.execution.Usage;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * 媒体生成结果
 * <p>
 * 产物完成与异步远端任务受理分开；供应商临时链接需经校验与转存才能成为 Completed 产物。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 22:01 ✾
 */
public sealed interface MediaResult extends Serializable permits MediaResult.Completed, MediaResult.Pending {
    record Completed(List<ArtifactRef> artifacts, Usage usage) implements MediaResult {
        public Completed {
            artifacts = ContractChecks.list(artifacts, "artifacts", 1, ContractChecks.MAX_PARTS);
            Objects.requireNonNull(usage, "usage");
        }
    }

    record Pending(RemoteTaskRef task) implements MediaResult {
        public Pending {
            Objects.requireNonNull(task, "task");
            ContractChecks.require(!task.state().terminal(), "Pending media task cannot already be terminal");
        }
    }
}
