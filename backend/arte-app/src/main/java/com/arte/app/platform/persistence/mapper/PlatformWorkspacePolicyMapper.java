package com.arte.app.platform.persistence.mapper;

import com.arte.app.pojo.platform.PlatformWorkspacePolicyDto;
import com.arte.core.pojo.platform.Scope;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 平台专用物理库 Mapper。完整范围、当前读和 CAS 由专用方法明确表达。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 16:28 ✾
 */
public interface PlatformWorkspacePolicyMapper {
    PlatformWorkspacePolicyDto selectByScope(@Param("tenantId") String tenantId, @Param("workspaceId") String workspaceId);

    PlatformWorkspacePolicyDto selectCurrent(@Param("scope") Scope scope);

    List<PlatformWorkspacePolicyDto> selectScopes(@Param("scopes") List<Scope> scopes);

    int insertPolicy(@Param("row") PlatformWorkspacePolicyDto row);

    int replaceCas(@Param("row") PlatformWorkspacePolicyDto row, @Param("expected") long expected);
}
