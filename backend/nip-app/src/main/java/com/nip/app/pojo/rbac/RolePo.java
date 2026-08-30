package com.nip.app.pojo.rbac;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.nip.app.pojo.BaseDto;
import com.nip.core.enums.StatusEnum;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.io.Serializable;

/**
 * 角色实体
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2025/2/4 17:30 ✾
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TableName("nip_rbac_role")
public class RolePo extends BaseDto implements Serializable {
    @Serial
    private static final long serialVersionUID = 4504625652793619384L;

    @TableId
    private Integer id;
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
     * 描述
     */
    private String description;
    /**
     * 行版本号，乐观锁
     */
    private Integer rowVersion;

    public static final String COL_ID = "id";

    public static final String COL_ROLE_CODE = "role_code";

    public static final String COL_ROLE_NAME = "role_name";

    public static final String COL_STATUS = "status";

    public static final String COL_DESCRIPTION = "description";

    public static final String COL_ROW_VERSION = "row_version";
}
