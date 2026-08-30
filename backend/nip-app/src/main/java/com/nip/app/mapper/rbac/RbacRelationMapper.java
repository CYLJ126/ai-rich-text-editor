package com.nip.app.mapper.rbac;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nip.app.common.enums.RbacRelationEnum;
import com.nip.app.pojo.rbac.MenuDto;
import com.nip.app.pojo.rbac.RbacRelationDto;
import com.nip.app.pojo.rbac.RoleDto;
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
