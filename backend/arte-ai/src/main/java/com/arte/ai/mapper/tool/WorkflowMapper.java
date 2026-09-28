package com.arte.ai.mapper.tool;

import com.arte.ai.pojo.tool.po.WorkflowPo;
import com.arte.core.annotations.MybatisParams;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@MybatisParams(value = "arte_ai_workflow", queryFields = {})
public interface WorkflowMapper extends BaseMapper<WorkflowPo> {
    Optional<WorkflowPo> selectByWorkflowId(@Param("workflowId") String workflowId);

    List<WorkflowPo> selectByOwnerId(@Param("ownerId") String ownerId);

    List<WorkflowPo> selectOwnedPage(@Param("ownerId") String ownerId,
                                     @Param("keyword") String keyword,
                                     @Param("status") String status,
                                     @Param("offset") long offset,
                                     @Param("size") int size);

    long countOwned(@Param("ownerId") String ownerId,
                    @Param("keyword") String keyword,
                    @Param("status") String status);

    Optional<WorkflowPo> selectOwned(@Param("workflowId") String workflowId,
                                     @Param("ownerId") String ownerId);

    int updateLatest(@Param("workflowId") String workflowId, @Param("ownerId") String ownerId,
                     @Param("latestVersion") String latestVersion);

    int updateMetadata(@Param("workflowId") String workflowId, @Param("ownerId") String ownerId,
                       @Param("name") String name, @Param("description") String description);
}
