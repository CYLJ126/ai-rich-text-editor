package com.arte.app.pojo.rbac;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.arte.app.pojo.BaseDto;
import com.arte.core.enums.StatusEnum;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.io.Serial;
import java.io.Serializable;

/**
 * 用户实体
 *
 * @author zhangsc
 * @since 2025/1/2 11:39
 */
@EqualsAndHashCode(callSuper = true)
@Data
@ToString(callSuper = true)
@TableName("arte_rbac_user")
public class UserPo extends BaseDto implements Serializable {
    @Serial
    private static final long serialVersionUID = -6862902259526783030L;
    @TableId
    private Integer id;

    /**
     * 用户名
     */
    private String userName;
    /**
     * 状态，0-初始（未激活）；1-正常；2-注销；
     */
    private StatusEnum status;
    /**
     * 手机号
     */
    private String mobile;
    /**
     * 邮箱
     */
    private String email;
    /**
     * 密码
     */
    private String password;
    /**
     * 描述
     */
    private String description;
    /**
     * 行版本号，乐观锁
     */
    private Integer rowVersion;

    public static final String COL_ID = "id";

    public static final String COL_USER_NAME = "user_name";

    public static final String COL_STATUS = "status";

    public static final String COL_MOBILE = "mobile";

    public static final String COL_EMAIL = "email";

    public static final String COL_PASSWORD = "password";

    public static final String COL_DESCRIPTION = "description";

    public static final String COL_ROW_VERSION = "row_version";
}
