package com.arte.ai.pojo.tool.po;

import com.arte.ai.common.enums.tool.ToolExecutionModeEnum;
import com.arte.ai.common.enums.tool.ToolResultStatusEnum;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * AI 工具调用实体
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
@TableName(value = "arte_ai_tool_call", autoResultMap = true)
public class ToolCallPo extends ToolPersistencePo {

    @Serial
    private static final long serialVersionUID = -5166510064363212700L;
    private String callId;
    private String traceId;
    private String spanId;
    private String ownerId;
    private String subjectId;
    private String convId;
    private String messageId;
    private String sourceType;
    private String sourceId;
    private String toolId;
    private String toolVersion;
    private String bindingId;
    private ToolExecutionModeEnum executionMode;
    private String argumentsDigest;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> argumentsSnapshot;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> contextSnapshot;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> policySnapshot;
    private ToolResultStatusEnum status;
    private String idempotencyKey;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Long latencyMs;
    private String errorCode;
    private String errorCategory;
    private String errorMessage;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer totalTokens;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metadata;
    @Version
    private Long rowVersion;
}
