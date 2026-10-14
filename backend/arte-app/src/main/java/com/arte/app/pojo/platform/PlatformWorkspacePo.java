package com.arte.app.pojo.platform;

import com.arte.core.enums.workspace.WorkspaceStateEnum;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 平台工作空间：资源归属、协作和空间策略的边界。
 * 数据库实体，不继承旧 BaseDto；不承担授权或对外接口职责。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 16:28 ✾
 */
@Data
@TableName(value = "arte_platform_workspace", autoResultMap = true)
public class PlatformWorkspacePo implements java.io.Serializable {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /**
     * 所属租户 ID。
     */
    @TableField(value = "tenant_id")
    private String tenantId;

    /**
     * 工作空间唯一 ID。
     */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    /**
     * 工作空间显示名称。
     */
    @TableField(value = "name")
    private String name;

    /**
     * 工作空间描述。
     */
    @TableField(value = "description")
    private String description;

    /**
     * 工作空间状态：0 正常，1 已归档，2 关闭处理中，3 已软删除。
     */
    @TableField(value = "state")
    private WorkspaceStateEnum state;

    /**
     * 创建人稳定主体 ID，仅用于审计，不代表资源归属或权限。
     */
    @TableField(value = "created_by")
    private String createdBy;

    /**
     * 乐观锁版本号，非负；更新时用于版本比较。
     */
    @TableField(value = "version")
    private Long version;

    /**
     * 空间授权变更代次，非负；用于使旧授权缓存失效。
     */
    @TableField(value = "auth_epoch")
    private Long authEpoch;

    /**
     * 不可变创建时间，明确按 UTC 映射的 DATETIME(6)，精度为微秒。
     */
    @TableField(value = "created_at")
    private LocalDateTime createdAt;

    /**
     * 非递减更新时间，明确按 UTC 映射的 DATETIME(6)，允许同一微秒内版本递增。
     */
    @TableField(value = "updated_at")
    private LocalDateTime updatedAt;
}
