package com.arte.ai.pojo.tool.po;

import com.arte.ai.common.enums.tool.ToolLifecycleStateEnum;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 工作流版本实体
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
@TableName(value = "arte_ai_workflow_version", autoResultMap = true)
public class WorkflowVersionPo extends ToolPersistencePo {

    @Serial
    private static final long serialVersionUID = -6231887830729064641L;
    private String workflowId;
    private String version;
    private String name;
    private String description;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Set<String> tags;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> inputSchema;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> outputSchema;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> executionPolicy;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> nodes;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> edges;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> compiledPlan;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> pinnedTools;
    private String entryNodeId;
    private String checksum;
    private ToolLifecycleStateEnum lifecycleState;
    private LocalDateTime publishedAt;
    @Version
    private Long rowVersion;
}
