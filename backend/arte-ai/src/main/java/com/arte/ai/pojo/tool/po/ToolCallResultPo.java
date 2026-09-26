package com.arte.ai.pojo.tool.po;

import com.arte.ai.common.enums.tool.ToolResultStatusEnum;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * AI 工具调用结果实体
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
@TableName(value = "arte_ai_tool_call_result", autoResultMap = true)
public class ToolCallResultPo extends ToolPersistencePo {

    @Serial
    private static final long serialVersionUID = 7484853214163874285L;
    private String resultId;
    private String callId;
    private String taskId;
    private ToolResultStatusEnum status;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> output;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> content;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> artifacts;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> usageInfo;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> errorInfo;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metadata;
    private LocalDateTime completedAt;
}
