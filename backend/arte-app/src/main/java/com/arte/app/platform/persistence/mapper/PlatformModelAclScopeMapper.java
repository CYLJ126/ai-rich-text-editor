package com.arte.app.platform.persistence.mapper;

import com.arte.app.pojo.platform.PlatformModelAclScopeDto;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

/**
 * 真实平台数据源，精确范围、当前读和版本 CAS。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 12:00 ✾
 */
public interface PlatformModelAclScopeMapper extends BaseMapper<PlatformModelAclScopeDto> {

    PlatformModelAclScopeDto scope(@Param("key") String key, @Param("current") boolean current);

    int insertScope(@Param("row") PlatformModelAclScopeDto row);

    int replaceScope(@Param("row") PlatformModelAclScopeDto row, @Param("expected") long expected);
}
