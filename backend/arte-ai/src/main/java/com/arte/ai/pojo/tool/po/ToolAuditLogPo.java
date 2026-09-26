package com.arte.ai.pojo.tool.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.util.Map;

/**
 * AI 工具审计日志实体
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
@TableName(value = "arte_ai_tool_audit_log", autoResultMap = true)
public class ToolAuditLogPo extends ToolPersistencePo {

    @Serial
    private static final long serialVersionUID = -7483797665428033027L;
    private String auditId;
    private String ownerId;
    private String actorId;
    private String action;
    private String resourceType;
    private String resourceId;
    private String traceId;
    private String outcome;
    private String ipAddress;
    private String userAgent;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> detail;
}
