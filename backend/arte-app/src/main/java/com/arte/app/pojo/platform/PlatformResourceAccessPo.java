package com.arte.app.pojo.platform;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 公共 ACL 持久化记录；不继承旧业务实体，不参与旧创建人过滤。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 17:50 ✾
 */
@Data
@TableName(value = "arte_platform_resource_access", autoResultMap = true)
public class PlatformResourceAccessPo implements Serializable {

    @Serial
    private static final long serialVersionUID = 7315622931773551444L;

    @TableId(value = "resource_key", type = IdType.INPUT)
    private String resourceKey;
    private String tenantId;
    private String resourceType;
    private String resourceId;
    private String workspaceId;
    private String payload;
    private Long aclVersion;
}
