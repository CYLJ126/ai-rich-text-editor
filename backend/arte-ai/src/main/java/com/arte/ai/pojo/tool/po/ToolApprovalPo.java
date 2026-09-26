package com.arte.ai.pojo.tool.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * AI 工具审批实体
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
@TableName(value = "arte_ai_tool_approval", autoResultMap = true)
public class ToolApprovalPo extends ToolPersistencePo {

    @Serial
    private static final long serialVersionUID = -268847555793413863L;
    private String requestId;
    private String callId;
    private String taskId;
    private String workflowRunId;
    private String toolId;
    private String toolVersion;
    private String argumentsDigest;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> displayArguments;
    private String summary;
    private String status;
    private String approverId;
    private String decisionReason;
    private LocalDateTime expiresAt;
    private LocalDateTime decidedAt;
}
