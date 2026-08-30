package com.nip.app.web.controller.richtext;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.nip.app.api.richtext.ShareService;
import com.nip.app.common.enums.ResourceTypeEnum;
import com.nip.app.common.enums.richtext.ArticlePermissionEnum;
import com.nip.app.common.enums.richtext.CatalogPermissionEnum;
import com.nip.app.mapper.rbac.RoleMapper;
import com.nip.app.mapper.rbac.UserMapper;
import com.nip.app.pojo.rbac.RoleDto;
import com.nip.app.pojo.rbac.UserDto;
import com.nip.app.pojo.richtext.ShareDto;
import com.nip.app.pojo.richtext.param.SearchUsersParam;
import com.nip.app.pojo.richtext.param.ShareResourceParam;
import com.nip.app.pojo.richtext.param.ShareTargetParam;
import com.nip.app.pojo.richtext.param.UnshareResourceParam;
import com.nip.app.service.richtext.PermissionValidator;
import com.nip.core.annotations.AnonymousAccess;
import com.nip.core.pojo.ResultContext;
import com.nip.core.pojo.UserContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 分享控制器。
 */
@Slf4j
@RestController
@RequestMapping("/richText/share")
public class ShareController {

    private static final String TARGET_USER = "USER";
    private static final String TARGET_ROLE = "ROLE";

    @Resource
    private ShareService shareService;

    @Resource
    private UserMapper userMapper;

    @Resource
    private RoleMapper roleMapper;

    @Resource
    private PermissionValidator permissionValidator;

    /**
     * 分享资源给指定用户或角色。
     */
    @AnonymousAccess
    @PostMapping("/share")
    public ResultContext<Void> share(@RequestBody ShareResourceParam req) {
        String resourceType = req.getResourceType();
        Integer resourceId = req.getResourceId();
        String targetType = normalizeTargetType(req.getTargetType());
        String permission = req.getPermission();
        String articlePermission = req.getArticlePermission();

        Assert.notBlank(resourceType, "资源类型不能为空");
        Assert.notNull(resourceId, "资源 ID 不能为空");
        Assert.notBlank(permission, "权限不能为空");
        Assert.isTrue(isValidResourceType(resourceType), "资源类型不合法");
        Assert.isTrue(isValidTargetType(targetType), "目标类型不合法");
        Assert.isTrue(isValidPermission(resourceType, permission, articlePermission), "权限不合法");

        permissionValidator.assertCanDelete(resourceType, resourceId);
        if (ResourceTypeEnum.ARTICLE == ResourceTypeEnum.of(resourceType)) {
            articlePermission = null;
        } else if (articlePermission == null) {
            articlePermission = ArticlePermissionEnum.READ.getValue();
        }

        List<String> targets = normalizeTargets(resolveTargets(req, targetType));
        Assert.notEmpty(targets, "分享目标不能为空");
        if (TARGET_USER.equals(targetType)) {
            targets = removeCurrentUser(targets);
            Assert.notEmpty(targets, "目标用户不能为空，且不能包含当前用户");
            assertUsersExist(targets);
        } else {
            assertRolesExist(targets);
        }

        shareService.share(resourceType, resourceId, targetType, targets, permission, articlePermission);
        return ResultContext.success(null);
    }

    /**
     * 取消分享。
     */
    @AnonymousAccess
    @PostMapping("/unshare")
    public ResultContext<Void> unshare(@RequestBody UnshareResourceParam req) {
        String resourceType = req.getResourceType();
        Integer resourceId = req.getResourceId();
        String targetType = normalizeTargetType(req.getTargetType());
        String target = TARGET_ROLE.equals(targetType) ? req.getTargetRole() : req.getTargetUser();

        Assert.notBlank(resourceType, "资源类型不能为空");
        Assert.notNull(resourceId, "资源 ID 不能为空");
        Assert.notBlank(target, "分享目标不能为空");
        Assert.isTrue(isValidResourceType(resourceType), "资源类型不合法");
        Assert.isTrue(isValidTargetType(targetType), "目标类型不合法");

        permissionValidator.assertCanDelete(resourceType, resourceId);
        shareService.unshare(resourceType, resourceId, targetType, target);
        return ResultContext.success(null);
    }

    /**
     * 获取资源的分享列表。
     */
    @AnonymousAccess
    @PostMapping("/listShares")
    public ResultContext<List<ShareDto>> listShares(@RequestBody ShareTargetParam req) {
        String resourceType = req.getResourceType();
        Integer resourceId = req.getResourceId();

        Assert.notBlank(resourceType, "资源类型不能为空");
        Assert.notNull(resourceId, "资源 ID 不能为空");
        Assert.isTrue(isValidResourceType(resourceType), "资源类型不合法");

        permissionValidator.assertCanDelete(resourceType, resourceId);
        return ResultContext.success(shareService.listShares(resourceType, resourceId));
    }

    /**
     * 用户主动退出自己的直接分享。
     */
    @AnonymousAccess
    @PostMapping("/leave")
    public ResultContext<Void> leave(@RequestBody ShareTargetParam req) {
        String resourceType = req.getResourceType();
        Integer resourceId = req.getResourceId();
        String currentUser = UserContext.getUserOnlineInfo().getUserName();

        Assert.notBlank(resourceType, "资源类型不能为空");
        Assert.notNull(resourceId, "资源 ID 不能为空");
        Assert.isTrue(isValidResourceType(resourceType), "资源类型不合法");

        permissionValidator.assertCanRead(resourceType, resourceId);
        shareService.leaveShare(resourceType, resourceId, currentUser);
        return ResultContext.success(null);
    }

    /**
     * 搜索用户，用于分享时选择协作者。
     */
    @AnonymousAccess
    @PostMapping("/searchUsers")
    public ResultContext<List<Map<String, String>>> searchUsers(@RequestBody SearchUsersParam req) {
        String keyword = req.getKeyword();
        String currentUser = UserContext.getUserName();

        QueryWrapper<UserDto> wrapper = new QueryWrapper<>();
        if (!StrUtil.isBlank(keyword)) {
            wrapper.and(w -> w.like("user_name", keyword).or().like("email", keyword));
        }
        wrapper.ne("user_name", currentUser);
        wrapper.last("limit 20");

        List<Map<String, String>> result = userMapper.selectList(wrapper).stream()
                .map(user -> {
                    Map<String, String> item = new HashMap<>();
                    item.put("userName", user.getUserName());
                    item.put("email", user.getEmail());
                    return item;
                })
                .collect(Collectors.toList());
        return ResultContext.success(result);
    }

    /**
     * 搜索角色，用于分享时选择角色。
     */
    @AnonymousAccess
    @PostMapping("/searchRoles")
    public ResultContext<List<Map<String, String>>> searchRoles(@RequestBody SearchUsersParam req) {
        String keyword = req.getKeyword();

        QueryWrapper<RoleDto> wrapper = new QueryWrapper<>();
        if (!StrUtil.isBlank(keyword)) {
            wrapper.and(w -> w.like("role_code", keyword).or().like("role_name", keyword));
        }
        wrapper.last("limit 20");

        List<Map<String, String>> result = roleMapper.selectList(wrapper).stream()
                .map(role -> {
                    Map<String, String> item = new HashMap<>();
                    item.put("roleCode", role.getRoleCode());
                    item.put("roleName", role.getRoleName());
                    return item;
                })
                .collect(Collectors.toList());
        return ResultContext.success(result);
    }

    private String normalizeTargetType(String targetType) {
        return StrUtil.isBlank(targetType) ? TARGET_USER : targetType.trim().toUpperCase();
    }

    private List<String> resolveTargets(ShareResourceParam req, String targetType) {
        return TARGET_ROLE.equals(targetType) ? req.getTargetRoles() : req.getTargetUsers();
    }

    private List<String> normalizeTargets(List<String> targets) {
        if (targets == null) {
            return List.of();
        }
        return targets.stream()
                .filter(StrUtil::isNotBlank)
                .map(String::trim)
                .distinct()
                .collect(Collectors.toList());
    }

    private List<String> removeCurrentUser(List<String> targets) {
        String currentUser = UserContext.getUserName();
        return targets.stream()
                .filter(user -> !currentUser.equals(user))
                .collect(Collectors.toList());
    }

    private void assertUsersExist(List<String> targetUsers) {
        QueryWrapper<UserDto> userWrapper = new QueryWrapper<>();
        userWrapper.in("user_name", targetUsers);
        Set<String> existingUsers = userMapper.selectList(userWrapper).stream()
                .map(UserDto::getUserName)
                .collect(Collectors.toSet());
        Assert.isTrue(existingUsers.containsAll(targetUsers), "目标用户不存在");
    }

    private void assertRolesExist(List<String> targetRoles) {
        QueryWrapper<RoleDto> roleWrapper = new QueryWrapper<>();
        roleWrapper.in("role_code", targetRoles);
        Set<String> existingRoles = roleMapper.selectList(roleWrapper).stream()
                .map(RoleDto::getRoleCode)
                .collect(Collectors.toSet());
        Assert.isTrue(existingRoles.containsAll(targetRoles), "目标角色不存在");
    }

    private boolean isValidResourceType(String resourceType) {
        return ResourceTypeEnum.isValid(resourceType);
    }

    private boolean isValidTargetType(String targetType) {
        return TARGET_USER.equals(targetType) || TARGET_ROLE.equals(targetType);
    }

    private boolean isValidPermission(String resourceType, String permission, String articlePermission) {
        if (ResourceTypeEnum.ARTICLE == ResourceTypeEnum.of(resourceType)) {
            return ArticlePermissionEnum.isValid(permission);
        }
        return CatalogPermissionEnum.isValid(permission)
                && (articlePermission == null || ArticlePermissionEnum.isValid(articlePermission));
    }
}
