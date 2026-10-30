package com.arte.app.pojo.platform;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;

/**
 * 公共 ACL 持久化记录；不继承旧业务实体，不参与旧创建人过滤。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 17:50 ✾
 */
@Data
@TableName(value = "arte_platform_resource_grant", autoResultMap = true)
public class PlatformResourceGrantPo implements java.io.Serializable {

    @Serial
    private static final long serialVersionUID = -746377929906659864L;

    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    private String resourceKey;
    private String sourceIdentityDigest;
    private String payload;
    private Long version;
}
