package com.arte.app.platform.persistence.mapper;

import com.arte.app.pojo.platform.PlatformTenantMemberDto;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 平台专用物理库 Mapper。完整范围、当前读和 CAS 由专用方法明确表达。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 16:28 ✾
 */
public interface PlatformTenantMemberMapper extends BaseMapper<PlatformTenantMemberDto> {
    PlatformTenantMemberDto selectMember(@Param("tenantId") String tenantId, @Param("subject") String subject, @Param("current") boolean current);

    PlatformTenantMemberDto selectScopedId(@Param("tenantId") String tenantId, @Param("id") String id);

    long countMembers(@Param("tenantId") String tenantId);

    List<PlatformTenantMemberDto> pageMembers(@Param("tenantId") String tenantId, @Param("limit") long limit, @Param("offset") long offset);

    int replaceCas(@Param("row") PlatformTenantMemberDto row, @Param("expected") long expected);

    List<PlatformTenantMemberDto> selectTenants(@Param("tenantIds") List<String> tenantIds, @Param("subject") String subject);
}
