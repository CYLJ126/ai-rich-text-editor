package com.arte.app.pojo.platform;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 模型 ACL 迁移范围，不保存执行身份或凭据。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 12:00 ✾
 */
@Data
@TableName("arte_ai_model_acl_scope")
public class PlatformModelAclScopePo implements Serializable {

    @Serial
    private static final long serialVersionUID = 3563695850454271578L;
    /**
     * 租户与空间的长度前缀摘要；读取时仍核对完整范围。
     */
    @TableId(type = IdType.INPUT)
    private String scopeKey;
    /**
     * 精确迁移租户。
     */
    private String tenantId;
    /**
     * 精确迁移空间。
     */
    private String workspaceId;
    /**
     * 清单、单一来源及变更证据的封闭 JSON。
     */
    private String payload;
    /**
     * 原事务 CAS 版本，与清单内版本一致。
     */
    private Long version;
}
