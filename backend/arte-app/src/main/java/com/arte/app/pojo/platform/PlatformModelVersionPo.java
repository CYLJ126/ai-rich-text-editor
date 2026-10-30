package com.arte.app.pojo.platform;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 模型管理持久化元数据，不承载执行归属或凭据明文。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 22:40 ✾
 */
@Data
@TableName(value = "arte_ai_model_version", autoResultMap = true)
public class PlatformModelVersionPo implements Serializable {

    @Serial
    private static final long serialVersionUID = -1708953294739365549L;

    @TableId(type = IdType.INPUT)
    private String bindingKey;
    private String resourceKey;
    private String tenantId;
    private String bindingId;
    private String bindingVersion;
    private String snapshotDigest;
    private String payload;
}
