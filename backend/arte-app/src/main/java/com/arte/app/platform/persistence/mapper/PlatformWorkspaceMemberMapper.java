package com.arte.app.platform.persistence.mapper;

import com.arte.app.pojo.platform.PlatformWorkspaceMemberDto;
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
public interface PlatformWorkspaceMemberMapper extends BaseMapper<PlatformWorkspaceMemberDto> {
    PlatformWorkspaceMemberDto selectMember(@Param("scope") Scope scope, @Param("subject") String subject, @Param("current") boolean current);

    PlatformWorkspaceMemberDto selectScopedId(@Param("scope") Scope scope, @Param("id") String id);

    long countMembers(@Param("scope") Scope scope);

    List<PlatformWorkspaceMemberDto> pageMembers(@Param("scope") Scope scope, @Param("limit") long limit, @Param("offset") long offset);

    int replaceCas(@Param("row") PlatformWorkspaceMemberDto row, @Param("expected") long expected);

    int insertFromWorkspace(@Param("row") PlatformWorkspaceMemberDto row);

    List<PlatformWorkspaceMemberDto> selectScopes(@Param("scopes") List<Scope> scopes, @Param("subject") String subject);
}
