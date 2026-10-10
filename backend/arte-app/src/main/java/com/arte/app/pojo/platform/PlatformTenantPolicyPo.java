package com.arte.app.pojo.platform;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 平台租户策略：租户内空间和资源行为的策略上限。
 * 数据库实体，不继承旧 BaseDto；不承担授权或对外接口职责。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 16:28 ✾
 */
@Data
@TableName(value = "arte_platform_tenant_policy", autoResultMap = true)
public class PlatformTenantPolicyPo implements java.io.Serializable {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /**
     * 所属租户 ID。
     */
    @TableId(value = "tenant_id", type = IdType.INPUT)
    private String tenantId;

    /**
     * 租户策略规则 JSON，使用固定版本结构，不包含可执行脚本或 Java 类型名。
     */
    @TableField(value = "rules")
    private String rules;

    /**
     * 是否允许普通租户成员创建工作空间，仍须通过成员资格和基础授权检查。
     */
    @TableField(value = "members_may_create_workspace")
    private Boolean membersMayCreateWorkspace;

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
