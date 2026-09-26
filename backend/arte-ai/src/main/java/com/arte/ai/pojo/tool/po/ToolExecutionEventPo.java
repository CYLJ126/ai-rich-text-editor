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
 * AI 工具执行事件实体
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
@TableName(value = "arte_ai_tool_execution_event", autoResultMap = true)
public class ToolExecutionEventPo extends ToolPersistencePo {

    @Serial
    private static final long serialVersionUID = 851540475134733577L;
    private String eventId;
    private String eventType;
    private LocalDateTime occurredAt;
    private String traceId;
    private String spanId;
    private String callId;
    private String taskId;
    private String workflowRunId;
    private String toolId;
    private String toolVersion;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> attributes;
}
