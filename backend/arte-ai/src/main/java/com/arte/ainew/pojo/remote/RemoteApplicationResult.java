package com.arte.ainew.pojo.remote;

import com.arte.ainew.common.execution.RemoteTaskRef;
import com.arte.ainew.common.reference.ArtifactRef;
import com.arte.ainew.common.reference.SourceRef;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.common.value.StructuredValue;
import com.arte.ainew.pojo.execution.Usage;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * 远端调用结果
 * 已完成输出与远端受理任务分开；结果未知或失败使用执行错误契约，不伪装为普通模型结果。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 17:22 ✾
 */
public sealed interface RemoteApplicationResult extends Serializable permits RemoteApplicationResult.Completed,
        RemoteApplicationResult.Pending {
    record Completed(StructuredValue output, RemoteApplicationRequest.SessionRef session,
                     List<SourceRef> sources, List<ArtifactRef> artifacts,
                     Usage usage) implements RemoteApplicationResult {
        public Completed {
            sources = ContractChecks.list(sources, "sources", 0, ContractChecks.MAX_ITEMS);
            ContractChecks.unique(sources.stream().map(SourceRef::citationId).toList(), "source IDs");
            artifacts = ContractChecks.list(artifacts, "artifacts", 0, ContractChecks.MAX_PARTS);
            ContractChecks.require(output != null || !artifacts.isEmpty(), "Completed remote application requires output");
            Objects.requireNonNull(usage, "usage");
        }
    }

    record Pending(RemoteTaskRef task, RemoteApplicationRequest.SessionRef session) implements RemoteApplicationResult {
        public Pending {
            Objects.requireNonNull(task, "task");
            ContractChecks.require(!task.state().terminal(), "Pending remote task cannot already be terminal");
            ContractChecks.require(session == null || task.connection().equals(session.connection())
                    && task.owner().equals(session.owner()), "Remote task and session belong to different connection or owner");
        }
    }
}
