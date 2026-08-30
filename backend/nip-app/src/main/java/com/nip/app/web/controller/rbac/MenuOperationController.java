package com.nip.app.web.controller.rbac;

import com.nip.app.api.rbac.MenuOperationService;
import com.nip.app.api.rbac.RbacRelationService;
import com.nip.app.pojo.rbac.MenuOperationDto;
import com.nip.app.pojo.rbac.RbacRelationDto;
import com.nip.app.pojo.rbac.RbacRelationPo;
import com.nip.app.pojo.rbac.param.MenuOperationParam;
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
 * 菜单操作表 前端控制器
 * </p>
 *
 * @author zhangsc
 * @since 2025-03-15
 */
@Slf4j
@RestController
@RequestMapping("/rbac/menuOperation")
public class MenuOperationController {

    @Resource
    private MenuOperationService menuOperationService;

    @Resource
    private RbacRelationService rbacRelationService;

    @PostMapping("/listMenuOperations")
    @PreAuthorize("@pcs.check('menuOperation:list')")
    public PageView<MenuOperationDto> listMenuOperations(@RequestBody MenuOperationParam param) {
        return PageView.wrap(param, menuOperationService::selectMenuOperations);
    }

    @PostMapping("/listMenuOperationsBySource")
    @PreAuthorize("@pcs.check('menuOperation:list')")
    public PageView<MenuOperationDto> listMenuOperationsBySource(@RequestBody RbacRelationParam param) {
        List<RbacRelationDto> hasAssigned = rbacRelationService.listOperationsBySource(param);
        List<MenuOperationDto> allOperations = menuOperationService.list();
        if (!hasAssigned.isEmpty()) {
            Set<String> hasAssignedSet = hasAssigned.stream().map(RbacRelationPo::getTarget).collect(Collectors.toSet());
            allOperations.forEach(operation -> {
                operation.setAssigned(hasAssignedSet.contains(operation.getAuthority()));
            });
        }
        return PageView.success(allOperations);
    }

    @PostMapping("/addMenuOperation")
    @PreAuthorize("@pcs.check('menuOperation:add')")
    public ResultContext<Boolean> addMenuOperation(@RequestBody MenuOperationDto param) {
        return ResultContext.wrap(param, menuOperationService::addMenuOperation);
    }

    @PostMapping("/updateMenuOperation")
    @PreAuthorize("@pcs.check('menuOperation:update')")
    public ResultContext<Boolean> updateMenuOperation(@RequestBody MenuOperationDto param) {
        return ResultContext.wrap(param, menuOperationService::updateMenuOperation);
    }

    @PostMapping("/deactivateMenuOperation")
    @PreAuthorize("@pcs.check('menuOperation:update')")
    public ResultContext<Boolean> deactivateMenuOperation(@RequestBody MenuOperationParam param) {
        return ResultContext.wrap(param, menuOperationService::deactivateMenuOperation);
    }
}
