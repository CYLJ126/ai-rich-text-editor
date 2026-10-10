package com.arte.app.pojo.platform;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 平台持久化 DTO，沿用项目 DTO 继承 PO 的结构；不作为 core 公共契约。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 16:28 ✾
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "arte_platform_tenant", autoResultMap = true)
public class PlatformTenantDto extends PlatformTenantPo {
    @java.io.Serial
    private static final long serialVersionUID = 1L;
}
