package com.arte.app.platform.persistence.mapper;

import com.arte.app.pojo.platform.PlatformWorkspacePreferenceDto;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

/**
 * 平台专用物理库 Mapper。完整范围、当前读和 CAS 由专用方法明确表达。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 16:28 ✾
 */
public interface PlatformWorkspacePreferenceMapper extends BaseMapper<PlatformWorkspacePreferenceDto> {
    PlatformWorkspacePreferenceDto selectCurrent(@Param("subject") String subject);

    String selectExternalId(@Param("subject") String subject);

    Long lockAccount(@Param("id") long id);

    String lockIdentity(@Param("subject") String subject, @Param("externalId") String externalId);

    int replaceCas(@Param("row") PlatformWorkspacePreferenceDto row, @Param("expected") long expected);
}
