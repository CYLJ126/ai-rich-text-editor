package com.arte.ai.pojo.tool.po;

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
import java.util.Set;

/**
 * AI 工作流运行实体
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
@TableName(value = "arte_ai_workflow_run", autoResultMap = true)
public class WorkflowRunPo extends ToolPersistencePo {

    @Serial
    private static final long serialVersionUID = 8337257094950536510L;
    private String runId;
    private String workflowId;
    private String workflowVersion;
    private String ownerId;
    private String subjectId;
    private String traceId;
    private String status;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> inputs;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> variables;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Set<String> activeNodeIds;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> outputs;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> errorInfo;
    private String resumeTokenHash;
    private Integer maximumSteps;
    private Integer currentSteps;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime deadlineAt;
    private String workerId;
    private LocalDateTime leaseUntil;
    @Version
    private Long rowVersion;
}
