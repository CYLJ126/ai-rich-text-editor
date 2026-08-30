package com.arte.app.pojo.rbac.param;

import com.arte.app.common.enums.RbacRelationEnum;
import com.arte.app.pojo.BaseParam;
import com.arte.app.pojo.rbac.RbacRelationDto;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/3/9 20:53 ✾
 */
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
public class RbacRelationParam extends BaseParam<RbacRelationDto> implements Serializable {

    @Serial
    private static final long serialVersionUID = -5020055060001356914L;
    /**
     * 源对象
     */
    private String source;

    /**
     * 绑定对象
     */
    private String target;

    /**
     * 绑定类型
     */
    private RbacRelationEnum bindingType;
}
