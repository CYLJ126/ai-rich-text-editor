package com.arte.app.service.rbac;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.text.CharSequenceUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.arte.app.api.rbac.RbacRelationService;
import com.arte.app.api.rbac.RoleService;
import com.arte.app.common.enums.RbacRelationEnum;
import com.arte.app.mapper.rbac.RoleMapper;
import com.arte.app.pojo.BaseDto;
import com.arte.app.pojo.rbac.RbacRelationDto;
import com.arte.app.pojo.rbac.RbacRelationPo;
import com.arte.app.pojo.rbac.RoleDto;
import com.arte.app.pojo.rbac.RolePo;
import com.arte.app.pojo.rbac.param.RbacRelationParam;
import com.arte.app.pojo.rbac.param.RoleParam;
import com.arte.core.cache.Cache;
import com.arte.core.cache.CacheableDataSource;
import com.arte.core.enums.StatusEnum;
import com.arte.core.pojo.PageView;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * @author zhangsc
 * @since 2026/3/6 13:39
 */
@Slf4j
@Service
public class RoleServiceImpl extends ServiceImpl<RoleMapper, RoleDto> implements RoleService {

    @Resource
    private RbacRelationService rbacRelationService;

    @Resource(name = "roleCache")
    @Lazy
    private CacheableDataSource<String, RoleDto> cacheableDataSource;

    @Resource(name = "roleCache")
    @Lazy
    private Cache<String, RoleDto> roleCache;

    @Override
    public PageView<RoleDto> selectRoles(RoleParam param) {
        return this.page(param, getQueryWrapper(param));
    }

    @Override
    public RoleDto getRole(RoleParam param) {
        RoleDto role = this.getOne(getQueryWrapper(param));
        if (role == null) {
            return null;
        }
        role.setMenuOperations(rbacRelationService.listOperationsBySource(new RbacRelationParam()
                .setBindingType(RbacRelationEnum.ROLE_TO_OPERATION).setSource(role.getRoleCode())).stream().map(RbacRelationDto::getTarget).toList());
        role.setMenus(rbacRelationService.listBySource(role.getRoleCode(), RbacRelationEnum.ROLE_TO_MENU).stream().map(RbacRelationDto::getTarget).toList());
        roleCache.put(role.getRoleCode(), role);
        return role;
    }

    @Override
    public RoleDto getRoleByCode(String roleCode) {
        return cacheableDataSource.get(roleCode, (key) -> getRole(new RoleParam().setRoleCode(roleCode)));
    }

    @Override
    public Boolean addRole(RoleDto param) {
        if (roleCache.exists(param.getRoleCode())) {
            log.info("角色已存在，角色编码：{}", param.getRoleCode());
            return false;
        }
        boolean result = this.save(param);
        if (result) {
            roleCache.put(param.getRoleCode(), param);
        }
        return result;
    }

    @Override
    public Boolean updateRole(RoleDto param) {
        boolean updated = this.updateById(param);
        if (updated) {
            RbacRelationParam rbacRelationParam = new RbacRelationParam()
                    .setBindingType(RbacRelationEnum.ROLE_TO_OPERATION).setSource(param.getRoleCode());
            List<RbacRelationDto> menuOperations = rbacRelationService.listOperationsBySource(rbacRelationParam);
            if (CollUtil.isNotEmpty(menuOperations)) {
                param.setMenuOperations(menuOperations.stream().map(RbacRelationDto::getTarget).toList());
            }
            roleCache.put(param.getRoleCode(), param);
        }
        return updated;
    }

    @Override
    public Boolean deactivateRole(RoleParam param) {
        UpdateWrapper<RoleDto> wrapper = new UpdateWrapper<>();
        wrapper.set(RolePo.COL_STATUS, StatusEnum.CLOSED);
        wrapper.eq(RolePo.COL_ROLE_CODE, param.getRoleCode());
        wrapper.eq(RolePo.COL_ID, param.getId());
        boolean result = this.update(wrapper);
        if (result) {
            roleCache.evict(param.getRoleCode());
        }
        return result;
    }

    @Override
    public Collection<String> getAuthorities(RoleParam param) {
        RoleDto role = getRoleByCode(param.getRoleCode());
        if (role == null || StatusEnum.CLOSED.equals(role.getStatus())) {
            return Collections.emptySet();
        }
        Set<String> menuOperations = new HashSet<>(role.getMenuOperations());
        if (CollUtil.isEmpty(menuOperations)) {
            RbacRelationParam rbacRelationParam = new RbacRelationParam();
            rbacRelationParam.setBindingType(RbacRelationEnum.ROLE_TO_OPERATION).setSource(param.getRoleCode());
            menuOperations.addAll(rbacRelationService.listOperationsBySource(rbacRelationParam).stream().map(RbacRelationDto::getTarget).toList());
            role.setMenuOperations(menuOperations);
            roleCache.put(param.getRoleCode(), role);
        }
        return menuOperations;
    }

    @Override
    public Boolean assignOperationsToRole(RoleParam param) {
        Boolean bindResult = rbacRelationService.bind(param.getRoleCode(), param.getMenuOperations(), RbacRelationEnum.ROLE_TO_OPERATION);
        if (bindResult) {
            RoleDto tempRole = getRoleByCode(param.getRoleCode());
            tempRole.setMenuOperations(param.getMenuOperations());
            roleCache.put(param.getRoleCode(), tempRole);
        }
        return bindResult;
    }

    @Override
    public void cancelOperationsToRole(Collection<String> roleCodes, Collection<String> operationCodes) {
        if (CollUtil.isEmpty(operationCodes)) {
            log.info("操作代码不能为空！");
            return;
        }
        QueryWrapper<RbacRelationDto> queryWrapper = new QueryWrapper<>();
        queryWrapper.in(CollUtil.isNotEmpty(roleCodes), RbacRelationPo.COL_SOURCE, roleCodes);
        queryWrapper.in(RbacRelationPo.COL_TARGET, operationCodes);
        queryWrapper.eq(RbacRelationPo.COL_BINDING_TYPE, RbacRelationEnum.ROLE_TO_OPERATION);
        List<RbacRelationDto> relations = rbacRelationService.list(queryWrapper);
        boolean result = rbacRelationService.removeBatchByIds(relations);
        if (result) {
            Map<String, RoleDto> roleMap = new HashMap<>();
            relations.stream().map(RbacRelationDto::getSource).forEach(roleCode -> {
                RoleDto tempRole = getRoleByCode(roleCode);
                if (tempRole != null) {
                    tempRole.setMenuOperations(CollUtil.subtract(tempRole.getMenuOperations(), operationCodes));
                    roleMap.put(roleCode, tempRole);
                }
            });
            roleCache.multiPut(roleMap);
        }
    }

    @Override
    public Boolean assignMenusToRole(RoleParam roleParam) {
        Boolean bindResult = rbacRelationService.bind(roleParam.getRoleCode(), roleParam.getMenus(), RbacRelationEnum.ROLE_TO_MENU);
        if (bindResult) {
            RoleDto tempRole = this.getRoleByCode(roleParam.getRoleCode());
            tempRole.setMenus(roleParam.getMenus());
            roleCache.put(roleParam.getRoleCode(), tempRole);
        }
        return bindResult;
    }

    private static QueryWrapper<RoleDto> getQueryWrapper(RoleParam roleParam) {
        QueryWrapper<RoleDto> wrapper = new QueryWrapper<>();
        wrapper.eq(Objects.nonNull(roleParam.getId()), "id", roleParam.getId());
        wrapper.eq(CharSequenceUtil.isNotBlank(roleParam.getRoleCode()), RolePo.COL_ROLE_CODE, roleParam.getRoleCode());
        wrapper.eq(CharSequenceUtil.isNotBlank(roleParam.getRoleName()), RolePo.COL_ROLE_NAME, roleParam.getRoleName());
        wrapper.eq(Objects.nonNull(roleParam.getStatus()), RolePo.COL_STATUS, roleParam.getStatus());
        wrapper.eq(CharSequenceUtil.isNotBlank(roleParam.getCreateBy()), BaseDto.COL_CREATE_BY, roleParam.getCreateBy());
        wrapper.ge(Objects.nonNull(roleParam.getCreateTimeFloor()), BaseDto.COL_CREATE_TIME, roleParam.getCreateTimeFloor());
        wrapper.le(Objects.nonNull(roleParam.getCreateTimeCeil()), BaseDto.COL_CREATE_TIME, roleParam.getCreateTimeCeil());
        return wrapper;
    }
}
