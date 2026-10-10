package com.arte.app.pojo.platform;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

/**
 * 平台命令回执：命令幂等、防重复执行及结果重放。
 * 数据库实体，不继承旧 BaseDto；不承担授权或对外接口职责。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 16:28 ✾
 */
@Data
@TableName(value = "arte_platform_command", autoResultMap = true)
public class PlatformCommandPo implements java.io.Serializable {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /**
     * 操作唯一 ID，用于关联命令回执、审计和事件。
     */
    @TableId(value = "operation_id", type = IdType.INPUT)
    private String operationId;

    /**
     * 操作者、租户、空间、命令类型及幂等键的 SHA-256 摘要；空范围参与编码。
     */
    @TableField(value = "identity_digest")
    private String identityDigest;

    /**
     * 命令实际操作者的稳定主体 ID。
     */
    @TableField(value = "actor_id")
    private String actorId;

    /**
     * 命令所属租户 ID；无租户范围的命令为空。
     */
    @TableField(value = "tenant_id", updateStrategy = FieldStrategy.ALWAYS)
    private String tenantId;

    /**
     * 命令所属空间 ID；无空间范围的命令为空。
     */
    @TableField(value = "workspace_id", updateStrategy = FieldStrategy.ALWAYS)
    private String workspaceId;

    /**
     * 命令类型标识，与操作者、范围及幂等键共同确定命令身份。
     */
    @TableField(value = "command_type")
    private String commandType;

    /**
     * 请求幂等键，按操作者、范围和命令类型隔离。
     */
    @TableField(value = "operation_key")
    private String operationKey;

    /**
     * 规范化请求摘要，用于拒绝同一幂等键提交不同请求内容。
     */
    @TableField(value = "request_digest")
    private String requestDigest;

    /**
     * 结果结构标识，须为服务端支持的版本化结构，不是 Java 类名。
     */
    @TableField(value = "result_schema")
    private String resultSchema;

    /**
     * 命令回执 JSON，固定结构保存完整命令身份、请求摘要及结果等信息，不含凭据。
     */
    @TableField(value = "receipt")
    private String receipt;

    /**
     * 乐观锁版本号，非负；更新时用于版本比较。
     */
    @TableField(value = "version")
    private Long version;
}
