package com.nip.app.pojo.rbac;

import com.baomidou.mybatisplus.annotation.TableField;
import com.nip.core.cache.CacheTypeEnum;
import com.nip.core.cache.CacheTypeInfo;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;

/**
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2025/2/4 17:30 ✾
 */
@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@CacheTypeInfo(cacheType = CacheTypeEnum.ROLE, cacheKeyType = String.class, cacheValueType = RoleDto.class)
public class RoleDto extends RolePo implements Serializable {
    @Serial
    private static final long serialVersionUID = -493977304699225981L;

    @TableField(exist = false)
    private Collection<String> userNames;
    /**
     * 菜单信息
     */
    @TableField(exist = false)
    private Collection<String> menus;
    /**
     * 权限信息
     */
    @TableField(exist = false)
    private Collection<String> menuOperations;

    @TableField(exist = false)
    private Boolean assigned;
}
