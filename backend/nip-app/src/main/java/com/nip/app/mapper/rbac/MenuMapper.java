package com.nip.app.mapper.rbac;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nip.app.pojo.rbac.MenuDto;
import com.nip.core.annotations.MybatisParams;

import java.util.List;


/**
 * <p>
 * 菜单表 Mapper 接口
 * </p>
 *
 * @author zhangsc
 * @since 2025-03-15
 */
@MybatisParams(value = "nip_rbac_menu", queryFields = {})
public interface MenuMapper extends BaseMapper<MenuDto> {

    List<MenuDto> listRecursive(MenuDto param);
}

