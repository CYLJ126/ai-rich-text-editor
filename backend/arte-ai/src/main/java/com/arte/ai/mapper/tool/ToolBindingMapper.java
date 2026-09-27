package com.arte.ai.mapper.tool;

import com.arte.ai.pojo.tool.po.ToolBindingPo;
import com.arte.core.annotations.MybatisParams;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@MybatisParams(value = "arte_ai_tool_binding", queryFields = {})
public interface ToolBindingMapper extends BaseMapper<ToolBindingPo> {
    Optional<ToolBindingPo> selectOwned(@Param("ownerId") String ownerId,
                                        @Param("bindingId") String bindingId);

    Optional<ToolBindingPo> selectByScope(@Param("ownerId") String ownerId,
                                          @Param("workspaceId") String workspaceId,
                                          @Param("toolId") String toolId,
                                          @Param("toolVersion") String toolVersion);

    List<ToolBindingPo> selectEnabledByScope(@Param("ownerId") String ownerId,
                                             @Param("workspaceId") String workspaceId);

    List<ToolBindingPo> selectByOwnerScope(@Param("ownerId") String ownerId,
                                           @Param("workspaceId") String workspaceId);

    int updateWithVersion(@Param("binding") ToolBindingPo binding,
                          @Param("expectedVersion") Long expectedVersion);
}
