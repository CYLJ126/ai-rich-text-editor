package com.arte.app.platform.persistence.mapper;

import com.arte.app.pojo.platform.PlatformResourceAccessDto;
import com.arte.app.pojo.platform.PlatformResourceGrantDto;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 专用平台数据源，SQL 明确当前读、完整范围和 CAS。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 17:50 ✾
 */
public interface PlatformResourceAclMapper extends BaseMapper<PlatformResourceAccessDto> {

    PlatformResourceAccessDto access(@Param("key") String key, @Param("current") boolean current);

    int insertAccess(@Param("row") PlatformResourceAccessDto row);

    int replaceAccess(@Param("row") PlatformResourceAccessDto row, @Param("expected") long expected);

    List<String> currentGrantIds(@Param("key") String key);

    long countGrants(@Param("key") String key);

    List<PlatformResourceGrantDto> grants(@Param("key") String key, @Param("size") long size, @Param("offset") long offset, @Param("current") boolean current);

    PlatformResourceGrantDto grant(@Param("key") String key, @Param("id") String id, @Param("current") boolean current);

    int insertGrant(@Param("row") PlatformResourceGrantDto row);

    int replaceGrant(@Param("row") PlatformResourceGrantDto row, @Param("expected") long expected);
}
