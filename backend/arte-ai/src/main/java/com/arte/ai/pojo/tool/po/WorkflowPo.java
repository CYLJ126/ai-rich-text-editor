package com.arte.ai.pojo.tool.po;

import com.arte.ai.common.enums.tool.ToolLifecycleStateEnum;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;

/**
 * AI 工作流目录实体
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
@TableName("arte_ai_workflow")
public class WorkflowPo extends ToolPersistencePo {

    @Serial
    private static final long serialVersionUID = -5680072022022621313L;
    private String workflowId;
    private String ownerId;
    private String name;
    private String description;
    private String latestVersion;
    private ToolLifecycleStateEnum lifecycleState;
}
