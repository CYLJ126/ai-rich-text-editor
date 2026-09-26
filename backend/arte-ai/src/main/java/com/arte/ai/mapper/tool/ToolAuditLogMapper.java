package com.arte.ai.mapper.tool;

import com.arte.ai.pojo.tool.po.ToolAuditLogPo;
import com.arte.core.annotations.MybatisParams;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@MybatisParams(value = "arte_ai_tool_audit_log", queryFields = {})
public interface ToolAuditLogMapper extends BaseMapper<ToolAuditLogPo> {
    List<ToolAuditLogPo> selectByResource(@Param("ownerId") String ownerId,
                                          @Param("resourceType") String resourceType,
                                          @Param("resourceId") String resourceId,
                                          @Param("limit") int limit);
}
