package com.arte.app.pojo.rbac;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.arte.app.pojo.BaseDto;
import com.arte.core.enums.StatusEnum;
import com.arte.core.enums.YesOrNoEnum;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 菜单实体
 *
 * @author zhangsc
 * @since 2025-03-15
 */
@Getter
@Setter
@ToString
@Accessors(chain = true)
@TableName("arte_rbac_menu")
public class MenuPo extends BaseDto implements Serializable {

    @Serial
    private static final long serialVersionUID = -7527480476293933398L;
    /**
     * ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /**
     * 菜单编码
     */
    private String menuCode;

    /**
     * 菜单名称
     */
    private String menuName;

    /**
     * 菜单图标
     */
    private String icon;

    /**
     * 菜单地址
     */
    private String menuUrl;

    /**
     * 上级菜单 ID
     */
    private Integer fatherId;

    /**
     * 顺序
     */
    private Integer orderId;

    /**
     * 状态，参考 StatusEnum，1-启用；3-停用
     */
    private StatusEnum status;

    /**
     * 描述
     */
    private String description;

    /**
     * 是否在菜单树展示：0-否，1-是
     */
    private YesOrNoEnum showFlag;

    /**
     * 版本号
     */
    private Integer rowVersion;

    public static final String COL_ID = "id";

    public static final String COL_MENU_CODE = "menu_code";

    public static final String COL_MENU_NAME = "menu_name";

    public static final String COL_ICON = "icon";

    public static final String COL_MENU_URL = "menu_url";

    public static final String COL_PARENT_ID = "father_id";

    public static final String COL_ORDER_ID = "order_id";

    public static final String COL_STATUS = "status";

    public static final String COL_DESCRIPTION = "description";

    public static final String COL_SHOW_FLAG = "show_flag";

    public static final String COL_ROW_VERSION = "row_version";
}
