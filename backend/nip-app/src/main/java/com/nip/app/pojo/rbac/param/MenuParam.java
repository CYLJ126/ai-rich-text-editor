package com.nip.app.pojo.rbac.param;

import com.nip.app.pojo.BaseParam;
import com.nip.app.pojo.rbac.MenuDto;
import com.nip.core.enums.StatusEnum;
import com.nip.core.enums.YesOrNoEnum;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/2/7 10:53 ✾
 */
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
public class MenuParam extends BaseParam<MenuDto> implements Serializable {

    @Serial
    private static final long serialVersionUID = -5020055060001356914L;

    /**
     * 菜单编码
     */
    private String menuCode;

    /**
     * 菜单名称
     */
    private String menuName;

    /**
     * 菜单请求地址
     */
    private String menuUrl;

    /**
     * 上级菜单 ID
     */
    private Integer fatherId;

    /**
     * 状态，参考 StatusEnum，1-启用；3-停用
     */
    private StatusEnum status;

    /**
     * 是否在菜单树展示：0-否，1-是
     */
    private YesOrNoEnum showFlag;
}
