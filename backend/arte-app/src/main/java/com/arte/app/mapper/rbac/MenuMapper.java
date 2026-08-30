package com.arte.app.mapper.rbac;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.arte.app.pojo.rbac.MenuDto;
import com.arte.core.annotations.MybatisParams;

import java.util.List;


/**
 * <p>
 * 菜单表 Mapper 接口
 * </p>
 *
 * @author zhangsc
 * @since 2025-03-15
 */
@MybatisParams(value = "arte_rbac_menu", queryFields = {})
public interface MenuMapper extends BaseMapper<MenuDto> {

    List<MenuDto> listRecursive(MenuDto param);
}

