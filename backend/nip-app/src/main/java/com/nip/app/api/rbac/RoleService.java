package com.nip.app.api.rbac;

import com.baomidou.mybatisplus.extension.service.IService;
import com.nip.app.pojo.rbac.RoleDto;
import com.nip.app.pojo.rbac.param.RoleParam;
import com.nip.core.pojo.PageView;

import java.util.Collection;

/**
 * <p>
 * 角色表 服务类
 * </p>
 *
 * @author zhangsc
 * @since 2025-03-15
 */
public interface RoleService extends IService<RoleDto> {

    PageView<RoleDto> selectRoles(RoleParam param);

    RoleDto getRole(RoleParam param);

    RoleDto getRoleByCode(String roleCode);

    Boolean addRole(RoleDto param);

    Boolean updateRole(RoleDto param);

    Boolean deactivateRole(RoleParam param);

    Collection<String> getAuthorities(RoleParam param);

    /**
     * 分配当前角色给对应操作
     *
     * @param param 请求参数
     * @return 是否分配成功
     */
    Boolean assignOperationsToRole(RoleParam param);

    /**
     * 操作权限变动时，取消相关角色对应权限
     *
     * @param roleCodes      角色编码列表
     * @param operationCodes 操作编码列表
     */
    void cancelOperationsToRole(Collection<String> roleCodes, Collection<String> operationCodes);

    Boolean assignMenusToRole(RoleParam roleParam);
}
