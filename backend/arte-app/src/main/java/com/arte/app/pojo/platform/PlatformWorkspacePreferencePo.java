package com.arte.app.pojo.platform;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

/**
 * 平台用户空间偏好：默认、启动、最近使用及收藏空间。
 * 数据库实体，不继承旧 BaseDto；不承担授权或对外接口职责。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 16:28 ✾
 */
@Data
@TableName(value = "arte_platform_workspace_preference", autoResultMap = true)
public class PlatformWorkspacePreferencePo implements java.io.Serializable {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /**
     * 稳定主体 ID，关联平台身份，不使用可变登录名。
     */
    @TableId(value = "subject_id", type = IdType.INPUT)
    private String subjectId;

    /**
     * 系统初始化的默认个人空间所属租户 ID，用户偏好更新不可修改。
     */
    @TableField(value = "default_tenant_id")
    private String defaultTenantId;

    /**
     * 系统初始化的默认个人空间 ID，用户偏好更新不可修改。
     */
    @TableField(value = "default_workspace_id")
    private String defaultWorkspaceId;

    /**
     * 用户指定启动空间的租户 ID；未指定时为空，与启动空间 ID 同时设置或清空。
     */
    @TableField(value = "startup_tenant_id", updateStrategy = FieldStrategy.ALWAYS)
    private String startupTenantId;

    /**
     * 用户指定启动空间 ID；仅为偏好，使用时重新校验访问资格。
     */
    @TableField(value = "startup_workspace_id", updateStrategy = FieldStrategy.ALWAYS)
    private String startupWorkspaceId;

    /**
     * 最近使用空间的租户 ID；尚无记录时为空，与最近空间 ID 同时设置或清空。
     */
    @TableField(value = "last_tenant_id", updateStrategy = FieldStrategy.ALWAYS)
    private String lastTenantId;

    /**
     * 最近使用空间 ID；仅为偏好，使用时重新校验访问资格。
     */
    @TableField(value = "last_workspace_id", updateStrategy = FieldStrategy.ALWAYS)
    private String lastWorkspaceId;

    /**
     * 收藏空间集合 JSON，固定 scopes 结构，每项包含租户和空间 ID；不授予访问资格。
     */
    @TableField(value = "favorites")
    private String favorites;

    /**
     * 乐观锁版本号，非负；更新时用于版本比较。
     */
    @TableField(value = "version")
    private Long version;
}
