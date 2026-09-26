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
 * AI 工作流检查点实体
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
@TableName(value = "arte_ai_workflow_checkpoint", autoResultMap = true)
public class WorkflowCheckpointPo extends ToolPersistencePo {

    @Serial
    private static final long serialVersionUID = 2752587942456138165L;
    private String checkpointId;
    private String runId;
    private Long sequence;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> state;
}
