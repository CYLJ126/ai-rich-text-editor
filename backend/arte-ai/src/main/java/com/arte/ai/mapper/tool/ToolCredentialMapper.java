package com.arte.ai.mapper.tool;

import com.arte.ai.pojo.tool.po.ToolCredentialPo;
import com.arte.core.annotations.MybatisParams;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@MybatisParams(value = "arte_ai_tool_credential", queryFields = {})
public interface ToolCredentialMapper extends BaseMapper<ToolCredentialPo> {
    Optional<ToolCredentialPo> selectActiveByCredentialId(@Param("ownerId") String ownerId,
                                                          @Param("credentialId") String credentialId);
}
