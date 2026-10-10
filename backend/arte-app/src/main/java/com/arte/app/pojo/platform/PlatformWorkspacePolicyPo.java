package com.arte.app.pojo.platform;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 平台空间策略：受租户策略上限约束的空间资源行为规则。
 * 数据库实体，不继承旧 BaseDto；不承担授权或对外接口职责。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 16:28 ✾
 */
@Data
@TableName(value = "arte_platform_workspace_policy", autoResultMap = true)
public class PlatformWorkspacePolicyPo implements java.io.Serializable {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /**
     * 所属租户 ID。
     */
    @TableField(value = "tenant_id")
    private String tenantId;

    /**
     * 所属工作空间 ID，与租户 ID 共同确定空间范围。
     */
    @TableField(value = "workspace_id")
    private String workspaceId;

    /**
     * 空间策略规则 JSON，使用固定版本结构；不得突破租户策略上限。
     */
    @TableField(value = "rules")
    private String rules;

    /**
     * 策略 JSON 结构版本，正整数；不支持的版本拒绝处理。
     */
    @TableField(value = "policy_schema_version")
    private Integer policySchemaVersion;

    /**
     * 治理角色预设版本，正整数；不支持的版本拒绝处理。
     */
    @TableField(value = "role_preset_version")
    private Integer rolePresetVersion;

    /**
     * 乐观锁版本号，非负；更新时用于版本比较。
     */
    @TableField(value = "version")
    private Long version;
}
