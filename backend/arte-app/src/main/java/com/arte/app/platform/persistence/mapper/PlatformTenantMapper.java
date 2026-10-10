package com.arte.app.platform.persistence.mapper;

import com.arte.app.pojo.platform.PlatformTenantDto;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

/**
 * 平台专用物理库 Mapper。完整范围、当前读和 CAS 由专用方法明确表达。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 16:28 ✾
 */
public interface PlatformTenantMapper extends BaseMapper<PlatformTenantDto> {
    PlatformTenantDto selectPersonal(@Param("subject") String subject, @Param("current") boolean current);

    PlatformTenantDto selectCurrent(@Param("id") String id);

    int replaceCas(@Param("row") PlatformTenantDto row, @Param("expected") long expected);
}
