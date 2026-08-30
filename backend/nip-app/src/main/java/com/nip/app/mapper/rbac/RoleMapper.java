package com.nip.app.mapper.rbac;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nip.app.pojo.rbac.RoleDto;
import com.nip.core.annotations.MybatisParams;

/**
 * <p>
 * 角色表 Mapper 接口
 * </p>
 *
 * @author zhangsc
 * @since 2025-03-15
 */
@MybatisParams(value = "nip_rbac_role", queryFields = {})
public interface RoleMapper extends BaseMapper<RoleDto> {

}

