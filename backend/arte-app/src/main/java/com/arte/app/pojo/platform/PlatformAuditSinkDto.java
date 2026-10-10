package com.arte.app.pojo.platform;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.io.Serial;
import java.io.Serializable;

/**
 * 平台操作审计实体
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 16:28 ✾
 */
@EqualsAndHashCode(callSuper = true)
@Data
@ToString(callSuper = true)
@TableName(value = "arte_platform_audit", autoResultMap = true)
public class PlatformAuditSinkDto extends PlatformAuditSinkPo implements Serializable {

    @Serial
    private static final long serialVersionUID = 4711280546147431707L;

}
