package com.nip.app.pojo.rbac.theme;

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
public class ThemeConfigDto extends ThemeConfigPo implements Serializable {
    @Serial
    private static final long serialVersionUID = -822544212056439498L;
}
