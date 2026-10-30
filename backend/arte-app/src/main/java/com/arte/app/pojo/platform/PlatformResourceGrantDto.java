package com.arte.app.pojo.platform;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 公共 ACL 持久化记录；不继承旧业务实体，不参与旧创建人过滤。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 17:50 ✾
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "arte_platform_resource_grant", autoResultMap = true)
public class PlatformResourceGrantDto extends PlatformResourceGrantPo {

    @Serial
    private static final long serialVersionUID = -2114615018093731070L;
}
