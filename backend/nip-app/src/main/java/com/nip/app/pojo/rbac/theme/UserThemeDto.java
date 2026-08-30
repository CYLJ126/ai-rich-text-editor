package com.nip.app.pojo.rbac.theme;

import com.baomidou.mybatisplus.annotation.TableField;
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
public class UserThemeDto extends UserThemePo implements Serializable {
    @Serial
    private static final long serialVersionUID = -2844203644832573700L;

    /**
     * 主题颜色配置（JSON格式）
     */
    @TableField(exist = false)
    private ThemeColorsDto themeColors;
}
