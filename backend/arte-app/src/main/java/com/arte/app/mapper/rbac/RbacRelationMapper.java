package com.arte.app.mapper.rbac;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.arte.app.common.enums.RbacRelationEnum;
import com.arte.app.pojo.rbac.MenuDto;
import com.arte.app.pojo.rbac.RbacRelationDto;
import com.arte.app.pojo.rbac.RoleDto;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 * 操作权限表 Mapper 接口
 * </p>
 *
 * @author zhangsc
 * @since 2025-03-13
 */
public interface RbacRelationMapper extends BaseMapper<RbacRelationDto> {

    List<RoleDto> listRolesByUserName(@Param("userName") String userName);

    List<RbacRelationDto> listBySourceName(@Param("sourceType") RbacRelationEnum sourceType, @Param("sourceName") String sourceName);

    List<MenuDto> listMenusByUserName(@Param("userName") String userName);
}
