package com.nip.app.web.controller.rbac;

import cn.hutool.core.lang.Assert;
import com.nip.app.api.rbac.MenuService;
import com.nip.app.api.rbac.RbacRelationService;
import com.nip.app.pojo.rbac.MenuDto;
import com.nip.app.pojo.rbac.RbacRelationDto;
import com.nip.app.pojo.rbac.RbacRelationPo;
import com.nip.app.pojo.rbac.param.MenuParam;
import com.nip.app.pojo.rbac.param.RbacRelationParam;
import com.nip.core.pojo.PageView;
import com.nip.core.pojo.ResultContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 菜单表 前端控制器
 * </p>
 *
 * @author zhangsc
 * @since 2025-03-15
 */
@Slf4j
@RestController
@RequestMapping("/rbac/menu")
public class MenuController {

    @Resource
    private MenuService menuService;

    @Resource
    private RbacRelationService rbacRelationService;

    @PostMapping("/listRecursiveMenus")
    @PreAuthorize("@pcs.check('menu:list')")
    public ResultContext<List<MenuDto>> listRecursiveMenus(@RequestBody MenuDto param) {
        // TODO 从 MenuContext 中拿到用户信息，根据其权限过滤后再返回
        return ResultContext.wrap(param, menuService::listRecursive);
    }

    @PostMapping("/addMenu")
    @PreAuthorize("@pcs.check('menu:add')")
    public ResultContext<Boolean> addMenu(@RequestBody MenuDto param) {
        return ResultContext.wrap(param, menuService::addMenu);
    }

    @PostMapping("/updateMenu")
    @PreAuthorize("@pcs.check('menu:update')")
    public ResultContext<Boolean> updateMenu(@RequestBody MenuDto param) {
        Assert.notNull(param.getId(), "菜单 ID 不能为空");
        return ResultContext.wrap(param, menuService::updateMenu);
    }

    @PostMapping("/deactivateMenu")
    @PreAuthorize("@pcs.check('menu:delete')")
    public ResultContext<Boolean> deactivateMenu(@RequestBody MenuParam param) {
        return ResultContext.wrap(param, menuService::deactivateMenu);
    }

    @PostMapping("/getMenu")
    @PreAuthorize("@pcs.check('menu:list')")
    public ResultContext<MenuDto> getMenu(@RequestBody MenuParam param) {
        return ResultContext.wrap(param, menuService::getMenu);
    }

    @PostMapping("/getMenuByCode")
    @PreAuthorize("@pcs.check('menu:list')")
    public ResultContext<MenuDto> getMenuByCode(@RequestBody MenuParam param) {
        return ResultContext.wrap(param, menuService::getMenu);
    }

    @PostMapping("/listMenu")
    @PreAuthorize("@pcs.check('menu:list')")
    public PageView<MenuDto> listMenu(@RequestBody MenuParam param) {
        return menuService.selectMenus(param);
    }

    @PostMapping("/listMenusBySource")
    @PreAuthorize("@pcs.check('menu:list')")
    public PageView<MenuDto> listMenusBySource(@RequestBody RbacRelationParam param) {
        List<RbacRelationDto> hasAssigned = rbacRelationService.listBySource(param.getSource(), param.getBindingType());
        List<MenuDto> allMenus = menuService.list();
        if (!hasAssigned.isEmpty()) {
            Set<String> hasAssignedSet = hasAssigned.stream().map(RbacRelationPo::getTarget).collect(Collectors.toSet());
            allMenus.forEach(menu -> menu.setAssigned(hasAssignedSet.contains(menu.getMenuCode())));
        }
        return PageView.success(allMenus);
    }
}
