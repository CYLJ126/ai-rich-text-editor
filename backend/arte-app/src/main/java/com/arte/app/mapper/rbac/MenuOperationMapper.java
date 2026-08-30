package com.arte.app.mapper.rbac;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.arte.app.pojo.rbac.MenuOperationDto;
import com.arte.core.annotations.MybatisParams;

/**
 * <p>
 * 菜单操作表 Mapper 接口
 * </p>
 *
 * @author zhangsc
 * @since 2025-03-13
 */
@MybatisParams(value = "arte_rbac_menu", queryFields = {})
public interface MenuOperationMapper extends BaseMapper<MenuOperationDto> {

}
