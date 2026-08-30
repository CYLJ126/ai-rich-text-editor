package com.nip.app.pojo.rbac.param;

import com.nip.app.pojo.BaseParam;
import com.nip.app.pojo.rbac.RoleDto;
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
public class RoleParam extends BaseParam<RoleDto> implements Serializable {

    @Serial
    private static final long serialVersionUID = -5020055060001356914L;

    /**
     * 角色编码
     */
    private String roleCode;
    /**
     * 角色名
     */
    private String roleName;
    /**
     * 状态，0-初始（未激活）；1-正常；2-注销；
     */
    private StatusEnum status;
    /**
     * 用户信息
     */
    private List<String> userNames;
    /**
     * 菜单信息
     */
    private List<String> menus;
    /**
     * 菜单操作信息
     */
    private List<String> menuOperations;
    /**
     * 是否分配角色，true-分配；false-取消分配；
     */
    private Boolean assignOrCancel;
}
