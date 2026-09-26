package com.arte.ai.mapper.tool;

import com.arte.ai.pojo.tool.po.ToolBindingPo;
import com.arte.core.annotations.MybatisParams;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@MybatisParams(value = "arte_ai_tool_binding", queryFields = {})
public interface ToolBindingMapper extends BaseMapper<ToolBindingPo> {
    Optional<ToolBindingPo> selectEffective(@Param("ownerId") String ownerId,
                                            @Param("bindingId") String bindingId);

    List<ToolBindingPo> selectEnabledByOwnerId(@Param("ownerId") String ownerId);

    int updateWithVersion(@Param("binding") ToolBindingPo binding,
                          @Param("expectedVersion") Long expectedVersion);
}
