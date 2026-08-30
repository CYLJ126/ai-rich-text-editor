package com.nip.app.pojo.rbac.theme;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.nip.app.pojo.BaseDto;
import com.nip.core.enums.StatusEnum;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 用户主题配置实体类
 *
 * @author zhangsc
 * @since 2026/4/13 20:39
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("nip_rbac_user_theme")
public class UserThemePo extends BaseDto implements Serializable {

    @Serial
    private static final long serialVersionUID = -5535641320636281126L;
    /**
     * 主键ID
     */
    @TableId
    private Integer id;

    /**
     * 用户ID
     */
    @TableField("user_name")
    private String userName;

    /**
     * 主题ID
     */
    @TableField("theme_id")
    private String themeId;

    /**
     * 主题名称
     */
    @TableField("theme_name")
    private String themeName;

    /**
     * 是否为暗色模式
     */
    @TableField("is_dark")
    private Boolean isDark;

    /**
     * 状态，0-初始（未激活）；1-正常；2-注销；
     */
    @TableField("status")
    private StatusEnum status;
}