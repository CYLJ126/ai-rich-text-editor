package com.nip.app.pojo.rbac;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.nip.core.cache.CacheTypeEnum;
import com.nip.core.cache.CacheTypeInfo;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * <p>
 * 菜单表
 * </p>
 *
 * @author zhangsc
 * @since 2025-03-15
 */
@Getter
@Setter
@ToString
@Accessors(chain = true)
@TableName("nip_rbac_menu")
@CacheTypeInfo(cacheType = CacheTypeEnum.MENU, cacheKeyType = String.class, cacheValueType = MenuDto.class)
public class MenuDto extends MenuPo implements Serializable {

    @Serial
    private static final long serialVersionUID = 441634910466238629L;

    @TableField(exist = false)
    List<MenuDto> children;

    @TableField(exist = false)
    private Boolean assigned;
}
