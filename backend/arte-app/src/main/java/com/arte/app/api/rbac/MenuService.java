package com.arte.app.api.rbac;

import com.baomidou.mybatisplus.extension.service.IService;
import com.arte.app.pojo.rbac.MenuDto;
import com.arte.app.pojo.rbac.param.MenuParam;
import com.arte.core.pojo.PageView;

import java.util.List;

/**
 * <p>
 * 菜单表 服务类
 * </p>
 *
 * @author zhangsc
 * @since 2025-03-15
 */
public interface MenuService extends IService<MenuDto> {

    List<MenuDto> listRecursive(MenuDto param);

    Boolean addMenu(MenuDto param);

    Boolean updateMenu(MenuDto param);

    Boolean deactivateMenu(MenuParam param);

    MenuDto getMenu(MenuParam param);

    MenuDto getMenuByCode(String menuCode);

    PageView<MenuDto> selectMenus(MenuParam param);
}
