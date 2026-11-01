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
@TableName(value = "arte_ai_model_resource", autoResultMap = true)
public class PlatformModelResourcePo implements Serializable {

    @Serial
    private static final long serialVersionUID = 564191853803066308L;

    @TableId(type = IdType.INPUT)
    private String resourceKey;
    private String tenantId;
    private String resourceId;
    /**
     * 固定管理空间；租户模型的归属与允许使用空间不能从此列推断。
     */
    private String workspaceId;
    private String payload;
    private Long version;
}
