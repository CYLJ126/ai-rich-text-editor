package com.arte.app.pojo.platform;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 平台 Mapper 的模型 ACL 范围载荷。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 12:00 ✾
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("arte_ai_model_acl_scope")
public class PlatformModelAclScopeDto extends PlatformModelAclScopePo {

    @Serial
    private static final long serialVersionUID = 6622284344336264136L;
}
