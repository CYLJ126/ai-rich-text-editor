package com.arte.app.pojo.rbac.theme;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.arte.app.pojo.BaseDto;
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
@TableName("arte_rbac_theme_colors")
public class ThemeColorsPo extends BaseDto implements Serializable {
    @Serial
    private static final long serialVersionUID = -9133984770570282897L;

    /**
     * 颜色值正则表达式（支持 hex 格式）
     */
    private static final String COLOR_PATTERN = "^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{3})$";
    /**
     * 主色
     */
    @TableField("primary_color")
    private String primaryColor;
    /**
     * 主色悬停态
     */
    @TableField("primary_hover")
    private String primaryHover;
    /**
     * 主色激活态
     */
    @TableField("primary_active")
    private String primaryActive;
    /**
     * 主色浅色版
     */
    @TableField("primary_light")
    private String primaryLight;
    /**
     * 主色极浅版
     */
    @TableField("primary_lighter")
    private String primaryLighter;
    /**
     * 辅助色
     */
    @TableField("secondary_color")
    private String secondaryColor;
    /**
     * 辅助色悬停态
     */
    @TableField("secondary_hover")
    private String secondaryHover;
    /**
     * 辅助色激活态
     */
    @TableField("secondary_active")
    private String secondaryActive;
    /**
     * 辅助色浅色版
     */
    @TableField("secondary_light")
    private String secondaryLight;
    /**
     * 辅助色极浅版
     */
    @TableField("secondary_lighter")
    private String secondaryLighter;
}
