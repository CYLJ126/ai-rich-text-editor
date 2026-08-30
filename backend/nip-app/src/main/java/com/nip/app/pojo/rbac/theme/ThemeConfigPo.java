package com.nip.app.pojo.rbac.theme;

import com.baomidou.mybatisplus.annotation.TableName;
import com.nip.app.pojo.BaseDto;
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
@TableName("nip_rbac_theme_config")
public class ThemeConfigPo extends BaseDto implements Serializable {
    @Serial
    private static final long serialVersionUID = -4864080365509654293L;

    /**
     * 主题ID
     */
    private String id;
    /**
     * 主题名称
     */
    private String name;
    /**
     * 是否为暗色模式
     */
    private Boolean isDark;
    /**
     * 颜色配置
     */
    private ThemeColorsDto colors;
}
