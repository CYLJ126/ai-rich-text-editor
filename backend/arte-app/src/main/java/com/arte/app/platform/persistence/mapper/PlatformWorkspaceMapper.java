package com.arte.app.platform.persistence.mapper;

import com.arte.app.pojo.platform.PlatformWorkspaceDto;
import com.arte.core.pojo.platform.Scope;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 平台专用物理库 Mapper。完整范围、当前读和 CAS 由专用方法明确表达。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 16:28 ✾
 */
public interface PlatformWorkspaceMapper extends BaseMapper<PlatformWorkspaceDto> {
    PlatformWorkspaceDto selectScoped(@Param("scope") Scope scope, @Param("current") boolean current);

    int replaceCas(@Param("row") PlatformWorkspaceDto row, @Param("expected") long expected);

    List<PlatformWorkspaceDto> selectScopes(@Param("scopes") List<Scope> scopes);

    List<PlatformWorkspaceDto> scanCandidates(@Param("subject") String subject, @Param("digest") String digest, @Param("key") PlatformWorkspaceDto key, @Param("limit") int limit);
}
