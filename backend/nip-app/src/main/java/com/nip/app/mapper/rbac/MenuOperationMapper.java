package com.nip.app.mapper.rbac;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nip.app.pojo.rbac.MenuOperationDto;
import com.nip.core.annotations.MybatisParams;

/**
 * <p>
 * 菜单操作表 Mapper 接口
 * </p>
 *
 * @author zhangsc
 * @since 2025-03-13
 */
@MybatisParams(value = "nip_rbac_menu", queryFields = {})
public interface MenuOperationMapper extends BaseMapper<MenuOperationDto> {

}
