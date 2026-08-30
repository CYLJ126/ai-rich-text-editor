package com.nip.app.web.controller.rbac;

import cn.hutool.core.lang.Assert;
import com.nip.app.api.rbac.RbacRelationService;
import com.nip.app.api.rbac.UserService;
import com.nip.app.pojo.rbac.RbacRelationPo;
import com.nip.app.pojo.rbac.UserDto;
import com.nip.app.pojo.rbac.param.UserParam;
import com.nip.core.pojo.PageView;
import com.nip.core.pojo.ResultContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户相关 Controller
 *
 * @author zhangsc
 * @since 2025/1/2 11:38
 */
@Slf4j
@RestController
@RequestMapping("/rbac/user")
public class UserController {

    @Resource
    private UserService userService;

    @Resource
    private RbacRelationService rbacRelationService;

    @PostMapping("/addUser")
    @PreAuthorize("@pcs.check('user:add')")
    public ResultContext<Boolean> addUser(@RequestBody UserDto param) {
        return ResultContext.wrap(param, userService::addUser);
    }

    @PostMapping("/updateUser")
    @PreAuthorize("@pcs.check('user:update')")
    public ResultContext<Boolean> updateUser(@RequestBody UserDto param) {
        Assert.notNull(param.getId(), "用户 ID 不能为空");
        return ResultContext.wrap(param, userService::updateUser);
    }

    @PostMapping("/deactivateUser")
    @PreAuthorize("@pcs.check('user:delete')")
    public ResultContext<Boolean> deactivateUser(@RequestBody UserParam param) {
        return ResultContext.wrap(param, userService::deactivateUser);
    }

    @PostMapping("/getUserByName")
    @PreAuthorize("@pcs.check('user:list')")
    public ResultContext<UserDto> getUserByName(@RequestBody UserParam param) {
        Assert.notBlank(param.getUserName(), "用户名不能为空");
        return ResultContext.wrap(param.getUserName(), userService::getByName);
    }

    @PostMapping("/listUser")
    @PreAuthorize("@pcs.check('user:list')")
    public PageView<UserDto> listUser(@RequestBody UserParam param) {
        return userService.selectUsers(param);
    }

    @PostMapping("/listUserByTarget")
    @PreAuthorize("@pcs.check('user:list')")
    public PageView<UserDto> listUserByTarget(@RequestBody UserParam param) {
        Assert.notBlank(param.getTarget(), "绑定目标不能为空");
        Assert.notNull(param.getRelationType(), "关系类型不能为空");
        Set<String> existedUsernames = rbacRelationService.listByTarget(param.getTarget(), param.getRelationType())
                .stream().map(RbacRelationPo::getSource).collect(Collectors.toSet());
        PageView<UserDto> pageView = userService.selectUsers(param);
        pageView.getRecords().forEach(user -> {
            if (existedUsernames.contains(user.getUserName())) {
                user.setAssigned(true);
            }
        });
        return pageView;
    }

    @PostMapping("/assignRolesToUser")
    @PreAuthorize("@pcs.check('user:update')")
    public ResultContext<Boolean> assignRolesToUser(@RequestBody UserParam param) {
        Assert.notBlank(param.getUserName(), "用户名不能为空");
        return ResultContext.wrap(param, userService::assignRolesToUser);
    }

    @PostMapping("/assignMenusToUser")
    @PreAuthorize("@pcs.check('user:update')")
    public ResultContext<Boolean> assignMenusToUser(@RequestBody UserParam param) {
        Assert.notBlank(param.getUserName(), "用户名不能为空");
        return ResultContext.wrap(param, userService::assignMenusToUser);
    }

    @PostMapping("/assignOperationsToUser")
    @PreAuthorize("@pcs.check('user:update')")
    public ResultContext<Boolean> assignOperationsToUser(@RequestBody UserParam param) {
        Assert.notBlank(param.getUserName(), "用户名不能为空");
        return ResultContext.wrap(param, userService::assignOperationsToUser);
    }
}
