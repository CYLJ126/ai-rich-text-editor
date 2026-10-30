package com.arte.app.pojo.platform;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 模型管理持久化元数据，不承载执行归属或凭据明文。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 22:40 ✾
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "arte_ai_model_version", autoResultMap = true)
public class PlatformModelVersionDto extends PlatformModelVersionPo {
    @Serial
    private static final long serialVersionUID = -3210389437056662700L;
}
