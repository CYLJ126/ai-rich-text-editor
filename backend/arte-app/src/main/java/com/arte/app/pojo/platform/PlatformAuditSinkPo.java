package com.arte.app.pojo.platform;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 平台操作审计实体
 * <p>
 * 记录实际操作者、操作范围及关联命令。字段仅对应审计表，不继承旧 BaseDto。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 16:28 ✾
 */
@Data
@TableName(value = "arte_platform_audit", autoResultMap = true)
public class PlatformAuditSinkPo implements Serializable {

    @Serial
    private static final long serialVersionUID = 6626683218689616650L;

    /**
     * 审计记录唯一 ID，由 core 提供，不在持久化时重新生成。
     */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    /**
     * 实际操作者的稳定主体 ID。
     */
    @TableField("actor_subject_id")
    private String actorSubjectId;

    /**
     * 操作租户范围；无租户范围时为空。
     */
    @TableField("tenant_id")
    private String tenantId;

    /**
     * 操作空间范围；空间外命令为空。
     */
    @TableField("workspace_id")
    private String workspaceId;

    /**
     * 审计动作标识。
     */
    @TableField("action")
    private String action;

    /**
     * 关联命令操作 ID。
     */
    @TableField("operation_id")
    private String operationId;

    /**
     * 操作原因或审计说明。
     */
    @TableField("reason")
    private String reason;

    /**
     * 操作发生时间的 Unix 秒数。
     */
    @TableField("occurred_sec")
    private Long occurredSec;

    /**
     * 秒内纳秒数，与 occurred_sec 配对，保留完整 Instant 精度。
     */
    @TableField("occurred_nano")
    private Integer occurredNano;

    public static final String COL_ID = "id";

    public static final String COL_ACTOR_SUBJECT_ID = "actor_subject_id";
    public static final String COL_TENANT_ID = "tenant_id";
    public static final String COL_WORKSPACE_ID = "workspace_id";
    public static final String COL_ACTION = "action";
    public static final String COL_OPERATION_ID = "operation_id";
    public static final String COL_REASON = "reason";
    public static final String COL_OCCURRED_SEC = "occurred_sec";
    public static final String COL_OCCURRED_NANO = "occurred_nano";
}
