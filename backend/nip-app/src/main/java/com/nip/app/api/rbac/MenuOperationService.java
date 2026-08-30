package com.nip.app.api.rbac;

import com.baomidou.mybatisplus.extension.service.IService;
import com.nip.app.pojo.rbac.MenuOperationDto;
import com.nip.app.pojo.rbac.param.MenuOperationParam;
import com.nip.core.pojo.PageView;

/**
 * <p>
 * 菜单操作表 服务类
 * </p>
 *
 * @author zhangsc
 * @since 2025-03-13
 */
public interface MenuOperationService extends IService<MenuOperationDto> {

    PageView<MenuOperationDto> selectMenuOperations(MenuOperationParam param);

    MenuOperationDto getMenuOperation(MenuOperationParam param);

    Boolean addMenuOperation(MenuOperationDto param);

    Boolean updateMenuOperation(MenuOperationDto param);

    Boolean deactivateMenuOperation(MenuOperationParam param);
}
