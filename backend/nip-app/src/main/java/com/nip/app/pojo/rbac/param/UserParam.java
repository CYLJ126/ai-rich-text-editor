package com.nip.app.pojo.rbac.param;

import com.nip.app.common.enums.RbacRelationEnum;
import com.nip.app.pojo.BaseParam;
import com.nip.app.pojo.rbac.UserDto;
import com.nip.core.enums.StatusEnum;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/2/7 10:53 ✾
 */
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
public class UserParam extends BaseParam<UserDto> implements Serializable {

    @Serial
    private static final long serialVersionUID = -5020055060001356914L;

    /**
     * 用户名
     */
    private String userName;
    /**
     * 状态，0-初始（未激活）；1-正常；2-注销；
     */
    private StatusEnum status;
    /**
     * 手机号
     */
    private String mobile;
    /**
     * 邮箱
     */
    private String email;

    /**
     * 角色信息
     */
    private List<String> roles;

    /**
     * 菜单信息
     */
    private List<String> menus;

    /**
     * 菜单操作信息
     */
    private List<String> menuOperations;

    private RbacRelationEnum relationType;

    /**
     * 根据绑定目标反查用户列表时用到
     */
    private String target;

    /**
     * 老密码
     */
    private String oldPassword;

    /**
     * 新密码
     */
    private String newPassword;
}
