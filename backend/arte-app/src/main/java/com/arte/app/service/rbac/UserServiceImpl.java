package com.arte.app.service.rbac;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.text.CharSequenceUtil;
import com.arte.app.api.base.TagService;
import com.arte.app.api.rbac.RbacRelationService;
import com.arte.app.api.rbac.RoleService;
import com.arte.app.api.rbac.UserService;
import com.arte.app.common.enums.RbacRelationEnum;
import com.arte.app.common.enums.TagTypeEnum;
import com.arte.app.config.bean.WebSecurityProperties;
import com.arte.app.mapper.rbac.UserMapper;
import com.arte.app.pojo.BaseDto;
import com.arte.app.pojo.base.TagDto;
import com.arte.app.pojo.rbac.*;
import com.arte.app.pojo.rbac.param.RbacRelationParam;
import com.arte.app.pojo.rbac.param.RoleParam;
import com.arte.app.pojo.rbac.param.UserParam;
import com.arte.core.cache.Cache;
import com.arte.core.cache.CacheableDataSource;
import com.arte.core.enums.StatusEnum;
import com.arte.core.pojo.PageView;
import com.arte.core.pojo.UserContext;
import com.arte.core.pojo.UserOnlineInfo;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * @author zhangsc
 * @since 2025/1/15 13:40
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, UserDto> implements UserService, UserDetailsService {

    @Resource
    private WebSecurityProperties webSecurityProperties;

    @Resource
    private PasswordEncoder bCryptPasswordEncoder;

    @Resource
    private RbacRelationService rbacRelationService;

    @Resource
    private RoleService roleService;

    @Resource
    private TagService tagService;

    @Resource(name = "userCache")
    @Lazy
    private CacheableDataSource<String, UserDto> cacheableDataSource;

    @Resource(name = "userCache")
    @Lazy
    private Cache<String, UserDto> userCache;

    @Override
    public Boolean addUser(UserDto user) {
        // 设置默认密码
        String encodedPassword = bCryptPasswordEncoder.encode(webSecurityProperties.getDefaultPassword());
        user.setPassword(encodedPassword);
        boolean result = this.save(user);
        if (result) {
            // 预设相应参数
            asyncAddPostProcess(user);
        }
        return result;
    }

    @Override
    public Boolean updateUser(UserDto user) {
        // 不更新密码
        user.setPassword(null);
        boolean updated = this.updateById(user);
        if (updated) {
            UserParam userParam = new UserParam().setUserName(user.getUserName());
            user.setMenuOperations(getAuthorities(userParam));
            user.setRoles(getRoles(userParam));
            userCache.put(user.getUserName(), user);
        }
        return updated;
    }

    @Override
    public UserDto getUser(UserParam userParam) {
        UserDto user = this.getOne(getQueryWrapper(userParam));
        List<RbacRelationDto> menuOperations = rbacRelationService.listOperationsBySource(new RbacRelationParam()
                .setSource(userParam.getUserName()).setBindingType(RbacRelationEnum.USER_TO_OPERATION));
        user.setMenuOperations(menuOperations.stream().map(RbacRelationDto::getTarget).toList());
        List<RoleDto> roles = rbacRelationService.listRolesByUserName(userParam.getUserName());
        user.setRoles(new ArrayList<>(roles.stream().map(RoleDto::getRoleCode).toList()));
        List<MenuDto> menus = rbacRelationService.listMenusByUserName(userParam.getUserName());
        user.setMenus(menus.stream().map(MenuDto::getMenuCode).toList());
        return user;
    }

    @Override
    public Boolean deactivateUser(UserParam user) {
        UpdateWrapper<UserDto> wrapper = new UpdateWrapper<>();
        wrapper.set(UserPo.COL_STATUS, StatusEnum.CLOSED);
        wrapper.eq(UserPo.COL_USER_NAME, user.getUserName());
        boolean result = this.update(wrapper);
        if (result) {
            userCache.evict(user.getUserName());
        }
        return result;
    }

    @Override
    public UserDto getByName(String name) {
        return cacheableDataSource.get(name, (key) -> {
            UserParam userParam = new UserParam();
            userParam.setUserName(key);
            return getUser(userParam);
        });
    }

    @Override
    public PageView<UserDto> selectUsers(UserParam param) {
        return this.page(param, getQueryWrapper(param));
    }

    /**
     * 读取当前账号和密码，沿用既有 RBAC 权限服务。
     * 登录期间暂停外层路由事务，避免已绑定的其他库连接覆盖 Mapper 的 app 路由。
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public @NonNull UserDetails loadUserByUsername(@NonNull String username) throws UsernameNotFoundException {
        // 暂停外层路由事务，让 Mapper 切面在获取连接前选择 app 库。
        if (CharSequenceUtil.isBlank(username)) {
            throw new UsernameNotFoundException("Account unavailable");
        }
        var rows = baseMapper.selectLoginByName(username);
        if (rows.size() != 1) {
            throw new UsernameNotFoundException("Account unavailable");
        }
        // 状态、创建记录和密码来自同一次数据库查询，不能从权限缓存中取密码。
        UserDto userDto = rows.getFirst();
        if (userDto.getId() == null || userDto.getId() <= 0 || CharSequenceUtil.isBlank(userDto.getUserName())
                || !StatusEnum.isNormal(userDto.getStatus()) || userDto.getCreateTime() == null
                || CharSequenceUtil.isBlank(userDto.getPassword())) {
            throw new UsernameNotFoundException("Account unavailable");
        }
        var cached = getByName(userDto.getUserName());
        // 数据库记录与缓存记录一致性校验
        if (cached == null || !Objects.equals(cached.getId(), userDto.getId())) {
            throw new UsernameNotFoundException("Account identity changed");
        }
        UserOnlineInfo userOnlineInfo = new UserOnlineInfo();
        UserParam userParam = new UserParam();
        BeanUtil.copyProperties(userDto, userOnlineInfo, "status");
        BeanUtil.copyProperties(userDto, userParam);
        userOnlineInfo.setStatus(userDto.getStatus());
        userOnlineInfo.setAccountCreatedAt(userDto.getCreateTime());
        userOnlineInfo.setRoles(getRoles(userParam));
        userOnlineInfo.setMenus(getMenus(userParam));
        userOnlineInfo.setMenuOperations(getAuthorities(userParam));
        userOnlineInfo.setDepartments(getDepartments(userParam));
        userOnlineInfo.setGroups(getGroups(userParam));
        return new JwtUserDto(userOnlineInfo);
    }

    @Override
    public Collection<String> getMenus(UserParam userParam) {
        UserDto user = getByName(userParam.getUserName());
        if (user == null) {
            return Collections.emptySet();
        }
        if (CollUtil.isNotEmpty(user.getMenus())) {
            return user.getMenus();
        }
        List<MenuDto> menus = rbacRelationService.listMenusByUserName(userParam.getUserName());
        if (CollUtil.isNotEmpty(menus)) {
            List<String> menuCodes = menus.stream().map(MenuDto::getMenuCode).toList();
            user.setMenus(menuCodes);
            userCache.put(userParam.getUserName(), user);
            return menuCodes;
        }
        return Collections.emptySet();
    }

    @Override
    public Collection<String> getAuthorities(UserParam userParam) {
        UserDto user = getByName(userParam.getUserName());
        Set<String> menuOperations = new HashSet<>(user.getMenuOperations());
        if (CollUtil.isNotEmpty(user.getRoles())) {
            // 添加角色权限
            user.getRoles().forEach(role -> {
                RbacRelationParam rbacRelationParam = new RbacRelationParam();
                rbacRelationParam.setSource(role);
                rbacRelationParam.setBindingType(RbacRelationEnum.ROLE_TO_OPERATION);
                menuOperations.addAll(roleService.getAuthorities(new RoleParam().setRoleCode(role)));
            });
        }
        return menuOperations;
    }

    @Override
    public Collection<String> getRoles(UserParam userParam) {
        UserDto user = getByName(userParam.getUserName());
        if (user == null) {
            return Collections.emptySet();
        }
        if (CollUtil.isNotEmpty(user.getRoles())) {
            return user.getRoles();
        }
        Collection<String> roleCodes = user.getRoles();
        if (CollUtil.isEmpty(roleCodes)) {
            List<RoleDto> roles = rbacRelationService.listRolesByUserName(userParam.getUserName());
            if (CollUtil.isNotEmpty(roles)) {
                roleCodes = roles.stream().map(RoleDto::getRoleCode).toList();
                user.setRoles(roleCodes);
                userCache.put(userParam.getUserName(), user);
            }
        }
        return roleCodes;
    }

    @Override
    public Collection<String> getDepartments(UserParam user) {
        return Collections.emptyList();
    }

    @Override
    public Collection<String> getGroups(UserParam user) {
        return Collections.emptyList();
    }

    @Transactional(rollbackFor = Throwable.class)
    @Override
    public Boolean assignRolesToUser(UserParam userParam) {
        String userName = userParam.getUserName();
        UserDto user = getByName(userName);
        if (Objects.isNull(user)) {
            return false;
        }
        boolean result = rbacRelationService.bind(userName, userParam.getRoles(), RbacRelationEnum.USER_TO_ROLE);
        if (result) {
            UserDto newUser = getByName(userName);
            newUser.setRoles(userParam.getRoles());
            userCache.put(newUser.getUserName(), newUser);
        }
        return result;
    }

    @Override
    public Boolean assignOperationsToUser(UserParam param) {
        Boolean bindResult = rbacRelationService.bind(param.getUserName(), param.getMenuOperations(), RbacRelationEnum.USER_TO_OPERATION);
        if (bindResult) {
            UserDto tempUser = this.getByName(param.getUserName());
            tempUser.setMenuOperations(param.getMenuOperations());
            userCache.put(param.getUserName(), tempUser);
        }
        return bindResult;
    }

    @Override
    public Boolean assignMenusToUser(UserParam param) {
        Boolean bindResult = rbacRelationService.bind(param.getUserName(), param.getMenus(), RbacRelationEnum.USER_TO_MENU);
        if (bindResult) {
            UserDto tempUser = this.getByName(param.getUserName());
            tempUser.setMenus(param.getMenus());
            userCache.put(param.getUserName(), tempUser);
        }
        return bindResult;
    }

    @Transactional(rollbackFor = Throwable.class)
    @Override
    public Boolean assignRoleToUsers(RoleParam param) {
        if (param.getAssignOrCancel()) {
            return innerAssignRoleToUsers(param);
        } else {
            return cancelRoleToUsers(param);
        }
    }

    @Override
    public void cancelOperationsToUser(Collection<String> userNames, Collection<String> operationCodes) {
        if (CollUtil.isEmpty(operationCodes)) {
            log.info("操作代码不能为空！");
            return;
        }
        QueryWrapper<RbacRelationDto> queryWrapper = new QueryWrapper<>();
        queryWrapper.in(CollUtil.isNotEmpty(userNames), RbacRelationPo.COL_SOURCE, userNames);
        queryWrapper.eq(RbacRelationPo.COL_BINDING_TYPE, RbacRelationEnum.USER_TO_OPERATION);
        queryWrapper.in(RbacRelationPo.COL_TARGET, operationCodes);
        List<RbacRelationDto> relations = rbacRelationService.list(queryWrapper);
        boolean result = rbacRelationService.removeBatchByIds(relations);
        if (result) {
            Map<String, UserDto> userMap = new HashMap<>();
            relations.stream().map(RbacRelationDto::getSource).forEach(userName -> {
                UserDto tempUser = getByName(userName);
                if (tempUser != null) {
                    tempUser.setMenuOperations(CollUtil.subtract(tempUser.getMenuOperations(), operationCodes));
                    userMap.put(userName, tempUser);
                }
            });
            userCache.multiPut(userMap);
        }
    }

    @Override
    public Boolean changePassword(UserParam userParam) {
        try {
            UpdateWrapper<UserDto> updateWrapper = new UpdateWrapper<>();
            updateWrapper.set(UserPo.COL_PASSWORD, bCryptPasswordEncoder.encode(userParam.getNewPassword()))
                    .eq(UserPo.COL_USER_NAME, userParam.getUserName());
            boolean updated = update(updateWrapper);
            if (updated) {
                userCache.evict(userParam.getUserName());
            }
            return updated;
        } catch (Exception e) {
            log.info("修改密码失败！", e);
            return false;
        }
    }

    private boolean innerAssignRoleToUsers(RoleParam param) {
        List<String> userNames = param.getUserNames();
        List<RbacRelationDto> relations = new ArrayList<>(userNames.size());
        Map<String, UserDto> userMap = new HashMap<>(userNames.size());
        userNames.forEach(userName -> {
            UserDto user = getByName(userName);
            if (Objects.isNull(user)) {
                return;
            }
            Set<String> existsRoles = new HashSet<>(user.getRoles());
            if (!existsRoles.contains(param.getRoleCode())) {
                RbacRelationDto relation = new RbacRelationDto();
                relation.setTarget(param.getRoleCode()).setSource(userName).setBindingType(RbacRelationEnum.USER_TO_ROLE);
                relations.add(relation);
                existsRoles.add(param.getRoleCode());
                user.setRoles(CollUtil.union(existsRoles, user.getRoles()));
                userMap.put(userName, user);
            }
        });
        // 将用户-角色的关系更新到数据库
        boolean result = rbacRelationService.saveBatch(relations);
        if (result) {
            // 更新所有用户缓存
            userCache.multiPut(userMap);
        }
        return result;
    }

    private boolean cancelRoleToUsers(RoleParam param) {
        List<String> userNames = param.getUserNames();
        Set<String> usersToRoleToDelete = new HashSet<>(userNames.size());
        Map<String, UserDto> userMap = new HashMap<>(userNames.size());
        userNames.forEach(userName -> {
            UserDto user = getByName(userName);
            if (Objects.isNull(user)) {
                return;
            }
            Set<String> existsRoles = new HashSet<>(user.getRoles());
            if (existsRoles.contains(param.getRoleCode())) {
                usersToRoleToDelete.add(userName);
                existsRoles.remove(param.getRoleCode());
                user.setRoles(existsRoles);
                userMap.put(userName, user);
            }
        });
        // 将用户-角色的关系从数据库中删除
        QueryWrapper<RbacRelationDto> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(RbacRelationPo.COL_TARGET, param.getRoleCode());
        queryWrapper.in(RbacRelationPo.COL_SOURCE, usersToRoleToDelete);
        queryWrapper.eq(RbacRelationPo.COL_BINDING_TYPE, RbacRelationEnum.USER_TO_ROLE);
        boolean result = rbacRelationService.remove(queryWrapper);
        if (result) {
            // 更新所有用户缓存
            userCache.multiPut(userMap);
        }
        return result;
    }

    private static QueryWrapper<UserDto> getQueryWrapper(UserParam param) {
        QueryWrapper<UserDto> wrapper = new QueryWrapper<>();
        wrapper.eq(Objects.nonNull(param.getId()), UserPo.COL_ID, param.getId());
        wrapper.eq(CharSequenceUtil.isNotBlank(param.getUserName()), UserPo.COL_USER_NAME, param.getUserName());
        wrapper.eq(CharSequenceUtil.isNotBlank(param.getMobile()), UserPo.COL_MOBILE, param.getMobile());
        wrapper.eq(CharSequenceUtil.isNotBlank(param.getEmail()), UserPo.COL_EMAIL, param.getEmail());
        wrapper.eq(Objects.nonNull(param.getStatus()), UserPo.COL_STATUS, param.getStatus());
        wrapper.eq(CharSequenceUtil.isNotBlank(param.getCreateBy()), BaseDto.COL_CREATE_BY, param.getCreateBy());
        wrapper.ge(Objects.nonNull(param.getCreateTimeFloor()), BaseDto.COL_CREATE_TIME, param.getCreateTimeFloor());
        wrapper.le(Objects.nonNull(param.getCreateTimeCeil()), BaseDto.COL_CREATE_TIME, param.getCreateTimeCeil());
        return wrapper;
    }

    /**
     * 1. 添加初始标签列表；
     * 2. 添加菜单操作权限；
     * 3. 刷新用户缓存；
     *
     * @param user 待添加用户
     */
    private void asyncAddPostProcess(UserDto user) {
        CompletableFuture.runAsync(() -> {
            try {
                UserContext.setUserOnlineInfo(new UserOnlineInfo().setId(user.getId()).setUserName(user.getUserName()));
                log.info("为新用户 {} 预设配置", user.getUserName());
                List<TagDto> tagList = new ArrayList<>();
                TagTypeEnum[] tagTypes = TagTypeEnum.values();
                for (int i = 0; i < tagTypes.length; i++) {
                    TagTypeEnum type = tagTypes[i];
                    TagDto tag = new TagDto();
                    tag.setStatus(StatusEnum.DOING).setOrderId(i + 1).setName(type.getDescription());
                    tagList.add(tag);
                }
                // 添加初始化标签
                tagService.saveBatch(tagList);
                // 添加默认菜单操作权限：首页、工具
                List<String> menus = List.of("HomePage", "Tools", "TextFormatter");
                rbacRelationService.bind(user.getUserName(), menus, RbacRelationEnum.USER_TO_MENU);
                List<String> menuOperations = List.of("tag:list", "menu:list", "website:list", "website:refresh");
                rbacRelationService.bind(user.getUserName(), menuOperations, RbacRelationEnum.USER_TO_OPERATION);
                // 刷新用户缓存
                user.setMenus(menus);
                user.setMenuOperations(menuOperations);
            } catch (Exception e) {
                log.error("为新用户 {} 预设配置失败", user.getUserName(), e);
            } finally {
                // 无论预设成功与否，都刷新用户缓存
                userCache.put(user.getUserName(), user);
            }
        });
    }
}
