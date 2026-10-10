package com.arte.app.platform.persistence.mapper;

import com.arte.app.pojo.platform.PlatformTenantPolicyDto;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

/**
 * 平台专用物理库 Mapper。完整范围、当前读和 CAS 由专用方法明确表达。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 16:28 ✾
 */
public interface PlatformTenantPolicyMapper extends BaseMapper<PlatformTenantPolicyDto> {
    PlatformTenantPolicyDto selectCurrent(@Param("tenantId") String tenantId);

    int replaceCas(@Param("row") PlatformTenantPolicyDto row, @Param("expected") long expected);
}
