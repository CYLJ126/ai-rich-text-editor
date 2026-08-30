package com.nip.app.api.rbac;

import com.baomidou.mybatisplus.extension.service.IService;
import com.nip.app.common.enums.RbacRelationEnum;
import com.nip.app.pojo.rbac.MenuDto;
import com.nip.app.pojo.rbac.RbacRelationDto;
import com.nip.app.pojo.rbac.RoleDto;
import com.nip.app.pojo.rbac.param.RbacRelationParam;

import java.util.List;

/**
 * <p>
 * RBAC 关系表 服务类
 * </p>
 *
 * @author zhangsc
 * @since 2025-03-13
 */
public interface RbacRelationService extends IService<RbacRelationDto> {

    List<RoleDto> listRolesByUserName(String userName);

    List<MenuDto> listMenusByUserName(String userName);

    List<RbacRelationDto> listOperationsBySource(RbacRelationParam param);

    Boolean bind(String source, List<String> targets, RbacRelationEnum type);

    Boolean deleteBySource(String source, RbacRelationEnum type);

    List<RbacRelationDto> listBySource(String source, RbacRelationEnum type);

    List<RbacRelationDto> listByTarget(String target, RbacRelationEnum type);
}
