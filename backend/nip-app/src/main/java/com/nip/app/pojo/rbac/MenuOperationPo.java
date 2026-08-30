package com.nip.app.pojo.rbac;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.nip.app.pojo.BaseDto;
import com.nip.core.enums.StatusEnum;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 菜单操作实体
 *
 * @author zhangsc
 * @since 2025-03-13
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("nip_rbac_menu_operation")
public class MenuOperationPo extends BaseDto implements Serializable {
    @Serial
    private static final long serialVersionUID = -7271610890201934753L;
    /**
     * ID
     */
    @TableId(value = "id", type = IdType.AUTO)
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

    /**
     * 描述
     */
    private String description;

    /**
     * 版本号
     */
    @Version
    private Integer rowVersion;


    public static final String COL_ID = "id";

    public static final String COL_MENU_CODE = "menu_code";

    public static final String COL_OPERATION_CODE = "operation_code";

    public static final String COL_OPERATION_NAME = "operation_name";

    public static final String COL_STATUS = "status";

    public static final String COL_DESCRIPTION = "description";

    public static final String COL_ROW_VERSION = "row_version";

    public String getAuthority() {
        return menuCode + ":" + operationCode;
    }

}
