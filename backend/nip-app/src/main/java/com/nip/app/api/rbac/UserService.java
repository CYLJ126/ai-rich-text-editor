package com.nip.app.api.rbac;


import com.baomidou.mybatisplus.extension.service.IService;
import com.nip.app.pojo.rbac.UserDto;
import com.nip.app.pojo.rbac.param.RoleParam;
import com.nip.app.pojo.rbac.param.UserParam;
import com.nip.core.pojo.PageView;

import java.util.Collection;

/**
 * @author zhangsc
 * @since 2025/1/15 13:40
 */
public interface UserService extends IService<UserDto> {

    Boolean addUser(UserDto user);

    Boolean updateUser(UserDto user);

    UserDto getUser(UserParam user);

    Boolean deactivateUser(UserParam user);

    UserDto getByName(String name);

    PageView<UserDto> selectUsers(UserParam userParam);

    Collection<String> getMenus(UserParam userParam);

    Collection<String> getAuthorities(UserParam user);

    Collection<String> getRoles(UserParam user);

    Collection<String> getDepartments(UserParam user);

    Collection<String> getGroups(UserParam user);

    /**
     * 为当前用户分配若干角色
     *
     * @param user 请求参数
     * @return 是否分配成功
     */
    Boolean assignRolesToUser(UserParam user);

    Boolean assignOperationsToUser(UserParam param);

    /**
     * 为当前用户分配若干菜单
     *
     * @param param 请求参数
     * @return 是否分配成功
     */
    Boolean assignMenusToUser(UserParam param);

    /**
     * 分配当前角色给对应用户
     *
     * @param param 请求参数
     * @return 是否分配成功
     */
    Boolean assignRoleToUsers(RoleParam param);

    /**
     * 操作权限变动时，取消相关用户对应权限
     *
     * @param userNames      用户编码列表
     * @param operationCodes 操作编码列表
     */
    void cancelOperationsToUser(Collection<String> userNames, Collection<String> operationCodes);

    /**
     * 更改密码
     *
     * @param userParam 用户
     * @return 更新成功与否
     */
    Boolean changePassword(UserParam userParam);
}