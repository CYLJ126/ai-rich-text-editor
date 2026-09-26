package com.arte.ai.pojo.tool.po;

import com.arte.ai.common.enums.tool.WorkflowNodeTypeEnum;
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
 * AI 工作流节点运行实体
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
@TableName(value = "arte_ai_workflow_node_run", autoResultMap = true)
public class WorkflowNodeRunPo extends ToolPersistencePo {

    @Serial
    private static final long serialVersionUID = 133528177052007415L;
    private String nodeRunId;
    private String runId;
    private String nodeId;
    private WorkflowNodeTypeEnum nodeType;
    private Integer attempt;
    private String status;
    private String callId;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> inputs;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> outputs;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> errorInfo;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Long latencyMs;
    @Version
    private Long rowVersion;
}
