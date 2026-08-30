package com.nip.app.pojo.rbac.param;

import com.nip.app.pojo.BaseParam;
import com.nip.app.pojo.rbac.MenuOperationDto;
import com.nip.core.enums.StatusEnum;
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
public class MenuOperationParam extends BaseParam<MenuOperationDto> implements Serializable {

    @Serial
    private static final long serialVersionUID = -5020055060001356914L;

    private Integer id;

    /**
     * 菜单代码
     */
    private String menuCode;

    /**
     * 菜单操作代码
     */
    private String operationCode;

    /**
     * 菜单操作名称
     */
    private String operationName;

    /**
     * 状态，参考 StatusEnum，1-启用；3-停用
     */
    private StatusEnum status;

    public String getAuthority() {
        return menuCode + ":" + operationCode;
    }
}
