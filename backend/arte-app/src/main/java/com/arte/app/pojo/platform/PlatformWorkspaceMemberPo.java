package com.arte.app.pojo.platform;

import com.arte.core.enums.workspace.MembershipStateEnum;
import com.arte.core.enums.workspace.WorkspaceRoleEnum;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 平台空间成员：主体在工作空间内的治理角色和成员资格。
 * 数据库实体，不继承旧 BaseDto；不承担授权或对外接口职责。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 16:28 ✾
 */
@Data
@TableName(value = "arte_platform_workspace_member", autoResultMap = true)
public class PlatformWorkspaceMemberPo implements java.io.Serializable {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /**
     * 空间成员记录唯一 ID。
     */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

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
     * 稳定主体 ID，关联平台身份，不使用可变登录名。
     */
    @TableField(value = "subject_id")
    private String subjectId;

    /**
     * 主体 ID 的 SHA-256 十六进制摘要，仅用于缩短导航索引；鉴权仍核对原主体 ID。
     */
    @TableField(value = "subject_digest")
    private String subjectDigest;

    /**
     * 关联空间创建时间的 Unix 秒数，不可变；用于成员空间列表分页排序。
     */
    @TableField(value = "workspace_created_sec")
    private Long workspaceCreatedSec;

    /**
     * 关联空间创建时间的秒内纳秒数，不可变；与 workspace_created_sec 配对。
     */
    @TableField(value = "workspace_created_nano")
    private Integer workspaceCreatedNano;

    /**
     * 空间治理角色：0 负责人，1 管理员，2 成员，3 访客；不隐式授予私有正文读取权限。
     */
    @TableField(value = "role")
    private WorkspaceRoleEnum role;

    /**
     * 成员状态：0 有效，1 已撤销；撤销后保留成员记录。
     */
    @TableField(value = "state")
    private MembershipStateEnum state;

    /**
     * 成员资格代次，非负；重新加入时递增，防止旧授权恢复生效。
     */
    @TableField(value = "generation")
    private Long generation;

    /**
     * 乐观锁版本号，非负；更新时用于版本比较。
     */
    @TableField(value = "version")
    private Long version;
}
