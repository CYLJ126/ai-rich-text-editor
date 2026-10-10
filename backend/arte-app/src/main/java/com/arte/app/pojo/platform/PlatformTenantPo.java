package com.arte.app.pojo.platform;

import com.arte.core.enums.workspace.TenantStateEnum;
import com.arte.core.enums.workspace.TenantTypeEnum;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

/**
 * 平台租户：个人或组织的治理与资源隔离边界。
 * 数据库实体，不继承旧 BaseDto；不承担授权或对外接口职责。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 16:28 ✾
 */
@Data
@TableName(value = "arte_platform_tenant", autoResultMap = true)
public class PlatformTenantPo implements java.io.Serializable {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /**
     * 租户唯一 ID。
     */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    /**
     * 租户显示名称。
     */
    @TableField(value = "name")
    private String name;

    /**
     * 租户类型：0 个人，1 组织。
     */
    @TableField(value = "type")
    private TenantTypeEnum type;

    /**
     * 个人租户关联的稳定主体 ID；组织租户为空，每个主体最多一个个人租户。
     */
    @TableField(value = "personal_subject_id", updateStrategy = FieldStrategy.ALWAYS)
    private String personalSubjectId;

    /**
     * 租户状态：0 正常，1 停用，2 关闭处理中。
     */
    @TableField(value = "state")
    private TenantStateEnum state;

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
     * 租户授权变更代次，非负；用于使旧授权缓存失效。
     */
    @TableField(value = "auth_epoch")
    private Long authEpoch;

    /**
     * 创建时间的 Unix 秒数，与 created_nano 共同保存完整时间精度。
     */
    @TableField(value = "created_sec")
    private Long createdSec;

    /**
     * 创建时间的秒内纳秒数，范围 0 至 999999999。
     */
    @TableField(value = "created_nano")
    private Integer createdNano;

    /**
     * 最后更新时间的 Unix 秒数，与 updated_nano 配对。
     */
    @TableField(value = "updated_sec")
    private Long updatedSec;

    /**
     * 最后更新时间的秒内纳秒数，范围 0 至 999999999。
     */
    @TableField(value = "updated_nano")
    private Integer updatedNano;
}
