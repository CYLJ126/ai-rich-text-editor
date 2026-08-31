package com.arte.app.web.controller.rbac;

import com.arte.core.i18n.MessageUtils;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import com.arte.app.api.rbac.RoleService;
import com.arte.app.api.rbac.UserService;
import com.arte.app.pojo.rbac.RoleDto;
import com.arte.app.pojo.rbac.UserDto;
import com.arte.app.pojo.rbac.param.RoleParam;
import com.arte.app.pojo.rbac.param.UserParam;
import com.arte.core.pojo.PageView;
import com.arte.core.pojo.ResultContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/**
 * <p>
 * 角色表 前端控制器
 * </p>
 *
 * @author zhangsc
 * @since 2026-03-06
 */
@Slf4j
@RestController
@RequestMapping("/rbac/role")
public class RoleController {

    @Resource
    private RoleService roleService;

    @Resource
    private UserService userService;

    @PostMapping("/addRole")
    @PreAuthorize("@pcs.check('role:add')")
    public ResultContext<Boolean> addRole(@RequestBody RoleDto param) {
        return ResultContext.wrap(param, roleService::addRole);
    }

    @PostMapping("/updateRole")
    @PreAuthorize("@pcs.check('role:update')")
    public ResultContext<Boolean> updateRole(@RequestBody RoleDto param) {
        Assert.notNull(param.getId(), MessageUtils.get("error.field.roleIdRequired"));
        return ResultContext.wrap(param, roleService::updateRole);
    }

    @PostMapping("/deactivateRole")
    @PreAuthorize("@pcs.check('role:delete')")
    public ResultContext<Boolean> deactivateRole(@RequestBody RoleParam param) {
        Assert.notNull(param.getId(), MessageUtils.get("error.field.roleIdRequired"));
        Assert.notBlank(param.getRoleCode(), MessageUtils.get("error.field.roleCodeRequired"));
        return ResultContext.wrap(param, roleService::deactivateRole);
    }

    @PostMapping("/getRole")
    @PreAuthorize("@pcs.check('role:list')")
    public ResultContext<RoleDto> getRole(@RequestBody RoleParam param) {
        return ResultContext.wrap(param, roleService::getRole);
    }

    @PostMapping("/getRoleByCode")
    @PreAuthorize("@pcs.check('role:list')")
    public ResultContext<RoleDto> getRoleByCode(@RequestBody RoleParam param) {
        return ResultContext.wrap(param, roleService::getRole);
    }

    @PostMapping("/listRole")
    @PreAuthorize("@pcs.check('role:list')")
    public PageView<RoleDto> listRole(@RequestBody RoleParam param) {
        return roleService.selectRoles(param);
    }

    @PostMapping("/assignRoleToUsers")
    @PreAuthorize("@pcs.check('role:update')")
    public ResultContext<Boolean> assignRoleToUsers(@RequestBody RoleParam param) {
        Assert.notBlank(param.getRoleCode(), MessageUtils.get("error.field.roleCodeRequired"));
        return ResultContext.wrap(param, userService::assignRoleToUsers);
    }

    @PostMapping("/assignMenusToRole")
    @PreAuthorize("@pcs.check('role:update')")
    public ResultContext<Boolean> assignMenusToRole(@RequestBody RoleParam param) {
        Assert.notBlank(param.getRoleCode(), MessageUtils.get("error.field.roleCodeRequired"));
        return ResultContext.wrap(param, roleService::assignMenusToRole);
    }

    @PostMapping("/assignOperationsToRole")
    @PreAuthorize("@pcs.check('role:update')")
    public ResultContext<Boolean> assignOperationsToRole(@RequestBody RoleParam param) {
        Assert.notBlank(param.getRoleCode(), MessageUtils.get("error.field.roleCodeRequired"));
        return ResultContext.wrap(param, roleService::assignOperationsToRole);
    }

    @PostMapping("/listRolesByUser")
    @PreAuthorize("@pcs.check('role:list')")
    public PageView<RoleDto> listRolesByUser(@RequestBody RoleParam param) {
        String userName = Optional.ofNullable(param.getUserNames())
                .filter(list -> !list.isEmpty())
                .map(List::getFirst)
                .orElse(null);
        Assert.notBlank(userName, MessageUtils.get("error.field.userNameRequired"));
        List<RoleDto> allRoles = roleService.list();
        UserDto user = userService.getUser(new UserParam().setUserName(userName));
        if (CollUtil.isNotEmpty(user.getRoles())) {
            allRoles.forEach(role -> {
                if (user.getRoles().contains(role.getRoleCode())) {
                    role.setAssigned(true);
                }
            });
        }
        return PageView.success(allRoles);
    }

}
