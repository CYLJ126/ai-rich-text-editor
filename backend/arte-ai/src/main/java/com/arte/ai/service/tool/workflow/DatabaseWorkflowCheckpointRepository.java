package com.arte.ai.service.tool.workflow;

import com.arte.ai.api.tool.workflow.WorkflowCheckpointRepository;
import com.arte.ai.mapper.tool.WorkflowCheckpointMapper;
import com.arte.ai.pojo.tool.WorkflowCheckpoint;
import com.arte.ai.pojo.tool.po.WorkflowCheckpointPo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.ZoneId;
import java.util.Optional;

/**
 * MySQL 工作流检查点仓库。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
@Repository
@RequiredArgsConstructor
public class DatabaseWorkflowCheckpointRepository implements WorkflowCheckpointRepository {

    private final WorkflowCheckpointMapper mapper;

    @Override
    public void save(WorkflowCheckpoint checkpoint) {
        WorkflowCheckpointPo po = new WorkflowCheckpointPo().setCheckpointId(checkpoint.checkpointId())
                .setRunId(checkpoint.runId()).setSequence(checkpoint.sequence()).setState(checkpoint.state());
        mapper.insert(po);
    }

    @Override
    public Optional<WorkflowCheckpoint> findLatest(String runId) {
        return mapper.selectLatest(runId).map(po -> new WorkflowCheckpoint(po.getCheckpointId(),
                po.getRunId(), po.getSequence(), po.getState(),
                po.getCreateTime().atZone(ZoneId.systemDefault()).toInstant()));
    }
}
