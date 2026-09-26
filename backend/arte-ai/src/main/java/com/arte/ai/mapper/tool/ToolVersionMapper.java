package com.arte.ai.mapper.tool;

import com.arte.ai.pojo.tool.po.ToolVersionPo;
import com.arte.core.annotations.MybatisParams;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@MybatisParams(value = "arte_ai_tool_version", queryFields = {})
public interface ToolVersionMapper extends BaseMapper<ToolVersionPo> {
    Optional<ToolVersionPo> selectExact(@Param("toolId") String toolId, @Param("version") String version);

    Optional<ToolVersionPo> selectLatestPublished(@Param("toolId") String toolId);

    List<ToolVersionPo> selectVersions(@Param("toolId") String toolId);

    int publish(@Param("id") Long id, @Param("expectedVersion") Long expectedVersion,
                @Param("publishedAt") LocalDateTime publishedAt);
}
