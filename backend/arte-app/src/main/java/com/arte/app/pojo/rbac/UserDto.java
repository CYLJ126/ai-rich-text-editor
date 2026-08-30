package com.arte.app.pojo.rbac;

import com.baomidou.mybatisplus.annotation.TableField;
import com.arte.core.cache.CacheTypeEnum;
import com.arte.core.cache.CacheTypeInfo;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
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
@ToString(callSuper = true)
@CacheTypeInfo(cacheType = CacheTypeEnum.USER, cacheKeyType = String.class, cacheValueType = UserDto.class)
public class UserDto extends UserPo implements Serializable {
    @Serial
    private static final long serialVersionUID = -493977304699225981L;

    /**
     * 角色信息
     */
    @TableField(exist = false)
    private Collection<String> roles;
    /**
     * 部门信息
     */
    @TableField(exist = false)
    private Collection<String> departments;
    /**
     * 所属组信息
     */
    @TableField(exist = false)
    private Collection<String> groups;
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
