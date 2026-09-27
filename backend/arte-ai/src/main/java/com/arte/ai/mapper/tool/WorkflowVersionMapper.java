package com.arte.ai.mapper.tool;

import com.arte.ai.pojo.tool.po.WorkflowVersionPo;
import com.arte.core.annotations.MybatisParams;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@MybatisParams(value = "arte_ai_workflow_version", queryFields = {})
public interface WorkflowVersionMapper extends BaseMapper<WorkflowVersionPo> {
    Optional<WorkflowVersionPo> selectExact(@Param("workflowId") String workflowId,
                                            @Param("version") String version);

    List<WorkflowVersionPo> selectVersions(@Param("workflowId") String workflowId);

    int publish(@Param("id") Long id, @Param("expectedVersion") Long expectedVersion,
                @Param("publishedAt") LocalDateTime publishedAt);

    int publishCompiled(@Param("id") Long id, @Param("expectedVersion") Long expectedVersion,
                        @Param("compiledPlan") Map<String, Object> compiledPlan,
                        @Param("pinnedTools") Map<String, Object> pinnedTools,
                        @Param("entryNodeId") String entryNodeId,
                        @Param("checksum") String checksum,
                        @Param("publishedAt") LocalDateTime publishedAt);
}
