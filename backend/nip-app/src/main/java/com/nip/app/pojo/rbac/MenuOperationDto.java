package com.nip.app.pojo.rbac;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.nip.core.cache.CacheTypeEnum;
import com.nip.core.cache.CacheTypeInfo;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * <p>
 * 菜单操作表
 * </p>
 *
 * @author zhangsc
 * @since 2025-03-13
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("nip_rbac_menu_operation")
@CacheTypeInfo(cacheType = CacheTypeEnum.MENU_OPERATION, cacheKeyType = String.class, cacheValueType = MenuOperationDto.class)
public class MenuOperationDto extends MenuOperationPo implements Serializable {

    @Serial
    private static final long serialVersionUID = -3640542200907153601L;

    @TableField(exist = false)
    private Boolean assigned;
}
